package org.terra.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.terra.app.sensing.WalkService
import org.terra.app.ui.TerraRoot
import org.terra.app.ui.TerraTheme

class MainActivity : ComponentActivity() {
    private val vm: TerraViewModel by viewModels()

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "terra" && data.host == "inat-auth") vm.finishSignIn(data.getQueryParameter("code"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TerraTheme { TerraRoot(vm) } }
        if (savedInstanceState == null) handle(intent)
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
