package org.terra.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.terra.app.data.PhotoStore
import java.io.File
import java.text.DateFormat
import java.util.Date

val cardShape = RoundedCornerShape(6.dp)

@Composable
fun PaperCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tint: Color? = null,
    padding: PaddingValues = PaddingValues(14.dp), content: @Composable ColumnScope.() -> Unit
) {
    val m = modifier.fillMaxWidth().clip(cardShape).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Surface(m, shape = cardShape, color = tint ?: pigments.card, border = BorderStroke(1.dp, pigments.line)) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Small-caps margin label, like a printed field-guide heading. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = pigments.inkSoft) {
    Text(text.uppercase(), modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

/** A specimen stamp: the organism's glyph inside an inked ring. */
@Composable
fun Stamp(glyph: String, size: Dp = 48.dp, known: Boolean = true, ring: Color = pigments.ink, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(if (known) pigments.card else pigments.wash)
            .border(if (known) 1.5.dp else 1.dp, if (known) ring else pigments.line, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(if (known) glyph else "?", fontSize = (size.value * 0.46f).sp, color = if (known) pigments.ink else pigments.inkSoft, textAlign = TextAlign.Center)
    }
}

@Composable
fun Tag(text: String, color: Color = pigments.inkSoft, modifier: Modifier = Modifier) {
    Text(
        text, modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = pigments.moss) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pigments.wash)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.titleSmall, color = pigments.inkSoft)
        }
        trailing()
    }
}

@Composable
fun Photo(path: String?, modifier: Modifier = Modifier) {
    if (path == null) return
    AsyncImage(
        model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(pigments.wash).border(1.dp, pigments.line, RoundedCornerShape(4.dp))
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T?) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) FilterChip(
            selected = o == selected, onClick = { onSelect(if (o == selected) null else o) }, label = { Text(label(o)) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = pigments.moss, selectedLabelColor = pigments.card)
        )
    }
}

@Composable
fun Rule(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(pigments.line))

@Composable fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))
@Composable fun GapW(w: Dp = 12.dp) = Spacer(Modifier.width(w))

fun day(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
fun dayTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val days = (now - ms) / 86_400_000L
    return when {
        days < 1 -> "today"
        days < 2 -> "yesterday"
        days < 60 -> "$days days ago"
        days < 730 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}

/** Takes or picks a photo into app-private storage and hands back the file. */
class PhotoTaker(val take: (String, (File) -> Unit) -> Unit, val pick: (String, (File) -> Unit) -> Unit)

@Composable
fun rememberPhotoTaker(photos: PhotoStore, onError: (String) -> Unit): PhotoTaker {
    var pending by remember { mutableStateOf<Pair<File, (File) -> Unit>?>(null) }
    var pickFor by remember { mutableStateOf<Pair<String, (File) -> Unit>?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pending
        pending = null
        if (ok && p != null && p.first.length() > 0) p.second(p.first) else p?.first?.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val p = pickFor
        pickFor = null
        if (uri != null && p != null) photos.importFrom(uri, p.first)?.let(p.second) ?: onError("That image could not be opened.")
    }
    return remember(photos) {
        PhotoTaker(
            take = { prefix, done ->
                val f = photos.newFile(prefix)
                pending = f to done
                runCatching { camera.launch(photos.uriFor(f)) }.onFailure { pending = null; onError("No camera app is available.") }
            },
            pick = { prefix, done ->
                pickFor = prefix to done
                runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { pickFor = null; onError("No photo picker is available.") }
            }
        )
    }
}
