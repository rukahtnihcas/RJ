package com.rj.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rj.app.RjApp
import com.rj.app.data.MemoryStore
import com.rj.app.data.SettingsStore
import com.rj.app.show.RjService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Set when opened from the "connected to your car" notification. */
    private var autostart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A car-mounted phone shouldn't go to sleep mid-show.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        autostart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true
        val engine = (application as RjApp).engine
        val store = SettingsStore(this)

        setContent {
            RjTheme {
                val state by engine.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var settings by remember { mutableStateOf(store.load()) }
                var pendingDestination by remember { mutableStateOf<String?>(null) }
                var healthGranted by remember { mutableStateOf(false) }
                var msCode by remember { mutableStateOf<String?>(null) }
                var msStatus by remember { mutableStateOf<String?>(null) }
                var msSignedIn by remember { mutableStateOf(engine.microsoft.signedIn) }
                var nanoStatus by remember { mutableStateOf<String?>(null) }
                var gemmaStatus by remember { mutableStateOf(engine.localAi.gemmaStatus()) }

                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    // Start regardless: every context source degrades gracefully if its permission is denied.
                    RjService.send(this, RjService.ACTION_START, pendingDestination)
                }
                val healthPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
                    healthGranted = granted.isNotEmpty()
                }
                val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

                LaunchedEffect(showSettings) {
                    healthGranted = engine.health.granted().isNotEmpty()
                    if (showSettings) {
                        nanoStatus = engine.localAi.nanoStatus()
                        // Keep the Gemma download progress fresh while Settings is open.
                        while (true) {
                            gemmaStatus = engine.localAi.gemmaStatus()
                            kotlinx.coroutines.delay(2_000)
                        }
                    }
                }
                LaunchedEffect(autostart) {
                    if (autostart && !state.live) {
                        autostart = false
                        permissions.launch(requiredPermissions())
                    }
                }

                if (showSettings) {
                    BackHandler { store.save(settings); showSettings = false }
                    SettingsScreen(
                        settings = settings,
                        onChange = { settings = it },
                        onClose = { store.save(settings); showSettings = false },
                        connections = ConnectionsUi(
                            hasNotificationAccess = engine.music.streaming.hasSessionAccess(),
                            onGrantNotificationAccess = { engine.music.streaming.openSessionAccessSettings() },
                            healthAvailable = engine.health.available(),
                            healthGranted = healthGranted,
                            onConnectHealth = { healthPermissions.launch(engine.health.permissions) },
                            msSignedIn = msSignedIn,
                            msCode = msCode,
                            msStatus = msStatus,
                            onMsSignIn = {
                                store.save(settings)
                                msStatus = "Getting a sign-in code…"
                                scope.launch {
                                    val r = engine.microsoft.signIn { code ->
                                        msCode = code.userCode
                                        msStatus = "Go to ${code.verificationUri} on any device and enter the code."
                                    }
                                    msCode = null
                                    msSignedIn = engine.microsoft.signedIn
                                    msStatus = r.fold({ "Connected to Outlook." }, { "Sign-in failed: ${it.message}" })
                                }
                            },
                            onMsSignOut = { engine.microsoft.signOut(); msSignedIn = false; msStatus = null },
                            obdAdapters = engine.obd.pairedAdapters(),
                            onRequestBluetooth = {
                                if (Build.VERSION.SDK_INT >= 31) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            },
                            nanoStatus = nanoStatus,
                            onDownloadNano = { engine.localAi.downloadNano { msg -> nanoStatus = msg } },
                            gemmaStatus = gemmaStatus,
                            onDownloadGemma = {
                                store.save(settings)
                                engine.localAi.startGemmaDownload(settings.gemmaModelUrl)
                                gemmaStatus = engine.localAi.gemmaStatus()
                            },
                            onDeleteGemma = { engine.localAi.deleteGemma(); gemmaStatus = engine.localAi.gemmaStatus() },
                        ),
                        onClearMemories = { MemoryStore(this).clear() },
                    )
                } else {
                    HomeScreen(
                        state = state,
                        settings = settings,
                        hasMediaAccess = engine.music.streaming.hasSessionAccess(),
                        onStart = { destination ->
                            pendingDestination = destination
                            permissions.launch(requiredPermissions())
                        },
                        onTalk = { engine.talk() },
                        onFeature = { engine.feature(it) },
                        onSkip = { engine.skip() },
                        onStop = { RjService.send(this, RjService.ACTION_STOP) },
                        onDestination = { engine.setDestination(it) },
                        onSettings = { settings = store.load(); showSettings = true },
                        onGrantMediaAccess = { engine.music.streaming.openSessionAccessSettings() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) autostart = true
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CALENDAR)
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
