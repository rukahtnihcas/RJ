package org.terra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.terra.app.sensing.WalkService
import org.terra.app.ui.TerraRoot
import org.terra.app.ui.TerraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TerraTheme { TerraRoot() } }
    }

    override fun onStart() {
        super.onStart()
        (application as TerraApp).location.start()
    }

    override fun onStop() {
        super.onStop()
        // Location is only kept running in the background while the player has Walk mode on.
        if (!WalkService.running.value) (application as TerraApp).location.stop()
    }
}
