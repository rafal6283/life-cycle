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

/** Typowy dzień: 24 słupki 100%, każdy pokazuje, co zwykle robisz o tej godzinie. */
@Composable
fun TypicalDayChart(hours: List<Map<Long, Long>>, categories: List<Category>, animKey: Any) {
    val grow = rememberGrow(animKey)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val order = categories.withIndex().associate { it.value.id to it.index }
    val colors = categories.associate { it.id to catColor(it) }
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val labelH = 18.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / 24
        val bw = slot * 0.82f
        hours.forEachIndexed { h, m ->
            val total = m.values.sum().toFloat()
            if (total <= 0f) return@forEachIndexed
            var y = chartH
            val x = h * slot + (slot - bw) / 2
            m.toList().sortedBy { order[it.first] ?: Int.MAX_VALUE }.forEach { (id, v) ->
                val hgt = chartH * (v / total) * grow
                drawRect(colors[id] ?: UnknownGray, Offset(x, y - hgt), Size(bw, hgt))
                y -= hgt
            }
        }
        for (h in listOf(0, 6, 12, 18)) {
            val t = measurer.measure(h.toString(), style)
            drawText(t, topLeft = Offset(h * slot, chartH + 3.dp.toPx()))
        }
    }
}

/** Pory snu: pionowe paski od zaśnięcia (góra) do pobudki (dół), oś 20:00–12:00. */
@Composable
fun SleepTimesChart(nights: List<Trends.Night>, color: Color, animKey: Any) {
    val grow = rememberGrow(animKey)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    fun minutesFrom20(ms: Long): Float {
        val t = Instant.ofEpochMilli(ms).atZone(Fmt.zone).toLocalTime()
        var m = t.hour * 60 + t.minute - 20 * 60
        if (m < 0) m += 24 * 60
        return m.toFloat().coerceIn(0f, 16 * 60f)
    }
    val dayF = DateTimeFormatter.ofPattern("d")
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 40.dp.toPx()
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
            val y2 = y1 + (chartH * (b - a) / span) * grow
            val x = left + i * slot + (slot - bw) / 2
            drawRoundRect(color, Offset(x, y1), Size(bw, y2 - y1), CornerRadius(bw / 2, bw / 2))
            if (n <= 10 || i % 5 == 0) {
                val t = measurer.measure(night.day.format(dayF), style)
                drawText(t, topLeft = Offset(left + i * slot + slot / 2 - t.size.width / 2f, chartH + 2.dp.toPx()))
            }
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
