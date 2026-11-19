package com.rj.app.connect

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rj.app.R
import com.rj.app.data.SettingsStore
import com.rj.app.ui.MainActivity
import com.rj.core.Obd
import com.rj.core.VehicleStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.UUID

private fun Context.canBluetooth() = Build.VERSION.SDK_INT < 31 ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** Talks to a Bluetooth ELM327 OBD-II adapter plugged into the car's diagnostic port. */
class ObdLink(private val context: Context) {
    private var socket: BluetoothSocket? = null
    @Volatile var last: VehicleStatus? = null
        private set

    @SuppressLint("MissingPermission")
    fun pairedAdapters(): List<Pair<String, String>> {
        if (!context.canBluetooth()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.map { (it.name ?: it.address) to it.address } }.getOrDefault(emptyList())
            .sortedByDescending { (name, _) -> Regex("obd|elm|vgate|v-link|vlink|obdii|carista|veepeak", RegexOption.IGNORE_CASE).containsMatchIn(name) }
    }

    /** Connect if needed and read the standard PIDs. Blocking: call on IO. */
    @SuppressLint("MissingPermission")
    fun poll(address: String): VehicleStatus? {
        if (address.isBlank() || !context.canBluetooth()) return null
        return runCatching {
            val s = socket?.takeIf { it.isConnected } ?: run {
                val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
                device.createRfcommSocketToServiceRecord(SPP).also { sock ->
                    sock.connect()
                    socket = sock
                    Obd.INIT.forEach { cmd -> send(sock, cmd) }
                }
            }
            var status = last ?: VehicleStatus()
            Obd.POLLED.forEach { pid -> status = Obd.apply(status, pid, send(s, Obd.request(pid))) }
            status.also { last = it }
        }.getOrElse { close(); null }
    }

    private fun send(s: BluetoothSocket, cmd: String): String {
        s.outputStream.write("$cmd\r".toByteArray())
        s.outputStream.flush()
        return readUntilPrompt(s.inputStream)
    }

    private fun readUntilPrompt(input: InputStream, timeoutMs: Long = 3_000): String {
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0 || c == '>'.code) break
                sb.append(c.toChar())
            } else Thread.sleep(20)
        }
        return sb.toString()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** Learns which Bluetooth device is the car, and offers to go on air when the phone connects to it. */
object CarBluetooth {
    private const val CHANNEL = "car_connected"

    /** Name of the currently connected Bluetooth audio device (the car's head unit while driving). */
    @SuppressLint("MissingPermission")
    suspend fun connectedAudioDevice(context: Context): String? {
        if (!context.canBluetooth()) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val result = CompletableDeferred<String?>()
        val ok = runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    result.complete(runCatching { proxy.connectedDevices.firstOrNull()?.name }.getOrNull())
                    adapter.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) { result.complete(null) }
            }, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!ok) return null
        return withTimeoutOrNull(3_000) { withContext(Dispatchers.Default) { result.await() } }
    }

    fun offerToStart(context: Context, carName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car connected", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 11,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(11, android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Connected to $carName")
            .setContentText("Tap to go on air")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}

/** Fires when any Bluetooth device connects; offers the show if it's the car. */
class CarConnectedReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED || !context.canBluetooth()) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val car = SettingsStore(context).load().carBluetoothName
        val name = runCatching { device.name }.getOrNull() ?: return
        if (car.isNotBlank() && name == car && !com.rj.app.RjApp.isLive) CarBluetooth.offerToStart(context, name)
    }
}

