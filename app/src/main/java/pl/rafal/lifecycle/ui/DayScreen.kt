package pl.rafal.lifecycle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.domain.Goals
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.domain.Segment
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

private sealed interface TimelineRow {
    data class Seg(val s: Segment) : TimelineRow
    data class Gap(val start: Long, val end: Long) : TimelineRow
}

@Composable
fun DayScreen() {
    var periodIdx by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    if (periodIdx != 0) {
        PeriodDonutScreen(periodIdx) { periodIdx = it }
        return
    }
    val repo = LifeApp.instance.repo
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val day = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now()
    val isToday = day == today
    val from = Fmt.dayStart(day)
    val to = Fmt.dayStart(day.plusDays(1))

    val entries by remember(from, to) { repo.entriesIn(from, to) }.collectAsState(initial = emptyList())
    val categories by repo.categories.collectAsState(initial = emptyList())
    val places by repo.places.collectAsState(initial = emptyList())
    val now by rememberNow()

    val catMap = remember(categories) { categories.associateBy { it.id } }
    val placeMap = remember(places) { places.associateBy { it.id } }
    val segments = remember(entries, now, from) { Timeline.resolve(entries, from, to, now) }
    val totals = remember(segments) { Timeline.totals(segments).toList().sortedByDescending { it.second } }
    val tracked = totals.sumOf { it.second }

    val rows = remember(segments) {
        val out = mutableListOf<TimelineRow>()
        var prevEnd: Long? = null
        for (s in segments) {
            val pe = prevEnd
            if (pe != null && s.start - pe >= 10 * 60_000L) out += TimelineRow.Gap(pe, s.start)
            out += TimelineRow.Seg(s)
            prevEnd = s.end
        }
        out
    }

    var clockMode by rememberSaveable { mutableStateOf(false) }
    var selSlice by remember(epochDay) { mutableStateOf<Any?>(null) }
    val daySlices = remember(segments, catMap) { mergeSlices(segments, catMap) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }
    var showMap by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val override by repo.override.collectAsState(initial = null)
    val goals by repo.goals.collectAsState(initial = emptyList())
    val weekRange = remember(day) { Goals.weekRange(day) }
    val weekEntries by remember(weekRange) { repo.entriesIn(weekRange.first, weekRange.second) }
        .collectAsState(initial = emptyList())
    val stepBuckets by remember(from, to) { LifeApp.instance.db.steps().observeRange(from, to) }
        .collectAsState(initial = emptyList())
    val stepsTotal = remember(stepBuckets) { pl.rafal.lifecycle.tracking.Steps.total(stepBuckets) }
    val stepHours = remember(stepBuckets, day) { pl.rafal.lifecycle.tracking.Steps.hoursOfDay(stepBuckets, day) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var stepGoalTick by remember { mutableStateOf(0) }
    val stepGoal = remember(stepGoalTick) { pl.rafal.lifecycle.util.Prefs(ctx).stepGoal }
    var editStepGoal by remember { mutableStateOf(false) }
    val goalProgress = remember(goals, weekEntries, now, day) {
        goals.map { Goals.progress(it, weekEntries, day, now) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().pointerInput(epochDay) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag > 160f) epochDay -= 1
                        else if (drag < -160f && epochDay < today.toEpochDay()) epochDay += 1
                        drag = 0f
                    },
                    onHorizontalDrag = { _, dx -> drag += dx },
                )
            },
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item { PeriodSwitcher(0) { periodIdx = it } }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { epochDay -= 1 }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni dzień")
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Fmt.dayTitle(day),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!isToday) {
                            Text(
                                "Wróć do dziś",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { epochDay = today.toEpochDay() },
                            )
                        }
                    }
                    IconButton(onClick = { epochDay += 1 }, enabled = !isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny dzień")
                    }
                    IconButton(onClick = { showMap = true }) {
                        Icon(Icons.Filled.Map, "Trasy dnia")
                    }
                }
            }

            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    if (clockMode) {
                        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                            ClockRing(
                                arcs = segments.map { RingArc(catColor(catMap[it.entry.categoryId]), it.start - from, it.end - from) },
                                dayLength = to - from,
                                now = if (isToday) now - from else null,
                                modifier = Modifier.fillMaxSize(),
                                stroke = 40.dp,
                                animKey = epochDay,
                            )
                            DonutCenter(stepsTotal, tracked, null)
                        }
                    } else {
                        AnimatedDonut(
                            slices = daySlices.map { it.slice },
                            selected = selSlice,
                            onSelect = { selSlice = it },
                            animKey = "$epochDay-${daySlices.size > 0}",
                            modifier = Modifier.size(330.dp),
                        ) {
                            DonutCenter(stepsTotal, tracked, daySlices.firstOrNull { it.slice.key == selSlice }?.let { d ->
                                val c = catMap[d.catId]
                                val names = d.places.mapNotNull { placeMap[it]?.name }.distinct()
                                listOf(
                                    names.firstOrNull() ?: c?.name ?: "—",
                                    Fmt.durationShort(d.end - d.start),
                                    "${Fmt.time(d.start)}–${Fmt.time(d.end)} · ${if (tracked > 0) (d.end - d.start) * 100 / tracked else 0}%",
                                )
                            })
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.FilterChip(!clockMode, { clockMode = false; selSlice = null }, { Text("Pierścień") })
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.FilterChip(clockMode, { clockMode = true }, { Text("Zegar 24 h") })
                }
            }

            if (isToday) {
                item {
                    OverrideCard(
                        entry = override,
                        cat = override?.let { catMap[it.categoryId] },
                        now = now,
                        onSwitch = { quick = true },
                        onStop = { scope.launch { repo.stopOverride() } },
                    )
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                StepsCard(stepsTotal, stepHours, stepGoal) { editStepGoal = true }
            }

            if (goalProgress.isNotEmpty()) {
                item { SectionTitle(if (isToday) "Cele" else "Cele (stan na ten dzień)") }
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        goalProgress.forEach { p -> GoalRow(p, catMap[p.goal.categoryId]) }
                    }
                }
            }

            if (totals.isEmpty()) {
                item {
                    Text(
                        if (isToday) "Jeszcze brak danych na dziś. Śledzenie zapisze pierwsze miejsce po kilku minutach pobytu."
                        else "Brak danych z tego dnia.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            } else {
                item { SectionTitle("Podsumowanie") }
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        totals.forEach { (catId, ms) ->
                            SummaryRow(catMap[catId], ms, tracked)
                        }
                    }
                }
                item { SectionTitle("Oś czasu") }
                items(rows) { row ->
                    when (row) {
                        is TimelineRow.Gap -> GapRow(row.start, row.end)
                        is TimelineRow.Seg -> SegmentRow(
                            row.s, catMap[row.s.entry.categoryId],
                            row.s.entry.placeId?.let { placeMap[it] },
                            ongoing = row.s.entry.endAt == null && isToday && now - row.s.end < 60_000,
                        ) { editing = row.s.entry }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) { Icon(Icons.Filled.Add, "Dodaj wpis", tint = Color.White) }
    }

    editing?.let { EntryEditorDialog(entry = it, defaultDay = day, onDismiss = { editing = null }) }
    if (showMap) DayMapDialog(day) { showMap = false }
    if (editStepGoal) StepGoalDialog { editStepGoal = false; stepGoalTick++ }
    if (quick) QuickSwitchDialog { quick = false }
    if (adding) EntryEditorDialog(entry = null, defaultDay = day, onDismiss = { adding = false })
}

