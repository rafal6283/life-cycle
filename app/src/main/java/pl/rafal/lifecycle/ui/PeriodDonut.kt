package pl.rafal.lifecycle.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.PeriodType
import pl.rafal.lifecycle.domain.Stats
import pl.rafal.lifecycle.domain.StatsResult
import pl.rafal.lifecycle.tracking.Steps
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

private val periodLabels = listOf("Dzień", "Tydzień", "Miesiąc", "Rok")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSwitcher(index: Int, onChange: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        periodLabels.forEachIndexed { i, l ->
            SegmentedButton(
                selected = index == i,
                onClick = { onChange(i) },
                shape = SegmentedButtonDefaults.itemShape(i, periodLabels.size),
            ) { Text(l) }
        }
    }
}

/** Pierścień tygodnia / miesiąca / roku: średni dzień w kategoriach. */
@Composable
fun PeriodDonutScreen(index: Int, onPeriod: (Int) -> Unit) {
    val repo = LifeApp.instance.repo
    val type = PeriodType.entries[index - 1]
    var startDay by rememberSaveable(type) { mutableStateOf(Period.current(type).start.toEpochDay()) }
    val period = Period(type, LocalDate.ofEpochDay(startDay))
    val prev = period.prev()
    val from = Fmt.dayStart(prev.start)
    val to = Fmt.dayStart(period.end)
    val canNext = period.end <= LocalDate.now()

    val entries by remember(from, to) { repo.entriesIn(from, to) }.collectAsState(initial = null)
    val categories by repo.categories.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val stepBuckets by remember(from, to) { LifeApp.instance.db.steps().observeRange(from, to) }
        .collectAsState(initial = emptyList())
    val stats by produceState<StatsResult?>(null, entries, type, startDay) {
        val e = entries ?: return@produceState
        value = withContext(Dispatchers.Default) { Stats.compute(e, period, System.currentTimeMillis()) }
    }
    val avgSteps = remember(stepBuckets, startDay, type) {
        val cur = Steps.perDay(stepBuckets).filterKeys { it >= period.start && it < period.end }
        if (cur.isEmpty()) null else cur.values.sum() / cur.size
    }
    var selected by remember(type, startDay) { mutableStateOf<Any?>(null) }
    val screenOn = remember { pl.rafal.lifecycle.util.Prefs(LifeApp.instance).screenTime }
    val screens by remember(type, startDay) {
        LifeApp.instance.db.screen().observe(period.start.toEpochDay(), period.end.toEpochDay())
    }.collectAsState(initial = emptyList())
    val phoneByCat by produceState<Map<Long, Long>>(emptyMap(), entries, screens) {
        val e = entries ?: return@produceState
        value = if (screenOn) pl.rafal.lifecycle.domain.PhoneByActivity.compute(e, screens, System.currentTimeMillis()) else emptyMap()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        item { PeriodSwitcher(index, onPeriod) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { startDay = prev.start.toEpochDay() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni")
                }
                Text(
                    period.label(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { startDay = period.next().start.toEpochDay() }, enabled = canNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny")
                }
            }
        }
        val s = stats
        val sorted = s?.totals?.toList()?.sortedByDescending { it.second } ?: emptyList()
        val totalAll = sorted.sumOf { it.second }
        item {
            val slices = sorted.map { (id, v) ->
                val c = catMap[id]
                DonutSlice(id, catColor(c), v.toFloat(), c?.icon, Fmt.durationShort(v / (s?.days ?: 1)))
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                AnimatedDonut(
                    slices = slices,
                    selected = selected,
                    onSelect = { selected = it },
                    animKey = "$type-$startDay-${slices.size}",
                    modifier = Modifier.size(330.dp),
                    phone = if (phoneByCat.isEmpty()) null else sorted.map { (id, v) ->
                        val f = ((phoneByCat[id] ?: 0L).toFloat() / v.coerceAtLeast(1L)).coerceIn(0f, 1f)
                        if (f > 0f) listOf(0f to f) else emptyList()
                    },
                ) {
                    val sel = selected as? Long
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (sel != null && s != null) {
                            val v = s.totals[sel] ?: 0L
                            Text(catMap[sel]?.name ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(Fmt.durationShort(v / s.days), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "dziennie · ${if (totalAll > 0) v * 100 / totalAll else 0}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val ph = phoneByCat[sel] ?: 0L
                            if (ph >= 60_000L && screens.isNotEmpty()) {
                                Text(
                                    "📱 ${Fmt.durationShort(ph / screens.size)} / dzień",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = androidx.compose.ui.graphics.Color(0xFF7D7AFF),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else if (avgSteps != null) {
                            Text(Fmt.steps(avgSteps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("kroków dziennie", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Text(
                                if (s == null) "…" else if (totalAll > 0) Fmt.durationShort(totalAll / s.days) else "—",
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            )
                            Text("śledzono dziennie", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (s != null && sorted.isNotEmpty()) {
            item { SectionTitle("Średni dzień · ${s.days} ${if (s.days == 1) "dzień" else "dni"} z danymi") }
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface).animateContentSize()
                ) {
                    sorted.forEach { (id, v) ->
                        val c = catMap[id]
                        val sel = selected == id
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .clickable { selected = if (sel) null else id }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            CategoryBadge(c, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c?.name ?: "—", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(4.dp))
                                ShareBar(v.toFloat() / totalAll, catColor(c))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.durationShort(v / s.days), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "razem ${Fmt.durationShort(v)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val ph = phoneByCat[id] ?: 0L
                                if (ph >= 60_000L && screens.isNotEmpty()) {
                                    Text(
                                        "📱 ${Fmt.durationShort(ph / screens.size)} / dzień",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF7D7AFF),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (screenOn) {
            item(key = "screen-$type-$startDay") { ScreenTrendSection(period) }
        }
        if (s != null && sorted.isEmpty()) {
            item {
                Text(
                    "Brak danych w tym okresie.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}
