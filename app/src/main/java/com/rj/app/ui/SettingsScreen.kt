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
import com.rj.app.data.MusicApp
import com.rj.core.Persona

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onClose: () -> Unit,
    hasMediaAccess: Boolean,
    onGrantMediaAccess: () -> Unit,
    onClearMemories: () -> Unit,
) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicApp.entries.forEach { m ->
                FilterChip(selected = settings.musicApp == m, onClick = { onChange(settings.copy(musicApp = m)) }, label = { Text(m.label) })
            }
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

        Section("Brain")
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
