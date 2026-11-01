package com.rj.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rj.app.data.AppSettings
import com.rj.app.show.OnAir
import com.rj.app.show.ShowState
import com.rj.app.show.Who
import com.rj.core.CueType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults

@Composable
fun HomeScreen(
    state: ShowState,
    settings: AppSettings,
    hasMediaAccess: Boolean,
    onStart: (String?) -> Unit,
    onTalk: () -> Unit,
    onFeature: (CueType) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onDestination: (String?) -> Unit,
    onSettings: () -> Unit,
    onGrantMediaAccess: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    settings.persona.carName.ifBlank { "Your car" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(settings.persona.style.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OnAirPill(state.status)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }

        // Setup hints
        if (settings.apiKey.isBlank()) {
            Hint("Add your Claude API key in Settings to give ${settings.persona.carName} a brain.", "Settings", onSettings)
        }
        if (!hasMediaAccess) {
            Hint(
                "Allow notification access so the RJ can see what's playing and cue songs in ${settings.musicApp.label}.",
                "Allow", onGrantMediaAccess,
            )
        }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
        if (state.live && !state.online) {
            Text("Offline: quizzes, trivia, jokes and your phone's music keep the show going.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        state.quiz?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        // Last thing the RJ said: big and glanceable.
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(20.dp).fillMaxSize()) {
                val listState = rememberLazyListState()
                LaunchedEffect(state.transcript.size) {
                    if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.lastIndex)
                }
                if (state.transcript.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.live) "…" else "Tap Start drive and ${settings.persona.carName} goes on air.",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.transcript) { line ->
                            val isLast = line === state.transcript.last()
                            when (line.who) {
                                Who.RJ -> Text(
                                    line.text,
                                    fontSize = if (isLast) 24.sp else 16.sp,
                                    lineHeight = if (isLast) 32.sp else 22.sp,
                                    modifier = Modifier.alpha(if (isLast) 1f else 0.6f),
                                )
                                Who.COHOST -> Text(
                                    "${state.cohostName.ifBlank { "Co-host" }}: ${line.text}",
                                    color = Color(0xFF8FD3FF),
                                    fontSize = if (isLast) 22.sp else 16.sp,
                                    lineHeight = if (isLast) 30.sp else 22.sp,
                                    modifier = Modifier.alpha(if (isLast) 1f else 0.6f),
                                )
                                Who.YOU -> Text(
                                    "You: ${line.text}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 16.sp,
                                )
                                Who.SONG -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(line.text, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        state.nowPlaying?.takeIf { state.live }?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it; if (state.live) onDestination(it.ifBlank { null }) },
            label = { Text("Where are we heading? (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!state.live) {
            Button(
                onClick = { onStart(destination.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(20.dp),
            ) { Text("Start drive", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        } else {
            // One-tap show features.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Quiz me" to CueType.QUIZ,
                    "Podcast" to CueType.PODCAST,
                    "Local trivia" to CueType.TRIVIA,
                    "News" to CueType.NEWS_BREAK,
                    "Joke" to CueType.JOKE,
                ).forEach { (label, type) ->
                    AssistChip(
                        onClick = { onFeature(type) },
                        label = { Text(label, fontSize = 16.sp) },
                        modifier = Modifier.height(48.dp),
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            // Big, glove-friendly controls.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.height(72.dp), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "End drive")
                }
                Button(
                    onClick = onTalk,
                    modifier = Modifier.weight(1f).height(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.status == OnAir.LISTENING) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.status == OnAir.LISTENING) "Listening…" else "Talk", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = onSkip, modifier = Modifier.height(72.dp), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

@Composable
private fun OnAirPill(status: OnAir) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val live = status != OnAir.OFF
    val color = when (status) {
        OnAir.OFF -> Color(0xFF4A4060)
        OnAir.LISTENING -> MaterialTheme.colorScheme.secondary
        OnAir.MUSIC -> Color(0xFF3FB68B)
        else -> Color(0xFFE53935)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color).alpha(if (live) a else 1f))
        Spacer(Modifier.width(8.dp))
        Text(status.label.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun Hint(text: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClick) { Text(action) }
    }
}
