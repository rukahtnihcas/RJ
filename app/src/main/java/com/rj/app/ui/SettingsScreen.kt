package com.rj.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rj.app.data.AppSettings
import com.rj.app.data.BrainMode
import com.rj.app.data.MusicApp
import com.rj.app.data.MusicSource
import com.rj.app.data.OnDeviceAi
import com.rj.app.data.VoiceAccent
import com.rj.app.data.VoiceEngine
import androidx.compose.material3.Switch
import com.rj.core.Persona

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onClose: () -> Unit,
    connections: ConnectionsUi,
    onClearMemories: () -> Unit,
) {
    val hasMediaAccess = connections.hasNotificationAccess
    val onGrantMediaAccess = connections.onGrantNotificationAccess
    val p = settings.profile
    val persona = settings.persona
    // Interests are edited as free text and parsed on change so typing commas feels natural.
    var interestsText by remember { mutableStateOf(p.interests.joinToString(", ")) }
    var memoriesCleared by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Section("Your car's character")
        Field("Car name", persona.carName) { onChange(settings.copy(persona = persona.copy(carName = it))) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Persona.Style.entries.forEach { style ->
                FilterChip(
                    selected = persona.style == style,
                    onClick = { onChange(settings.copy(persona = persona.copy(style = style))) },
                    label = { Text(style.label) },
                )
            }
        }
        Text(persona.style.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("Quirks (optional)", persona.quirks, lines = 2, hint = "e.g. obsessed with cricket stats, loves a pun") {
            onChange(settings.copy(persona = persona.copy(quirks = it)))
        }

        Field("Co-host name (podcast segments)", persona.cohostName) {
            onChange(settings.copy(persona = persona.copy(cohostName = it)))
        }
        Field("Co-host personality", persona.cohostVibe, lines = 2) {
            onChange(settings.copy(persona = persona.copy(cohostVibe = it)))
        }

        Section("Voice")
        Chips(VoiceEngine.entries, settings.voiceEngine, { it.label }) { onChange(settings.copy(voiceEngine = it)) }
        Text("Accent", style = MaterialTheme.typography.bodyMedium)
        Chips(VoiceAccent.entries, settings.accent, { it.label }) { onChange(settings.copy(accent = it)) }
        Toggle("Deep voice", settings.deepVoice) { onChange(settings.copy(deepVoice = it)) }
        if (settings.voiceEngine == VoiceEngine.ELEVENLABS) {
            Field("ElevenLabs API key", settings.elevenLabsKey, secret = true) { onChange(settings.copy(elevenLabsKey = it)) }
            Field("Host voice ID", settings.hostVoiceId) { onChange(settings.copy(hostVoiceId = it)) }
            Field("Co-host voice ID", settings.cohostVoiceId) { onChange(settings.copy(cohostVoiceId = it)) }
            Hint("Defaults: Daniel (deep British broadcaster) hosts, Alice (British) co-hosts. Any voice ID from the ElevenLabs Voice Library works. Falls back to the phone's voice when offline.")
        } else {
            Hint("For the best phone voice: Android Settings → Text-to-speech → Speech Services by Google → install the English (United Kingdom) voices. The app picks a deep male British voice when one is installed.")
        }

        Section("About you")
        Field("Your name", p.name) { onChange(settings.copy(profile = p.copy(name = it))) }
        Field("Home city", p.homeCity) { onChange(settings.copy(profile = p.copy(homeCity = it))) }
        Field("Interests (comma separated)", interestsText, hint = "F1, startups, cricket, space") {
            interestsText = it
            onChange(settings.copy(profile = p.copy(interests = it.split(',').map(String::trim).filter(String::isNotEmpty))))
        }
        Field("Music taste", p.musicTaste, lines = 2, hint = "90s Bollywood, indie rock, no country please") {
            onChange(settings.copy(profile = p.copy(musicTaste = it)))
        }
        Field(
            "Key stuff in your life", p.keyStuff, lines = 4,
            hint = "Partner Priya, kids Aarav (8) & Mira (5). Training for a half marathon. Launching v2 at work this month.",
        ) { onChange(settings.copy(profile = p.copy(keyStuff = it))) }
        Field("Language & style", p.language, hint = "English, or e.g. English with a sprinkle of Hindi") {
            onChange(settings.copy(profile = p.copy(language = it)))
        }

        Section("Music")
        Chips(MusicSource.entries, settings.musicSource, { it.label }) { onChange(settings.copy(musicSource = it)) }
        Hint(when (settings.musicSource) {
            MusicSource.AUTO -> "Uses your streaming app when online, and the music saved on your phone when offline or if the app won't play."
            MusicSource.LIBRARY -> "Plays music files saved on your phone with the built-in player. Works fully offline, and the RJ picks from your own collection."
            MusicSource.STREAMING -> "Cues songs in your streaming app. Needs internet."
        })
        if (settings.musicSource != MusicSource.LIBRARY) {
            Text("Streaming app", style = MaterialTheme.typography.bodyMedium)
            Chips(MusicApp.entries, settings.musicApp, { it.label }) { onChange(settings.copy(musicApp = it)) }
        }
        if (!hasMediaAccess) {
            OutlinedButton(onClick = onGrantMediaAccess) { Text("Allow notification access (see & cue songs)") }
        }
        Text("Talk break every ~${settings.linkEveryMinutes} min when track timing isn't available",
            style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.linkEveryMinutes.toFloat(),
            onValueChange = { onChange(settings.copy(linkEveryMinutes = it.toInt())) },
            valueRange = 2f..10f, steps = 7,
        )

        Section("Connections")
        Hint("Each of these gives the RJ more to work with. All optional.")

        Text("Messages & email (WhatsApp, SMS, Outlook, Gmail, Teams, car app)", style = MaterialTheme.typography.bodyMedium)
        if (!hasMediaAccess) OutlinedButton(onClick = onGrantMediaAccess) { Text("Allow notification access") }
        Toggle("Mention new messages on air", settings.announceMessages) { onChange(settings.copy(announceMessages = it)) }
        Toggle("Let the AI see message text (for summaries)", settings.shareMessageText) { onChange(settings.copy(shareMessageText = it)) }
        Field("VIPs: announce straight away", settings.vipNames, hint = "Priya, Mum, Boss") { onChange(settings.copy(vipNames = it)) }
        Hint("Say \"read it\" to hear messages, or \"reply to Priya\" to answer by voice. Nothing is sent until you say yes.")

        Text("Samsung Health & Galaxy Watch", style = MaterialTheme.typography.bodyMedium)
        if (!connections.healthAvailable) {
            Hint("Install or update Health Connect, then in Samsung Health: Settings → Health Connect → allow sharing.")
        } else if (!connections.healthGranted) {
            OutlinedButton(onClick = connections.onConnectHealth) { Text("Connect sleep, steps & heart rate") }
        } else {
            Hint("Connected: sleep, steps, heart rate and workouts shape the show's tone.")
        }
        Toggle("Use health data", settings.useHealth) { onChange(settings.copy(useHealth = it)) }

        Text("Outlook calendar & email (Microsoft 365)", style = MaterialTheme.typography.bodyMedium)
        if (connections.msSignedIn) {
            Hint("Connected. Meetings, organisers and important unread mail are on the show; it can email an organiser if you're running late.")
            OutlinedButton(onClick = connections.onMsSignOut) { Text("Disconnect Outlook") }
        } else {
            Field("Azure app (client) ID", settings.msClientId, hint = "see README: 2-minute free setup") { onChange(settings.copy(msClientId = it)) }
            OutlinedButton(onClick = connections.onMsSignIn, enabled = settings.msClientId.isNotBlank()) { Text("Sign in to Outlook") }
            Hint("No setup? Turn on calendar sync in the Outlook app and your meetings appear here automatically; email notifications come through notification access.")
        }
        connections.msCode?.let { Text(it, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
        connections.msStatus?.let { Hint(it) }

        Text("Traffic", style = MaterialTheme.typography.bodyMedium)
        Hint("Routes and ETAs come from OpenStreetMap open data using your destination (or the next meeting's address). Add a free TomTom key for live jams, road speeds and incidents.")
        Field("TomTom API key (optional)", settings.tomTomKey, secret = true) { onChange(settings.copy(tomTomKey = it)) }

        Text("Car (OBD-II adapter & Bluetooth)", style = MaterialTheme.typography.bodyMedium)
        Hint("Plug a Bluetooth ELM327 OBD-II adapter into the car, pair it with the phone, then pick it here for fuel, battery and warning lights. Alerts from the Tata iRA app (and other connected-car apps) arrive through notification access.")
        if (connections.obdAdapters.isEmpty()) {
            OutlinedButton(onClick = connections.onRequestBluetooth) { Text("Allow Bluetooth to find the adapter") }
        } else {
            Chips(listOf("" to "None") + connections.obdAdapters.map { it.second to it.first },
                connections.obdAdapters.firstOrNull { it.second == settings.obdAddress }?.let { it.second to it.first } ?: ("" to "None"),
                { it.second }) { (addr, name) -> onChange(settings.copy(obdAddress = addr, obdName = if (addr.isBlank()) "" else name)) }
        }
        if (settings.carBluetoothName.isNotBlank()) {
            Hint("Your car: ${settings.carBluetoothName}. When the phone connects to it, you'll get a tap-to-go-on-air notification.")
        } else {
            Hint("Start a drive while connected to the car's Bluetooth and RJ will remember it.")
        }

        Section("Brain")
        Chips(BrainMode.entries, settings.brainMode, { it.label }) { onChange(settings.copy(brainMode = it)) }
        Hint(if (settings.brainMode == BrainMode.AUTO) "Claude writes the show when you're online; the on-device AI (or the scripted host) takes over in dead zones."
            else "Never uses Claude: the on-device AI writes the show, with the scripted host as a safety net.")

        Text("On-device AI (private, works without signal)", style = MaterialTheme.typography.bodyMedium)
        Chips(OnDeviceAi.entries, settings.onDeviceAi, { it.label }) { onChange(settings.copy(onDeviceAi = it)) }
        Hint("Gemini Nano: ${connections.nanoStatus ?: "checking…"}. Built into newer Galaxy and Pixel phones; Android only lets the on-screen app use it, so keep RJ open while driving.")
        if (connections.nanoStatus?.startsWith("Supported") == true) {
            OutlinedButton(onClick = connections.onDownloadNano) { Text("Get Gemini Nano") }
        }
        Hint("Gemma 4: ${connections.gemmaStatus}. Google's open model, runs on the phone's GPU, works in the background. One-time 2.6 GB download over Wi-Fi.")
        if (connections.gemmaStatus.startsWith("Ready")) {
            OutlinedButton(onClick = connections.onDeleteGemma) { Text("Delete Gemma 4 (free 2.6 GB)") }
        } else if (connections.gemmaStatus == "Not downloaded") {
            OutlinedButton(onClick = connections.onDownloadGemma) { Text("Download Gemma 4 (2.6 GB, Wi-Fi)") }
        }
        Toggle("Use on-device AI even when online", settings.preferOnDevice) { onChange(settings.copy(preferOnDevice = it)) }
        Hint("Fully private shows. Claude is sharper and can search the web; the on-device AI talks about the news the app has already fetched.")
        Field("Claude API key", settings.apiKey, secret = true, hint = "sk-ant-…") { onChange(settings.copy(apiKey = it)) }
        Field("Model", settings.model) { onChange(settings.copy(model = it)) }
        Text(
            "Get a key at console.anthropic.com. It's stored only on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Memory")
        OutlinedButton(onClick = { onClearMemories(); memoriesCleared = true }, enabled = !memoriesCleared) {
            Text(if (memoriesCleared) "Memories cleared" else "Forget everything the RJ learned about me")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

/** Everything the Connections section needs from the activity. */
data class ConnectionsUi(
    val hasNotificationAccess: Boolean,
    val onGrantNotificationAccess: () -> Unit,
    val healthAvailable: Boolean,
    val healthGranted: Boolean,
    val onConnectHealth: () -> Unit,
    val msSignedIn: Boolean,
    val msCode: String?,
    val msStatus: String?,
    val onMsSignIn: () -> Unit,
    val onMsSignOut: () -> Unit,
    val obdAdapters: List<Pair<String, String>>,
    val onRequestBluetooth: () -> Unit,
    val nanoStatus: String?,
    val onDownloadNano: () -> Unit,
    val gemmaStatus: String,
    val onDownloadGemma: () -> Unit,
    val onDeleteGemma: () -> Unit,
)

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    lines: Int = 1,
    hint: String? = null,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (hint != null) Text(hint) },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
