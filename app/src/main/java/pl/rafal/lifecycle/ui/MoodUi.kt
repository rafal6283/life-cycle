package pl.rafal.lifecycle.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.MoodDay
import pl.rafal.lifecycle.domain.Mood
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.PeriodType
import pl.rafal.lifecycle.domain.SleepRegularity
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate

/** Ocena dnia jednym dotknięciem (😞–😄) i notatka. */
@Composable
fun MoodCard(day: LocalDate) {
    val ctx = LocalContext.current
    if (!remember { Prefs(ctx).moodOn }) return
    val db = LifeApp.instance.db
    val scope = rememberCoroutineScope()
    val list by remember(day) { db.mood().observe(day.toEpochDay(), day.toEpochDay() + 1) }.collectAsState(initial = emptyList())
    val cur = list.firstOrNull()
    var noteDialog by remember { mutableStateOf(false) }
    val isToday = day == LocalDate.now()
    if (day > LocalDate.now()) return

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (cur == null) (if (isToday) "Jak mija dzień?" else "Jak minął ten dzień?")
                else "Nastrój: ${Mood.labels[cur.value - 1].lowercase()}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (cur != null) {
                TextButton(onClick = { noteDialog = true }) { Text(if (cur.note.isNullOrBlank()) "Dodaj notatkę" else "Notatka") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Mood.faces.forEachIndexed { i, f ->
                val v = i + 1
                val sel = cur?.value == v
                val scale by animateFloatAsState(if (sel) 1.25f else if (cur == null) 1f else 0.85f, label = "mood")
                Box(
                    Modifier.size(48.dp).scale(scale).clip(CircleShape)
                        .background(if (sel) Color(Mood.colors[i]).copy(alpha = 0.25f) else Color.Transparent)
                        .then(if (sel) Modifier.border(2.dp, Color(Mood.colors[i]), CircleShape) else Modifier)
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (sel) db.mood().delete(day.toEpochDay())
                                    else db.mood().upsert(MoodDay(day.toEpochDay(), v, cur?.note))
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(f, fontSize = 26.sp) }
            }
        }
        if (!cur?.note.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(cur!!.note!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (noteDialog && cur != null) {
        var text by remember { mutableStateOf(cur.note ?: "") }
        AlertDialog(
            onDismissRequest = { noteDialog = false },
            title = { Text("Notatka do dnia") },
            text = {
                OutlinedTextField(
                    text, { text = it }, placeholder = { Text("Co wpłynęło na nastrój?") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    noteDialog = false
                    scope.launch { withContext(Dispatchers.IO) { db.mood().upsert(cur.copy(note = text.trim().ifBlank { null })) } }
                }) { Text("Zapisz") }
            },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Anuluj") } },
        )
    }
}

/** Trendy: średni nastrój, wykres i zależności. */
@Composable
fun MoodTrendSection(period: Period) {
    val ctx = LocalContext.current
    if (!remember { Prefs(ctx).moodOn }) return
    val app = LifeApp.instance
    val prev = period.prev()
    val moods by remember(period) { app.db.mood().observe(prev.start.toEpochDay(), period.end.toEpochDay()) }
        .collectAsState(initial = emptyList())
    val cur = moods.filter { it.day >= period.start.toEpochDay() }
    val pr = moods.filter { it.day < period.start.toEpochDay() }
    val insights by produceState(emptyList<String>(), period, moods.size) {
        value = withContext(Dispatchers.Default) {
            // Zależności liczone z dłuższego okresu, by było z czego wnioskować.
            val start = minOf(period.start, LocalDate.now().minusDays(90))
            val end = minOf(period.end, LocalDate.now().plusDays(1))
            val from = Fmt.dayStart(start)
            val to = Fmt.dayStart(end)
            val m = app.db.mood().range(start.toEpochDay(), end.toEpochDay())
            if (m.size < 10) return@withContext emptyList()
            Mood.insights(
                m, app.repo.getRange(from, to), app.db.categories().getAll().associateBy { it.id },
                pl.rafal.lifecycle.tracking.Steps.perDay(app.db.steps().getRange(from, to)),
                app.db.screen().range(start.toEpochDay(), end.toEpochDay()),
                app.db.weather().range(start.toEpochDay(), end.toEpochDay()),
                System.currentTimeMillis(),
            )
        }
    }
    if (cur.isEmpty()) return
    val avg = cur.map { it.value }.average()
    val prevAvg = pr.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
    SectionTitle("Nastrój")
    TrendCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Mood.face(avg), fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(String.format(Fmt.PL, "%.1f / 5", avg), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("średnio · ${cur.size} ocenionych dni", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (prevAvg != null) {
                val d = avg - prevAvg
                Text(
                    (if (d >= 0) "▲ " else "▼ ") + String.format(Fmt.PL, "%.1f", kotlin.math.abs(d)),
                    color = if (d >= 0) Color(0xFF34C759) else Color(0xFFFF453A),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MoodBars(cur, period)
        if (insights.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Co idzie w parze z lepszym nastrojem", style = MaterialTheme.typography.labelLarge)
            insights.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        } else if (moods.size < 10) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Oceniaj dni przez 2 tygodnie, a Doba pokaże, co poprawia Ci nastrój (sen, ruch, telefon, pogoda…).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MoodBars(list: List<MoodDay>, period: Period) {
    val values: List<Double?> = if (period.type == PeriodType.YEAR) {
        (1..12).map { m -> list.filter { LocalDate.ofEpochDay(it.day).monthValue == m }.takeIf { it.isNotEmpty() }?.map { it.value }?.average() }
    } else {
        val map = list.associateBy { it.day }
        generateSequence(period.start) { it.plusDays(1) }.takeWhile { it < period.end }.map { map[it.toEpochDay()]?.value?.toDouble() }.toList()
    }
    val t = rememberGrow(period)
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val n = values.size
        val slot = size.width / n
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val h = size.height * (v / 5.0).toFloat() * t
            val c = Color(Mood.colors[(v.coerceIn(1.0, 5.0) - 1).toInt().coerceIn(0, 4)])
            drawRoundRect(
                c, Offset(i * slot + slot * 0.18f, size.height - h), Size(slot * 0.64f, h),
                androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }
    }
}

/** Regularność snu: stałe pory zasypiania i pobudki, społeczny jet lag. */
@Composable
fun SleepRegularityCard(r: SleepRegularity) {
    TrendCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                val color = when {
                    r.score >= 75 -> Color(0xFF34C759)
                    r.score >= 50 -> Color(0xFFFFCC00)
                    else -> Color(0xFFFF9F0A)
                }
                val t = rememberGrow(r)
                Canvas(Modifier.size(56.dp)) {
                    drawArc(color.copy(alpha = 0.2f), -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(7.dp.toPx()))
                    drawArc(
                        color, -90f, 360f * r.score / 100f * t, false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
                Text("${r.score}", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        r.score >= 75 -> "Regularny rytm snu"
                        r.score >= 50 -> "Dość regularny sen"
                        else -> "Nieregularne pory snu"
                    },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Zasypianie ±${r.bedSdMin} min · pobudka ±${r.wakeSdMin} min (${r.nights} nocy)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r.socialJetlagMs?.let { j ->
                    val a = kotlin.math.abs(j)
                    Text(
                        if (a < 30 * 60_000L) "Weekendy bez przesunięcia snu 👍"
                        else "Społeczny jet lag: w weekendy śpisz ${Fmt.durationShort(a)} ${if (j > 0) "później" else "wcześniej"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a >= 60 * 60_000L) Color(0xFFFF9F0A) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
