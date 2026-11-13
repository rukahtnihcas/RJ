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
import earth.ripple.core.Provider
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import earth.ripple.core.Dimension

@Composable
fun SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit, recognizer: Recognizer.Status = Recognizer.Status.Missing) {
    var provider by remember { mutableStateOf(settings.provider) }
    var keys by remember { mutableStateOf(settings.keys) }
    var models by remember { mutableStateOf(settings.models) }
    var customUrl by remember { mutableStateOf(settings.customUrl) }
    val draft = settings.copy(
        provider = provider, keys = keys.mapValues { it.value.trim() }, models = models.mapValues { it.value.trim() }, customUrl = customUrl.trim(),
    )
    val changed = draft != settings
    fun commit() { if (changed) onChange(draft) }
    val p = Provider.byId(provider)

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Pill({ commit(); onBack() }, "Back to camera") { Text("←  Camera", color = Ink.Text, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = Ink.Text)

            Section("On-device recognition")
            Text(
                when (recognizer) {
                    is Recognizer.Status.Ready -> "Ready: ${recognizer.model}. Identifies objects from the photo itself, on your phone, with no internet. About ${recognizer.millis} ms per photo on this phone."
                    Recognizer.Status.Preparing -> "Preparing the vision model. The first launch after installing takes a little while."
                    Recognizer.Status.Missing -> "This build has no vision model, so Ripple uses a basic labeler that is often vague."
                    is Recognizer.Status.Failed -> "The vision model could not start on this phone (${recognizer.reason}). Using a basic labeler instead."
                },
                color = Ink.Text.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium,
            )

            Section("AI analysis", if (settings.aiEnabled) "On: ${settings.active.label}" else "Off")
            Text(
                "Optional. With AI analysis on, Ripple also sends the photo to an AI model that can name the exact product, material and brand, " +
                    "read labels and certifications on the pack, score that specific item, and answer your own questions. Free options work well.",
                color = Ink.Text.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Provider.all.forEach { option ->
                val on = option.id == provider
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) Ink.Raised else Ink.Surface)
                        .clickable(role = Role.RadioButton) { provider = option.id }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp).border(2.dp, if (on) Ink.Leaf else Ink.Muted, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(10.dp).background(Ink.Leaf, CircleShape))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(option.label, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                        Text(option.note, color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (p.keyUrl.isNotEmpty()) {
                Text("Get a key at ${p.keyUrl}, then paste it here.", color = Ink.Zero, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (p == Provider.CUSTOM) {
                OutlinedTextField(
                    value = customUrl, onValueChange = { customUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Base URL") }, placeholder = { Text("https://…/v1") }, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = keys[provider].orEmpty(), onValueChange = { keys = keys + (provider to it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("API key for ${p.label.substringBefore(" (").substringBefore(" via")}") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = models[provider].orEmpty(), onValueChange = { models = models + (provider to it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Model") }, placeholder = { Text(p.defaultModel.ifEmpty { "an image-capable model" }) }, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            if (p.defaultModel.isNotEmpty()) Text("Leave blank to use ${p.defaultModel}.", color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text(
                if (changed) "Save" else "Saved",
                color = if (changed) Ink.Bg else Ink.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(22.dp))
                    .background(if (changed) Ink.Leaf else Ink.Raised)
                    .clickable(role = Role.Button) { commit() }.padding(horizontal = 22.dp, vertical = 13.dp),
            )
            Note(
                "Privacy: with AI analysis on, a cropped photo of what you scan is sent to the service you chose above (Anthropic, OpenRouter and the model's host, or Google), together with the on-device labels and your country code. Free services may keep or learn from what you send. " +
                    "Nothing is sent otherwise, apart from a barcode number to Open Food Facts when you scan one. Keys are stored encrypted on this phone and your journal never leaves it."
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
