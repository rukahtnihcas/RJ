package earth.ripple.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import earth.ripple.core.KnowledgeBase
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RippleTheme { App() } }
    }
}

private enum class Screen { SCAN, JOURNAL, SETTINGS }

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    var screen by rememberSaveable { mutableStateOf(Screen.SCAN) }

    val detail by vm.detail.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rewards by vm.rewards.collectAsStateWithLifecycle()

    if (!granted) {
        Welcome(denied) { ask.launch(Manifest.permission.CAMERA) }
        return
    }

    val d = detail
    when {
        d != null -> {
            BackHandler { vm.closeDetail() }
            DetailScreen(
                state = d, profile = profile, others = journal.filter { it.key != d.entry.key }, aiEnabled = settings.aiEnabled,
                onBack = vm::closeDetail,
                onShare = { runCatching { ShareCard.share(context, d.entry, d.compareWith) }.onSuccess { vm.shared() } },
                onPledge = vm::pledge, onAsk = vm::ask, onCompare = vm::compare,
            )
            RewardToasts(rewards, Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp))
        }
        screen == Screen.JOURNAL -> {
            BackHandler { screen = Screen.SCAN }
            JournalScreen(profile, journal, vm.today, onBack = { screen = Screen.SCAN }, onOpen = vm::open, onTogglePledge = vm::togglePledge)
        }
        screen == Screen.SETTINGS -> {
            BackHandler { screen = Screen.SCAN }
            SettingsScreen(settings, vm::updateSettings) { screen = Screen.SCAN }
        }
        else -> ScanScreen(vm, onJournal = { screen = Screen.JOURNAL }, onSettings = { screen = Screen.SETTINGS })
    }
}

/** First run. The chart cycles through real objects so the idea lands before the camera opens. */
@Composable
fun Welcome(denied: Boolean, onStart: () -> Unit) {
    val samples = remember { listOf("tshirt", "tree", "phone", "bicycle", "beef-burger", "pollinator").mapNotNull(KnowledgeBase::byId) }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2600); index = (index + 1) % samples.size } }
    val a = samples[index]
    Column(
        Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("Everything has\na ripple.", style = MaterialTheme.typography.displaySmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(46f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Ink.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("Point your camera at anything and see what it does to climate, water, health, nature and people.", color = Ink.Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        RadarChart(a.values, Modifier.fillMaxWidth().height(300.dp), animateKey = a.id)
        Text("${a.emoji}  ${a.name}", color = Ink.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(a.headline, color = Ink.Text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, minLines = 4, maxLines = 4)
        Spacer(Modifier.height(22.dp))
        Text(
            "Open the camera", color = Ink.Bg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.Leaf).clickable(role = Role.Button, onClick = onStart).padding(vertical = 17.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (denied) "Ripple needs the camera to see what you point at. If nothing happens, allow Camera for Ripple in your phone's app settings."
            else "The camera is only used to recognise what you point at. Nothing leaves your phone unless you turn on AI analysis.",
            color = Ink.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
    }
}
