package com.rj.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by Health Connect (and Android's permission screens) to explain how RJ uses your data. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RjTheme {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("How RJ uses your data", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "RJ reads your sleep, steps, heart rate, workouts and active calories from Health Connect " +
                            "(for example from Samsung Health and your Galaxy Watch) only while a drive is on air. It uses " +
                            "them to set the tone of the show: gentler after a short night, a cheer after a workout, " +
                            "calmer when your heart rate is up in traffic."
                    )
                    Text(
                        "RJ never writes health data and never stores it. A short summary (for example \"slept 6h 10m, " +
                            "4,200 steps\") is included in what the show's AI sees when you've added a Claude API key. " +
                            "Without a key, everything stays on the phone."
                    )
                    Text(
                        "Messages and emails: RJ sees notifications from your chat, email and car apps. By default only " +
                            "who sent something (and which app) goes to the AI; message text is shared only if you turn " +
                            "that on in Settings. Replies are only ever sent after you say yes."
                    )
                    Text("You can revoke any of this at any time in Android Settings or Health Connect.")
                }
            }
        }
    }
}
