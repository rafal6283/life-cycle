package pl.rafal.lifecycle.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.domain.Trends
import pl.rafal.lifecycle.util.Fmt
import java.time.Instant
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalDensity

/** Animowany postęp 0→1, restartowany przy zmianie klucza. */
@Composable
fun rememberGrow(key: Any): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    return a.value
}

@Composable
fun TrendCard(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp)
    ) { content() }
}

/**
 * Typowy dzień: 24 słupki 100%, każdy pokazuje, co zwykle robisz o tej godzinie.
 * Słupki rosną kaskadą; dotknięcie lub przeciągnięcie pokazuje, co dominuje o danej godzinie.
 */
@Composable
fun TypicalDayChart(hours: List<Map<Long, Long>>, categories: List<Category>, animKey: Any) {
    val grow = rememberGrow(animKey)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val order = categories.withIndex().associate { it.value.id to it.index }
    val colors = categories.associate { it.id to catColor(it) }
    val names = categories.associate { it.id to it.name }
    val sel = rememberSelection(animKey)
    Canvas(
        Modifier.fillMaxWidth().height(170.dp)
            .chartTouch(sel, 24) { o, w -> (o.x / (w / 24)).toInt().coerceIn(0, 23) }
    ) {
        val labelH = 18.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / 24
        val bw = slot * 0.82f
        hours.forEachIndexed { h, m ->
            val total = m.values.sum().toFloat()
            if (total <= 0f) return@forEachIndexed
            var y = chartH
            val x = h * slot + (slot - bw) / 2
            val g = stagger(grow, h, 24)
            val a = barAlpha(h, sel, 1f)
            m.toList().sortedBy { order[it.first] ?: Int.MAX_VALUE }.forEach { (id, v) ->
                val hgt = chartH * (v / total) * g
                drawRect((colors[id] ?: UnknownGray).copy(alpha = a), Offset(x, y - hgt), Size(bw, hgt))
                y -= hgt
            }
        }
        for (h in listOf(0, 6, 12, 18)) {
            val t = measurer.measure(h.toString(), style)
            drawText(t, topLeft = Offset(h * slot, chartH + 3.dp.toPx()))
        }
        val s = sel.last
        if (s in hours.indices) {
            val m = hours[s]
            val total = m.values.sum()
            val text = if (total <= 0L) "$s:00 · brak danych" else {
                val top = m.entries.sortedByDescending { it.value }.take(2)
                "$s:00 · " + top.joinToString(", ") { "${names[it.key] ?: "inne"} ${it.value * 100 / total}%" }
            }
            tooltip(measurer, text, s * slot + slot / 2, 0f + 34.dp.toPx(), tipStyle, tipBg, sel.alpha.value)
        }
    }
}

/**
 * Pory snu: pionowe paski od zaśnięcia (góra) do pobudki (dół), oś 20:00–12:00.
 * Paski "spływają" kaskadą; dotknięcie nocy pokazuje godziny i długość snu.
 */
@Composable
fun SleepTimesChart(nights: List<Trends.Night>, color: Color, animKey: Any) {
    val grow = rememberGrow(animKey)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val sel = rememberSelection(animKey)
    val density = LocalDensity.current
    val leftPx = with(density) { 40.dp.toPx() }
    fun minutesFrom20(ms: Long): Float {
        val t = Instant.ofEpochMilli(ms).atZone(Fmt.zone).toLocalTime()
        var m = t.hour * 60 + t.minute - 20 * 60
        if (m < 0) m += 24 * 60
        return m.toFloat().coerceIn(0f, 16 * 60f)
    }
    val dayF = DateTimeFormatter.ofPattern("d")
    val tipF = DateTimeFormatter.ofPattern("EEE d.MM", Fmt.PL)
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    Canvas(
        Modifier.fillMaxWidth().height(200.dp)
            .chartTouch(sel, nights.size) { o, w ->
                if (nights.isEmpty() || o.x < leftPx) -1 else ((o.x - leftPx) / ((w - leftPx) / nights.size)).toInt().coerceIn(0, nights.size - 1)
            }
    ) {
        val left = leftPx
        val bottomH = 16.dp.toPx()
        val chartH = size.height - bottomH
        val span = 16 * 60f
        for ((label, m) in listOf("20:00" to 0f, "00:00" to 240f, "04:00" to 480f, "08:00" to 720f, "12:00" to 960f)) {
            val y = chartH * m / span
            drawLine(grid, Offset(left, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = measurer.measure(label, style)
            drawText(t, topLeft = Offset(0f, (y - t.size.height / 2f).coerceIn(0f, chartH - t.size.height)))
        }
        val n = nights.size.coerceAtLeast(1)
        val slot = (size.width - left) / n
        val bw = (slot * 0.6f).coerceAtMost(28.dp.toPx())
        nights.forEachIndexed { i, night ->
            val a = minutesFrom20(night.start)
            val b = minutesFrom20(night.end).coerceAtLeast(a + 10f)
            val y1 = chartH * a / span
            val y2 = y1 + (chartH * (b - a) / span) * stagger(grow, i, n)
            val x = left + i * slot + (slot - bw) / 2
            drawRoundRect(color.copy(alpha = barAlpha(i, sel, 1f)), Offset(x, y1), Size(bw, y2 - y1), CornerRadius(bw / 2, bw / 2))
            if (n <= 10 || i % 5 == 0) {
                val t = measurer.measure(night.day.format(dayF), style)
                drawText(t, topLeft = Offset(left + i * slot + slot / 2 - t.size.width / 2f, chartH + 2.dp.toPx()))
            }
        }
        val s = sel.last
        if (s in nights.indices) {
            val night = nights[s]
            val st = Instant.ofEpochMilli(night.start).atZone(Fmt.zone)
            val en = Instant.ofEpochMilli(night.end).atZone(Fmt.zone)
            val y1 = chartH * minutesFrom20(night.start) / span
            tooltip(
                measurer,
                "${night.day.format(tipF)} · ${st.format(hm)}–${en.format(hm)} · ${Fmt.durationShort(night.end - night.start)}",
                left + s * slot + slot / 2, y1, tipStyle, tipBg, sel.alpha.value,
            )
        }
    }
    if (nights.isNotEmpty()) {
        fun avgClock(list: List<Long>, shift: Boolean): String {
            val mins = list.map {
                val t = Instant.ofEpochMilli(it).atZone(Fmt.zone).toLocalTime()
                val m = t.hour * 60 + t.minute
                if (shift && m < 12 * 60) m + 1440 else m
            }.average().toInt() % 1440
            return "%02d:%02d".format(mins / 60, mins % 60)
        }
        val avgDur = nights.sumOf { it.end - it.start } / nights.size
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            SleepStat("Zasypiasz", avgClock(nights.map { it.start }, true), Modifier.weight(1f))
            SleepStat("Wstajesz", avgClock(nights.map { it.end }, false), Modifier.weight(1f))
            SleepStat("Śpisz", Fmt.durationShort(avgDur), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SleepStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecordsCard(records: List<Trends.Record>) {
    val f = DateTimeFormatter.ofPattern("EEE, d MMM", Fmt.PL)
    TrendCard {
        records.forEachIndexed { i, r ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(
                    Category(name = r.title, color = r.color ?: 0xFF8E8E93.toInt(), icon = r.iconKey ?: "unknown"),
                    32.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        r.day.format(f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(r.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
