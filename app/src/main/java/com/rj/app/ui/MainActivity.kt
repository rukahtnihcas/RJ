package com.rj.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rj.app.RjApp
import com.rj.app.data.SettingsStore
import com.rj.app.show.RjService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A car-mounted phone shouldn't go to sleep mid-show.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = (application as RjApp).engine
        val store = SettingsStore(this)

        setContent {
            RjTheme {
                val state by engine.state.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var settings by remember { mutableStateOf(store.load()) }
                var pendingDestination by remember { mutableStateOf<String?>(null) }

                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    // Start regardless: every context source degrades gracefully if its permission is denied.
                    RjService.send(this, RjService.ACTION_START, pendingDestination)
                }

                if (showSettings) {
                    BackHandler { store.save(settings); showSettings = false }
                    SettingsScreen(
                        settings = settings,
                        onChange = { settings = it },
                        onClose = { store.save(settings); showSettings = false },
                        hasMediaAccess = engine.music.streaming.hasSessionAccess(),
                        onGrantMediaAccess = { engine.music.streaming.openSessionAccessSettings() },
                        onClearMemories = { com.rj.app.data.MemoryStore(this).clear() },
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

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CALENDAR)
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
