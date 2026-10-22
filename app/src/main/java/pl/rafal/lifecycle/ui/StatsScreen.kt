package pl.rafal.lifecycle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.domain.Commute
import pl.rafal.lifecycle.domain.Insights
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.PeriodType
import pl.rafal.lifecycle.domain.Stats
import pl.rafal.lifecycle.domain.StatsResult
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val repo = LifeApp.instance.repo
    var type by rememberSaveable { mutableStateOf(PeriodType.WEEK) }
    var startDay by rememberSaveable(type) { mutableStateOf(Period.current(type).start.toEpochDay()) }
    val period = Period(type, LocalDate.ofEpochDay(startDay))
    val prev = period.prev()
    val from = Fmt.dayStart(prev.start)
    val to = Fmt.dayStart(period.end)
    val canNext = period.end <= LocalDate.now()

    val entries by remember(from, to) { repo.entriesIn(from, to) }.collectAsState(initial = null)
    val categories by repo.categories.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }

    val stats by produceState<StatsResult?>(null, entries, type, startDay) {
        val e = entries ?: return@produceState
        value = withContext(Dispatchers.Default) { Stats.compute(e, period, System.currentTimeMillis()) }
    }
    var selected by remember(type) { mutableStateOf<Long?>(null) }

    val places by repo.places.collectAsState(initial = emptyList())
    val insightFrom = remember { Fmt.dayStart(LocalDate.now().minusDays(57)) }
    val insightTo = remember { Fmt.dayStart(LocalDate.now().plusDays(1)) }
    val insightEntries by remember { repo.entriesIn(insightFrom, insightTo) }.collectAsState(initial = null)
    val insights by produceState<List<String>>(emptyList(), insightEntries, categories, places) {
        val e = insightEntries ?: return@produceState
        value = withContext(Dispatchers.Default) {
            Insights.compute(e, catMap, places.associateBy { it.id }, System.currentTimeMillis())
        }
    }
    val commute = remember(entries, categories, startDay, type) {
        entries?.let { list ->
            val start = Fmt.dayStart(period.start)
            Commute.compute(list.filter { it.startAt >= start }, catMap)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Statystyki") }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                PeriodType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, PeriodType.entries.size),
                    ) { Text(t.label) }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { startDay = prev.start.toEpochDay() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni okres")
                }
                Text(
                    period.label(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { startDay = period.next().start.toEpochDay() }, enabled = canNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny okres")
                }
            }
        }

        val s = stats
        when {
            s == null -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            s.totals.isEmpty() -> item {
                Text(
                    "Brak danych w tym okresie.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                )
            }
            else -> {
                val sorted = s.totals.toList().sortedByDescending { it.second }
                val totalAll = sorted.sumOf { it.second }
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                            ProportionRing(
                                sorted.map { (id, v) ->
                                    val c = catColor(catMap[id])
                                    (if (selected == null || selected == id) c else c.copy(alpha = 0.25f)) to v.toFloat()
                                },
                                Modifier.fillMaxSize(),
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val sel = selected
                                val shown = if (sel == null) totalAll else (s.totals[sel] ?: 0L)
                                Text(
                                    Fmt.durationShort(shown / s.days),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (sel == null) "śledzono / dzień" else (catMap[sel]?.name ?: "") + " / dzień",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${s.days} ${daysWord(s.days)} z danymi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item { SectionTitle("Średnio dziennie · vs poprzedni okres") }
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        sorted.forEach { (id, total) ->
                            StatRow(
                                cat = catMap[id],
                                avg = total / s.days,
                                prevAvg = (s.prevTotals[id] ?: 0L) / s.prevDays,
                                share = total.toFloat() / totalAll,
                                selected = selected == id,
                                onClick = { selected = if (selected == id) null else id },
                            )
                        }
                    }
                }

                item {
                    val sel = selected
                    SectionTitle(
                        when {
                            sel != null -> "Trend · ${catMap[sel]?.name ?: ""}"
                            type == PeriodType.YEAR -> "Średnio na dzień w miesiącach"
                            else -> "Rozkład dzień po dniu"
                        }
                    )
                }
                item {
                    val order = categories.withIndex().associate { it.value.id to it.index }
                    val bars = s.buckets.map { bucket ->
                        val sel = selected
                        if (sel != null) {
                            listOf(catColor(catMap[sel]) to (bucket[sel] ?: 0L))
                        } else {
                            bucket.toList()
                                .sortedBy { order[it.first] ?: Int.MAX_VALUE }
                                .map { (id, v) -> catColor(catMap[id]) to v }
                        }
                    }
                    Box(
                        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(14.dp)
                    ) {
                        BarsChart(bars, s.labels)
                    }
                }

                commute?.let { c ->
                    item { SectionTitle("Dojazdy · średnio") }
                    item { CommuteCard(c) }
                }
            }
        }

        if (insights.isNotEmpty()) {
            item { SectionTitle("Ciekawostki · ostatnie 8 tygodni") }
            item { InsightsCard(insights) }
        }
    }
}

private fun daysWord(n: Int): String = when {
    n == 1 -> "dzień"
    else -> "dni"
}

@Composable
private fun StatRow(cat: Category?, avg: Long, prevAvg: Long, share: Float, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(cat, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(cat?.name ?: "—", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            ShareBar(share, catColor(cat))
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.durationShort(avg), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            val diff = avg - prevAvg
            val text: String
            val color: Color
            when {
                prevAvg == 0L && avg > 0 -> { text = "nowe"; color = MaterialTheme.colorScheme.primary }
                kotlin.math.abs(diff) < 60_000L -> { text = "bez zmian"; color = MaterialTheme.colorScheme.onSurfaceVariant }
                else -> {
                    val pct = (diff * 100 / prevAvg.coerceAtLeast(1)).toInt()
                    text = (if (diff > 0) "▲ " else "▼ ") + Fmt.durationShort(kotlin.math.abs(diff)) + " (${if (pct > 0) "+" else ""}$pct%)"
                    color = if (diff > 0) Color(0xFF34C759) else Color(0xFFFF3B30)
                }
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
