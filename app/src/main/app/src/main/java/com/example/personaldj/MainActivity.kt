package com.example.personaldj

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

// --- THE ACTIVITY (UI) ---
class MainActivity : AppCompatActivity() {
    private var djService: DJRadioService? = null
    private var isBound = false
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DJRadioService.LocalBinder
            djService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI constructed in code (no XML needed for this quick build)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF121212.toInt())
        }
        val text = TextView(this).apply {
            text = "Personal DJ AI"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val btn = Button(this).apply {
            text = "Start Radio"
            setOnClickListener {
                val intent = Intent(this@MainActivity, DJRadioService::class.java)
                startService(intent)
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
        layout.addView(text)
        layout.addView(btn)
        setContentView(layout)
    }
}

// --- THE SERVICE (DJ LOGIC) ---
class DJRadioService : Service(), TextToSpeech.OnInitListener {
    private lateinit var player: ExoPlayer
    private lateinit var tts: TextToSpeech
    inner class LocalBinder : android.os.Binder() { fun getService() = this@DJRadioService }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        player = ExoPlayer.Builder(this).build()
        
        // Load a reliable test stream
        val item = MediaItem.fromUri("https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3")
        player.addMediaItem(item)
        player.prepare()
        player.play()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            // Example DJ Interruption after 5 seconds
            android.os.Handler(mainLooper).postDelayed({
                player.volume = 0.1f // Duck volume
                tts.speak("This is your AI DJ. Up next, more Jazz.", TextToSpeech.QUEUE_FLUSH, null, "ID")
            }, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        player.release()
        tts.shutdown()
        super.onDestroy()
    }
}
