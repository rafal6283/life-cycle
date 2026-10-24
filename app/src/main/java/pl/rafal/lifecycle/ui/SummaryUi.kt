package pl.rafal.lifecycle.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.domain.DayAgg
import pl.rafal.lifecycle.domain.HealthTrends
import pl.rafal.lifecycle.domain.Metric
import pl.rafal.lifecycle.domain.MetricTrend
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.PeriodSummary
import pl.rafal.lifecycle.domain.PeriodType
import pl.rafal.lifecycle.domain.SumRow
import pl.rafal.lifecycle.domain.SumSection
import pl.rafal.lifecycle.domain.Summaries
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)

// ---------------------------------------------------------------------------
// Ekran podsumowania (tydzień / miesiąc / rok)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(key: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember(key) { key?.let { Summaries.parseKey(it) } ?: Period.current(PeriodType.WEEK) }
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var startDay by rememberSaveable(type) {
        mutableStateOf(if (type == initial.type) initial.start.toEpochDay() else Period.current(type).start.toEpochDay())
    }
    val period = Period(type, LocalDate.ofEpochDay(startDay))
    val canNext = period.end <= LocalDate.now()
    val summary by produceState<PeriodSummary?>(null, type, startDay) {
        value = null
        value = Summaries.build(ctx, period)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ScreenHeader("Podsumowanie", onBack = onBack) {
                val s = summary
                IconButton(onClick = {
                    if (s == null) return@IconButton
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.asText())
                    ctx.startActivity(Intent.createChooser(send, "Udostępnij podsumowanie"))
                }, enabled = s != null) { Icon(Icons.Filled.Share, "Udostępnij") }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                PeriodType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t, onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, PeriodType.entries.size),
                    ) { Text(t.label) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { startDay = period.prev().start.toEpochDay() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni")
                }
                Text(
                    period.label(), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                IconButton(onClick = { startDay = period.next().start.toEpochDay() }, enabled = canNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny")
                }
            }
        }
        val s = summary
        if (s == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            item {
                Text(
                    s.headline,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
            if (s.sections.isEmpty()) {
                item {
                    Text(
                        "Brak danych w tym okresie.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            itemsIndexed(s.sections, key = { _, sec -> sec.title + startDay }) { i, sec ->
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(60L * i)
                    shown = true
                }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 4 }) {
                    SectionCard(sec)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(sec: SumSection) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(sec.color)), contentAlignment = Alignment.Center) {
                Icon(CatIcons.get(sec.icon), null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(sec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 4.dp)
        ) {
            sec.rows.forEach { SummaryRowView(it) }
            sec.notes.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
                    if (sec.rows.isEmpty()) {
                        Icon(Icons.Filled.Lightbulb, null, tint = Color(0xFFFFCC00), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(n, style = MaterialTheme.typography.bodySmall, color = if (sec.rows.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SummaryRowView(r: SumRow) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (r.icon != null) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(r.color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CatIcons.get(r.icon), null, tint = if (r.color != null) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(r.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            r.delta?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (r.good) {
                        true -> Green
                        false -> Red
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Karta-skrót na górze Trendów. */
@Composable
fun SummaryShortcut(period: Period, onOpen: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Summarize, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Podsumowanie: ${period.label()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Czas, sen, ruch, miejsca, zdrowie i zależności w jednym raporcie",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

// ---------------------------------------------------------------------------
// Zdrowie w Trendach
// ---------------------------------------------------------------------------

@Composable
fun HealthTrendsCard(trends: List<MetricTrend>, labels: List<String>, animKey: Any, onOpen: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(vertical = 4.dp)
    ) {
        trends.forEach { t -> HealthTrendRow(t, animKey) }
    }
}

@Composable
private fun HealthTrendRow(t: MetricTrend, animKey: Any) {
    val m = t.m
    val color = Color(m.color)
    val sum = m.agg == DayAgg.SUM
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(CatIcons.get(m.icon), null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (sum) "średnio dziennie · ${t.days} dni" else "zakres ${m.format(t.min)}–${m.format(t.max)} · ${t.days} dni",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MiniTrend(t.buckets, color, sum, animKey, Modifier.width(72.dp).height(28.dp))
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(86.dp)) {
            Text(
                (if (m == Metric.BP) "${m.format(t.avg)}/${m.format(t.v2 ?: 0.0)}" else m.format(t.avg)) +
                    if (m.unit.isNotEmpty() && m.unit.length <= 4) " ${m.unit}" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val prev = t.prevAvg
            if (prev != null) {
                val d = t.avg - prev
                val hb = HealthTrends.higherIsBetter(m)
                val col = when {
                    hb == null || kotlin.math.abs(d) < Math.pow(10.0, -m.decimals.toDouble()) -> MaterialTheme.colorScheme.onSurfaceVariant
                    (d > 0) == hb -> Green
                    else -> Red
                }
                Text(
                    (if (d >= 0) "▲ " else "▼ ") + m.format(kotlin.math.abs(d)),
                    style = MaterialTheme.typography.labelSmall,
                    color = col,
                )
            }
        }
    }
}

@Composable
private fun MiniTrend(values: List<Double?>, color: Color, bars: Boolean, animKey: Any, modifier: Modifier) {
    val t = entrance(animKey)
    Canvas(modifier) {
        val present = values.filterNotNull()
        if (present.isEmpty()) return@Canvas
        val lo = if (bars) 0.0 else present.min()
        val hi = present.max()
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val slot = size.width / values.size
        fun y(v: Double) = size.height - 2f - ((v - lo) / span * (size.height - 4f)).toFloat()
        if (bars) {
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val h = (size.height * (v / hi)).toFloat() * stagger(t, i, values.size)
                drawRect(color.copy(alpha = 0.8f), Offset(i * slot + slot * 0.15f, size.height - h), Size(slot * 0.7f, h))
            }
        } else {
            val path = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val x = i * slot + slot / 2
                if (!started) { path.moveTo(x, y(v)); started = true } else path.lineTo(x, y(v))
            }
            drawPath(path, color, style = Stroke(1.8.dp.toPx()), alpha = t)
            values.withIndex().lastOrNull { it.value != null }?.let { (i, v) ->
                drawCircle(color, 2.5.dp.toPx() * t, Offset(i * slot + slot / 2, y(v!!)))
            }
        }
    }
}

@Composable
fun HealthInsightsCard(lines: List<String>) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp)
    ) {
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Lightbulb, null, tint = Color(0xFFFFCC00), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(l, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Zależności to porównanie średnich, nie dowód przyczyny. Im więcej dni z danymi, tym pewniejsze.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
