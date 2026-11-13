package earth.ripple.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import earth.ripple.core.AiProtocol
import earth.ripple.core.Dimension

@Composable
fun SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    fun commit() = onChange(settings.copy(apiKey = key.trim(), model = model.trim().ifBlank { AiProtocol.DEFAULT_MODEL }))

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Pill({ commit(); onBack() }, "Back to camera") { Text("←  Camera", color = Ink.Text, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = Ink.Text)

            Section("AI analysis", if (settings.aiEnabled) "On" else "Off — using the on-device library")
            Text(
                "Without a key, Ripple recognises about 50 common kinds of object on your phone and reads food barcodes. " +
                    "With a Claude API key it can identify almost anything, score that specific object, and answer your own questions.",
                color = Ink.Text.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Claude API key") }, placeholder = { Text("sk-ant-…") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Model") }, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (key.trim() != settings.apiKey || model.trim() != settings.model) "Save" else "Saved",
                color = if (key.trim() != settings.apiKey || model.trim() != settings.model) Ink.Bg else Ink.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(22.dp))
                    .background(if (key.trim() != settings.apiKey || model.trim() != settings.model) Ink.Leaf else Ink.Raised)
                    .clickable(role = Role.Button) { commit() }.padding(horizontal = 22.dp, vertical = 13.dp),
            )
            Note(
                "Privacy: with AI analysis on, a cropped photo of what you scan is sent to Anthropic's API together with the on-device labels and your country code. " +
                    "Nothing is sent otherwise, apart from a barcode number to Open Food Facts when you scan one. Your key is stored encrypted on this phone and your journal never leaves it."
            )

            Section("Scanning")
            SwitchRow("Lock on automatically", "Hold the camera on something for a moment and its chart appears.", settings.autoLock) { onChange(settings.copy(autoLock = it)) }
            SwitchRow("Guess first", "Before each reveal, guess where the object leaves its biggest mark. Right answers earn more.", settings.guessFirst) { onChange(settings.copy(guessFirst = it)) }

            Section("How the scores work")
            Text(
                "Every object gets five scores from −5 to +5. Zero means no meaningful impact, negative means harm, positive means it gives something back. " +
                    "Carbon and freshwater are logarithmic: each point is roughly ten times more. A score covers the whole life of the thing, from raw material to disposal, " +
                    "for one typical item, one serving, or one year of use.",
                color = Ink.Text.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Dimension.entries.forEach { d ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text(d.glyph, color = Ink.Leaf, modifier = Modifier.padding(end = 10.dp))
                    Column {
                        Text(d.label, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                        Text(d.question, color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Note("These are estimates meant to build intuition and prompt better questions. They are not an audit of a specific product, and real items vary widely around the typical figure.")
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
