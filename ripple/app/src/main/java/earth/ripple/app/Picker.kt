package earth.ripple.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.ripple.core.Assessment
import earth.ripple.core.KnowledgeBase
import earth.ripple.core.Rubric

/**
 * "That's not what this is." Lets the user put the identification right: likely alternatives
 * first, then the whole library, filterable by typing.
 */
@Composable
fun ObjectPicker(current: Assessment, candidates: List<String>, aiEnabled: Boolean, onPick: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val library = remember { KnowledgeBase.all.filter { it.id != "unknown" } }
    val likely = remember(candidates) { candidates.mapNotNull(KnowledgeBase::byId).filter { it.id != current.id } }
    val q = query.trim().lowercase()
    val rest = library.filter { a -> a.id != current.id && (q.isEmpty() && likely.none { it.id == a.id } || q.isNotEmpty() && a.name.lowercase().contains(q)) }
        .sortedBy { it.name }

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Pill(onClose, "Cancel") { Text("←  Back", color = Ink.Text, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("What is it really?", style = MaterialTheme.typography.displaySmall, color = Ink.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ripple thought this was “${current.name}”. Pick the closest match." +
                    if (aiEnabled) "" else " For things that aren't listed, turn on AI analysis in Settings: it can identify almost anything.",
                color = Ink.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(40) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search, e.g. shoe, coffee, laptop", color = Ink.Muted) }, shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (q.isEmpty() && likely.isNotEmpty()) {
                item { Text("MORE LIKELY", color = Ink.Zero, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)) }
                items(likely, key = { "l-" + it.id }) { PickRow(it) { onPick(it.id) } }
                item { Text("EVERYTHING ELSE", color = Ink.Zero, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)) }
            }
            items(rest, key = { it.id }) { PickRow(it) { onPick(it.id) } }
            if (rest.isEmpty() && q.isNotEmpty()) item {
                Text("Nothing in the on-device library matches “$query”.", color = Ink.Muted, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun PickRow(a: Assessment, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.emoji, fontSize = 24.sp, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
            Text(Rubric.verdict(a.average), color = Ink.score(a.average), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Loads a gallery picture, upright and no larger than needed for recognition. */
fun loadPhoto(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // ML Kit and JPEG encoding need a readable bitmap
            val scale = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    } else {
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scale = maxSide.toFloat() / maxOf(raw.width, raw.height)
        if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
    }
}.getOrNull()