@Composable
private fun SummaryRow(cat: Category?, ms: Long, total: Long) {
    val frac = if (total > 0) ms.toFloat() / total else 0f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(cat, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cat?.name ?: "—", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(Fmt.duration(ms), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShareBar(frac, catColor(cat), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(frac * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(34.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun SegmentRow(seg: Segment, cat: Category?, place: Place?, ongoing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
            Text(Fmt.time(seg.start), style = MaterialTheme.typography.labelLarge)
            Text(
                if (ongoing) "teraz" else Fmt.time(seg.end),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.width(5.dp).height(52.dp).clip(RoundedCornerShape(3.dp)).background(catColor(cat))
        )
        Spacer(Modifier.width(10.dp))
        CategoryBadge(cat, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place?.name ?: cat?.name ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                if (place != null) cat?.name else null,
                pl.rafal.lifecycle.domain.Commutes.info(seg.entry.id)?.let { inf ->
                    (if (inf.toWork) "do pracy" else "do domu")
                },
                seg.entry.distanceM?.let { Fmt.distance(it) },
                Source.label(seg.entry.source),
                seg.entry.note,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            Fmt.duration(seg.duration),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GapRow(start: Long, end: Long) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Spacer(Modifier.width(62.dp))
        Box(
            Modifier.width(5.dp).height(22.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Brak danych · ${Fmt.duration(end - start)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


private data class DaySlice(val slice: DonutSlice, val catId: Long, val start: Long, val end: Long, val places: List<Long>)

/** Łączy sąsiednie segmenty tej samej kategorii w jeden wycinek pierścienia. */
private fun mergeSlices(segments: List<Segment>, catMap: Map<Long, Category>): List<DaySlice> {
    data class Acc(val catId: Long, val start: Long, var end: Long, val places: MutableList<Long>)
    val out = mutableListOf<Acc>()
    for (s in segments) {
        val last = out.lastOrNull()
        if (last != null && last.catId == s.entry.categoryId && s.start - last.end < 5 * 60_000L) {
            last.end = s.end
            s.entry.placeId?.let { last.places += it }
        } else {
            out += Acc(s.entry.categoryId, s.start, s.end, listOfNotNull(s.entry.placeId).toMutableList())
        }
    }
    return out.map { a ->
        val c = catMap[a.catId]
        var icon = c?.icon
        var extra: String? = null
        if (c?.kind == pl.rafal.lifecycle.data.Kind.COMMUTE) {
            val seg = segments.firstOrNull { it.start >= a.start && it.entry.categoryId == a.catId }
            val info = seg?.let { pl.rafal.lifecycle.domain.Commutes.info(it.entry.id) }
            if (info != null) {
                icon = info.mainMotionCat?.let { catMap[it]?.icon } ?: "car"
                extra = if (info.toWork) "work" else "home"
            }
        }
        DaySlice(
            DonutSlice(a.start, catColor(c), (a.end - a.start).toFloat(), icon, Fmt.durationShort(a.end - a.start), extra),
            a.catId, a.start, a.end, a.places,
        )
    }
}

@Composable
private fun DonutCenter(steps: Long, tracked: Long, selected: List<String>?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (selected != null) {
            Text(selected[0], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(selected[1], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(selected[2], style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(Fmt.steps(steps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("kroków", style = MaterialTheme.typography.bodyMedium)
            if (tracked > 0) {
                Text(
                    "śledzono ${Fmt.durationShort(tracked)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
