package org.terra.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.terra.app.Derived

@Composable
fun QuestScreen(d: Derived) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bounties & Bio-blitzes", "Briefs from the people who study this ground")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                PaperCard(tint = pigments.wash) {
                    Eyebrow("This season · ${d.season.label}", color = pigments.moss)
                    Gap(4.dp)
                    Text(d.season.theme, style = MaterialTheme.typography.headlineSmall)
                    Text(d.season.focus, style = MaterialTheme.typography.bodyMedium)
                    d.bounties.firstOrNull()?.let {
                        Gap(6.dp)
                        Text("The season turns on ${day(it.endsAt)}. Nothing here expires sooner than nature does.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                    }
                }
            }
            items(d.bounties, key = { it.bounty.id }) { p ->
                val b = p.bounty
                PaperCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(b.issuer, Modifier.weight(1f))
                        if (b.bioBlitz) Tag("Bio-blitz", pigments.water)
                        if (p.complete) { GapW(6.dp); Tag("Complete", pigments.moss) }
                    }
                    Gap(4.dp)
                    Text(b.title, style = MaterialTheme.typography.titleLarge)
                    Text(b.brief, style = MaterialTheme.typography.bodyMedium)
                    Gap(4.dp)
                    Text(b.why, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = pigments.inkSoft)
                    Gap(10.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Meter(p.fraction, Modifier.weight(1f), if (p.complete) pigments.moss else pigments.ochre)
                        GapW()
                        Text("${minOf(p.done, b.goal)} of ${b.goal}", style = MaterialTheme.typography.labelLarge)
                    }
                    Gap(4.dp)
                    Text(if (b.seasons == null) "Open all year · counted afresh each season" else "Open until ${day(p.endsAt)}", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                }
            }
            item {
                Text("These are sample briefs that ship with the app so bounties work offline. Regional briefs from universities, trusts and arborists arrive through a partner feed — see the README.",
                    style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
