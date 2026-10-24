package pl.rafal.lifecycle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.ExtEvent
import pl.rafal.lifecycle.data.HealthSample
import pl.rafal.lifecycle.domain.ActSpan
import pl.rafal.lifecycle.domain.DayAgg
import pl.rafal.lifecycle.domain.DayDetail
import pl.rafal.lifecycle.domain.ExchangeImport
import pl.rafal.lifecycle.domain.HealthDay
import pl.rafal.lifecycle.domain.Metric
import pl.rafal.lifecycle.domain.Pt
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate
import kotlin.math.abs

private const val HOUR = 3_600_000L

@Composable
fun DayNav(day: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(day.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni dzień")
        }
        Text(
            Fmt.dayTitle(day),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange(day.plusDays(1)) }, enabled = day < LocalDate.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny dzień")
        }
    }
}

/** Szczegóły jednego dnia: wykres 0–24 h, pasek aktywności, statystyki, strefy, średnie wg aktywności, odczyty. */
@Composable
fun MetricDayView(m: Metric, day: LocalDate, onDay: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val from = Fmt.dayStart(day)
    val to = Fmt.dayStart(day.plusDays(1))
    val detail by produceState<DayDetail?>(null, m, from) {
        value = null
        value = HealthDay.load(ctx, m, from, to)
    }
    val color = Color(m.color)

    DayNav(day, onDay)
    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    TrendCard {
        if (d.points.isEmpty()) {
            Text("Brak pomiarów tego dnia.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DayChart(m, d, from, to, color)
        }
        Spacer(Modifier.height(6.dp))
        ActivityStrip(d.spans, from, to)
        Text(
            if (d.fromHc && m == Metric.HR) "${d.points.size} odczytów z Health Connect · dotknij lub przeciągnij palcem"
            else "dotknij lub przeciągnij palcem, by zobaczyć wartość i aktywność",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (d.points.isEmpty()) return

    val vals = d.points.map { it.value }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        if (d.hourly) {
            StatBoxDay("razem", m.format(vals.sum()), Modifier.weight(1f))
            val peak = d.points.maxBy { it.value }
            StatBoxDay("najwięcej", "${m.format(peak.value)} o ${Fmt.time(peak.time)}", Modifier.weight(1.4f))
            StatBoxDay("aktywnych godzin", "${d.points.size}", Modifier.weight(1f))
        } else {
            StatBoxDay("średnia", m.format(vals.average()), Modifier.weight(1f))
            val mn = d.points.minBy { it.value }
            val mx = d.points.maxBy { it.value }
            StatBoxDay("min", "${m.format(mn.value)}\n${Fmt.time(mn.time)}", Modifier.weight(1f))
            StatBoxDay("max", "${m.format(mx.value)}\n${Fmt.time(mx.time)}", Modifier.weight(1f))
            StatBoxDay("odczyty", "${d.points.size}", Modifier.weight(1f))
        }
    }

    // Sen vs czuwanie
    if (!d.hourly) {
        val sleep = d.points.filter { HealthDay.isSleep(HealthDay.activityAt(d.spans, it.time)) }
        val awake = d.points.filter { !HealthDay.isSleep(HealthDay.activityAt(d.spans, it.time)) }
        if (sleep.isNotEmpty() && awake.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                StatBoxDay("w czasie snu", m.format(sleep.map { it.value }.average()) + " " + m.unit, Modifier.weight(1f))
                StatBoxDay("w ciągu dnia", m.format(awake.map { it.value }.average()) + " " + m.unit, Modifier.weight(1f))
            }
        }
    }

    if (m == Metric.HR) {
        SectionTitle("Strefy tętna")
        TrendCard { ZonesBar(HealthDay.zones(d.points)) }
    }

    val byAct = remember(d) { if (d.hourly) emptyList() else HealthDay.byActivity(d.points, d.spans) }
    val byActSum = remember(d) {
        if (!d.hourly) emptyList() else {
            // Sumy godzinowe przypisane aktywności, w której upłynęła większość godziny.
            d.points.groupBy { p ->
                d.spans.filter { it.end > p.time && it.start < p.time + HOUR }
                    .maxByOrNull { minOf(it.end, p.time + HOUR) - maxOf(it.start, p.time) }?.cat
            }.map { (c, l) -> Triple(c, l.sumOf { it.value }, l.size) }.sortedByDescending { it.second }
        }
    }
    val rows = if (d.hourly) byActSum else byAct
    if (rows.isNotEmpty()) {
        SectionTitle(if (d.hourly) "Wg aktywności (suma)" else "Wg aktywności (średnia)")
        Column(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            val maxV = rows.maxOf { it.second }.coerceAtLeast(1e-6)
            rows.forEach { (c, v, n) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(c, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c?.name ?: "Bez aktywności", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(3.dp))
                        ShareBar((v / maxV).toFloat(), catColor(c))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(m.format(v) + " " + m.unit, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        if (!d.hourly) {
                            Text("$n odczytów", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // Lista odczytów (dla rzadkich pomiarów i sum godzinowych)
    if (m != Metric.HR || d.points.size <= 60) {
        SectionTitle(if (d.hourly) "Godzina po godzinie" else "Odczyty")
        Column(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            d.points.sortedByDescending { it.time }.take(48).forEach { p ->
                val act = HealthDay.activityAt(d.spans, p.time)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (d.hourly) "${Fmt.time(p.time)}–${Fmt.time(p.time + HOUR)}" else Fmt.time(p.time),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(if (d.hourly) 100.dp else 56.dp),
                    )
                    Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(act)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        act?.name ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        (if (m == Metric.BP) "${m.format(p.value)}/${m.format(p.v2 ?: 0.0)}" else m.format(p.value)) + " " + m.unit,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBoxDay(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Oś 0–24 h: linia ciągła (tętno), punkty (rzadkie pomiary) albo słupki godzinowe (sumy). */
@Composable
private fun DayChart(m: Metric, d: DayDetail, from: Long, to: Long, color: Color) {
    val t = entrance(d)
    val sel = rememberSelection(d)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.surface)
    val tipBg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pts = d.points
    val leftPx = with(LocalDensity.current) { 34.dp.toPx() }
    val span = (to - from).toFloat()
    // Wybór najbliższego punktu w czasie
    fun indexAt(x: Float, w: Float): Int {
        if (x < leftPx) return -1
        val time = from + ((x - leftPx) / (w - leftPx) * span).toLong()
        if (d.hourly) return pts.indexOfFirst { time >= it.time && time < it.time + HOUR }
        var best = -1
        var bestD = Long.MAX_VALUE
        pts.forEachIndexed { i, p ->
            val dd = abs(p.time - time)
            if (dd < bestD) { bestD = dd; best = i }
        }
        return if (bestD <= 45 * 60_000L || pts.size < 30) best else -1
    }
    Canvas(Modifier.fillMaxWidth().height(230.dp).chartTouch(sel, pts.size) { o, w -> indexAt(o.x, w) }) {
        val bottom = 16.dp.toPx()
        val top = 26.dp.toPx()
        val chartH = size.height - bottom - top
        val w = size.width - leftPx
        fun x(time: Long) = leftPx + w * ((time - from) / span)
        var lo = if (d.hourly) 0.0 else pts.minOf { minOf(it.value, it.v2 ?: it.value) }
        var hi = pts.maxOf { it.value }
        if (hi - lo < 1e-6) { hi += 1; if (!d.hourly) lo -= 1 }
        if (!d.hourly) { val pad = (hi - lo) * 0.12; lo -= pad; hi += pad }
        fun y(v: Double) = top + chartH - ((v - lo) / (hi - lo) * chartH).toFloat()

        // Tło: pasy snu
        d.spans.filter { HealthDay.isSleep(it.cat) }.forEach { s ->
            drawRect(catColor(s.cat).copy(alpha = 0.08f), Offset(x(s.start), top), Size(x(s.end) - x(s.start), chartH))
        }
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            val yy = y(v)
            drawLine(grid.copy(alpha = 0.5f), Offset(leftPx, yy), Offset(size.width, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val tl = measurer.measure(m.format(v), style)
            drawText(tl, topLeft = Offset(0f, yy - tl.size.height / 2f))
        }
        for (h in listOf(0, 6, 12, 18, 24)) {
            val xx = leftPx + w * h / 24f
            drawLine(grid.copy(alpha = 0.3f), Offset(xx, top), Offset(xx, top + chartH), 1f)
            val tl = measurer.measure(if (h == 24) "" else "$h:00", style)
            drawText(tl, topLeft = Offset((xx - tl.size.width / 2f).coerceAtLeast(leftPx), top + chartH + 2.dp.toPx()))
        }
        val reveal = x(from + (span * t).toLong())
        when {
            d.hourly -> {
                val bw = w / 24f * 0.7f
                pts.forEachIndexed { i, p ->
                    val h = (y(lo) - y(p.value)) * stagger(t, i, pts.size)
                    drawRoundRect(
                        color.copy(alpha = barAlpha(i, sel, 0.85f)),
                        Offset(x(p.time) + w / 24f * 0.15f, y(lo) - h), Size(bw, h), CornerRadius(3f, 3f),
                    )
                }
            }
            pts.size >= 40 -> {
                // Linia ciągła z przerwami tam, gdzie brak danych > 15 min
                val path = Path()
                var prev: Pt? = null
                for (p in pts) {
                    val xx = x(p.time)
                    if (xx > reveal) break
                    if (prev == null || p.time - prev.time > 15 * 60_000L) path.moveTo(xx, y(p.value)) else path.lineTo(xx, y(p.value))
                    prev = p
                }
                drawPath(path, color, style = Stroke(1.6.dp.toPx()))
            }
            else -> {
                if (m == Metric.BP) {
                    pts.forEach { p ->
                        if (x(p.time) > reveal) return@forEach
                        val v2 = p.v2 ?: return@forEach
                        drawLine(color.copy(alpha = 0.5f), Offset(x(p.time), y(p.value)), Offset(x(p.time), y(v2)), 3.dp.toPx())
                        drawCircle(color.copy(alpha = 0.6f), 4.dp.toPx(), Offset(x(p.time), y(v2)))
                    }
                } else if (pts.size > 1) {
                    val path = Path()
                    pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.time), y(p.value)) else path.lineTo(x(p.time), y(p.value)) }
                    drawPath(path, color.copy(alpha = 0.35f * t), style = Stroke(1.5.dp.toPx()))
                }
                pts.forEachIndexed { i, p ->
                    if (x(p.time) > reveal) return@forEachIndexed
                    val r = if (i == sel.last && sel.alpha.value > 0f) 6.dp else 4.dp
                    drawCircle(color, r.toPx(), Offset(x(p.time), y(p.value)))
                }
            }
        }
        val i = sel.last
        if (i in pts.indices) {
            val p = pts[i]
            val act = HealthDay.activityAt(d.spans, p.time)
            val time = if (d.hourly) "${Fmt.time(p.time)}–${Fmt.time(p.time + HOUR)}" else Fmt.time(p.time)
            val value = if (m == Metric.BP) "${m.format(p.value)}/${m.format(p.v2 ?: 0.0)}" else m.format(p.value)
            val text = "$time · $value ${m.unit}" + (act?.let { " · ${it.name}" } ?: "")
            val xx = if (d.hourly) x(p.time) + w / 48f else x(p.time)
            if (pts.size >= 40 && !d.hourly) {
                drawCircle(color, 5.dp.toPx() * sel.alpha.value, Offset(xx, y(p.value)))
            }
            tooltip(measurer, text, xx, y(p.value), tipStyle, tipBg, sel.alpha.value, grid, top + chartH)
        }
    }
}

/** Kolorowy pasek aktywności z osi czasu Doby pod wykresem. */
@Composable
fun ActivityStrip(spans: List<ActSpan>, from: Long, to: Long) {
    val leftPx = with(LocalDensity.current) { 34.dp.toPx() }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width - leftPx
        val span = (to - from).toFloat()
        drawRoundRect(track, Offset(leftPx, 0f), Size(w, size.height), CornerRadius(4f, 4f))
        spans.forEach { s ->
            val a = leftPx + w * ((s.start - from) / span)
            val b = leftPx + w * ((s.end - from) / span)
            drawRect(catColor(s.cat), Offset(a, 0f), Size((b - a).coerceAtLeast(1f), size.height))
        }
    }
    val legend = spans.groupBy { it.cat?.id }.map { (_, l) -> l.first().cat to l.sumOf { it.end - it.start } }
        .sortedByDescending { it.second }.take(5)
    if (legend.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 4.dp)) {
            legend.forEach { (c, _) ->
                Box(Modifier.padding(top = 3.dp).size(8.dp).clip(CircleShape).background(catColor(c)))
                Spacer(Modifier.width(3.dp))
                Text(c?.name ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun ZonesBar(zones: List<Pair<pl.rafal.lifecycle.domain.Zone, Long>>) {
    val total = zones.sumOf { it.second }.coerceAtLeast(1L)
    val t = entrance(zones)
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        var x = 0f
        zones.forEach { (z, v) ->
            val w = size.width * v / total * t
            drawRect(Color(z.color), Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
    Spacer(Modifier.height(8.dp))
    zones.filter { it.second > 0 }.forEach { (z, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(z.color)))
            Spacer(Modifier.width(8.dp))
            Text("${z.label} bpm", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(Fmt.duration(v), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(
                "  ${v * 100 / total}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Profil doby z wielu dni
// ---------------------------------------------------------------------------

@Composable
fun HourProfileCard(m: Metric, samples: List<HealthSample>, days: Int, from: Long, to: Long) {
    val ctx = LocalContext.current
    val sums by produceState<List<HealthSample>?>(null, m, from, days) {
        value = if (m.agg == DayAgg.SUM && days <= 90) HealthDay.hourlySums(ctx, m, from, to) else emptyList()
    }
    val data = if (m.agg == DayAgg.SUM) sums.orEmpty() else samples
    val stats = remember(data, days) { HealthDay.hourProfile(m, data, days) }
    if (stats.isEmpty()) return
    SectionTitle("Rozkład w ciągu doby · średnio z $days dni")
    TrendCard {
        HourProfileChart(m, stats, Color(m.color))
        val peak = stats.maxBy { it.avg }
        val low = stats.minBy { it.avg }
        Text(
            if (m.agg == DayAgg.SUM) "Najwięcej zwykle o ${peak.hour}:00 (${m.format(peak.avg)} ${m.unit})."
            else "Najwyżej zwykle ok. ${peak.hour}:00 (${m.format(peak.avg)}), najniżej ok. ${low.hour}:00 (${m.format(low.avg)}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HourProfileChart(m: Metric, stats: List<HealthDay.HourStat>, color: Color) {
    val t = entrance(stats)
    val sel = rememberSelection(stats)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.surface)
    val tipBg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val byHour = stats.associateBy { it.hour }
    val leftPx = with(LocalDensity.current) { 34.dp.toPx() }
    val sum = m.agg == DayAgg.SUM
    Canvas(Modifier.fillMaxWidth().height(170.dp).chartTouch(sel, 24) { o, w ->
        if (o.x < leftPx) -1 else ((o.x - leftPx) / ((w - leftPx) / 24f)).toInt()
    }) {
        val bottom = 16.dp.toPx()
        val top = 24.dp.toPx()
        val chartH = size.height - bottom - top
        val w = size.width - leftPx
        val slot = w / 24f
        var lo = if (sum) 0.0 else stats.minOf { it.lo }
        var hi = stats.maxOf { if (sum) it.avg else it.hi }
        if (hi - lo < 1e-6) { hi += 1; if (!sum) lo -= 1 }
        fun y(v: Double) = top + chartH - ((v - lo) / (hi - lo) * chartH).toFloat()
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val tl = measurer.measure(m.format(v), style)
            drawText(tl, topLeft = Offset(0f, y(v) - tl.size.height / 2f))
            drawLine(grid.copy(alpha = 0.4f), Offset(leftPx, y(v)), Offset(size.width, y(v)), 1f)
        }
        for (h in listOf(0, 6, 12, 18)) {
            val tl = measurer.measure("$h", style)
            drawText(tl, topLeft = Offset(leftPx + h * slot, top + chartH + 2.dp.toPx()))
        }
        for (h in 0 until 24) {
            val s = byHour[h] ?: continue
            val k = stagger(t, h, 24)
            val cx = leftPx + h * slot + slot / 2
            if (sum) {
                val bh = (y(lo) - y(s.avg)) * k
                drawRect(color.copy(alpha = barAlpha(h, sel, 0.85f)), Offset(cx - slot * 0.35f, y(lo) - bh), Size(slot * 0.7f, bh))
            } else {
                val bw = slot * 0.55f
                val mid = (y(s.lo) + y(s.hi)) / 2
                val half = (y(s.lo) - y(s.hi)) / 2 * k
                drawRoundRect(
                    color.copy(alpha = 0.22f * barAlpha(h, sel, 1f)),
                    Offset(cx - bw / 2, mid - half), Size(bw, half * 2), CornerRadius(bw / 2, bw / 2),
                )
                drawCircle(color.copy(alpha = barAlpha(h, sel, 1f)), 3.5.dp.toPx() * k, Offset(cx, y(s.avg)))
            }
        }
        val i = sel.last
        val s = byHour[i]
        if (s != null) {
            val text = "$i:00–${i + 1}:00 · śr. ${m.format(s.avg)} ${m.unit}" +
                if (!sum) " (${m.format(s.lo)}–${m.format(s.hi)})" else ""
            tooltip(measurer, text, leftPx + i * slot + slot / 2, y(if (sum) s.avg else s.hi), tipStyle, tipBg, sel.alpha.value, grid, top + chartH)
        }
    }
}

// ---------------------------------------------------------------------------
// Noc / dzień dla zdarzeń z innych aplikacji (wiatry, AF)
// ---------------------------------------------------------------------------

@Composable
fun ExtDayView(source: String, label: String, day: LocalDate, color: Color, onDay: (LocalDate) -> Unit) {
    val night = source == ExchangeImport.SRC_NIGHT
    // Noc: od 18:00 poprzedniego dnia do 12:00 dnia pobudki.
    val from = if (night) Fmt.dayStart(day.minusDays(1)) + 18 * HOUR else Fmt.dayStart(day)
    val to = if (night) Fmt.dayStart(day) + 12 * HOUR else Fmt.dayStart(day.plusDays(1))
    val app = LifeApp.instance
    val data by produceState<Pair<List<ExtEvent>, List<ActSpan>>?>(null, source, label, from) {
        val cats = app.db.categories().getAll().associateBy { it.id }
        val ev = app.db.ext().all().filter { it.source == source && it.label == label && it.start in from until to }
        val spans = Timeline.resolve(app.repo.getRange(from, to), from, to, System.currentTimeMillis())
            .map { ActSpan(it.start, it.end, cats[it.entry.categoryId]) }
        value = ev to spans
    }
    DayNav(day, onDay)
    val d = data ?: return
    val events = d.first.filter { it.type != "window" && it.type != "day_total" }
    val windows = d.first.filter { it.type == "window" }
    TrendCard {
        Text(
            if (night) "Noc ${Fmt.dayTitle(day.minusDays(1)).lowercase()} → ${Fmt.dayTitle(day).lowercase()}, 18:00–12:00"
            else "Cały dzień",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        EventsAxis(events, windows, from, to, color, night)
        Spacer(Modifier.height(6.dp))
        ActivityStrip(d.second, from, to)
    }
    val total = d.first.firstOrNull { it.type == "day_total" }?.value?.toInt()
    Spacer(Modifier.height(10.dp))
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        StatBoxDay("zdarzeń", "${total ?: events.size}", Modifier.weight(1f))
        if (events.isNotEmpty()) {
            StatBoxDay("pierwsze", Fmt.time(events.minOf { it.start }), Modifier.weight(1f))
            StatBoxDay("ostatnie", Fmt.time(events.maxOf { it.start }), Modifier.weight(1f))
        }
        if (windows.isNotEmpty()) {
            StatBoxDay("nasłuch", Fmt.durationShort(windows.sumOf { (it.end ?: it.start) - it.start }), Modifier.weight(1f))
        }
    }
    if (events.isNotEmpty()) {
        val byAct = events.groupBy { HealthDay.activityAt(d.second, it.start)?.id }
            .map { (_, l) -> HealthDay.activityAt(d.second, l.first().start) to l.size }.sortedByDescending { it.second }
        SectionTitle("Wg aktywności")
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
            byAct.forEach { (c, n) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(c, 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(c?.name ?: "Bez aktywności", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("$n", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        SectionTitle("Godziny")
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
            events.sortedBy { it.start }.forEach { e ->
                val act = HealthDay.activityAt(d.second, e.start)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Fmt.time(e.start) + (e.end?.let { "–" + Fmt.time(it) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(110.dp),
                    )
                    Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(act)))
                    Spacer(Modifier.width(6.dp))
                    Text(act?.name ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventsAxis(events: List<ExtEvent>, windows: List<ExtEvent>, from: Long, to: Long, color: Color, night: Boolean) {
    val t = entrance(events.size to from)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val leftPx = with(LocalDensity.current) { 34.dp.toPx() }
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val w = size.width - leftPx
        val span = (to - from).toFloat()
        fun x(time: Long) = leftPx + w * ((time - from) / span)
        val bottom = 16.dp.toPx()
        val h = size.height - bottom
        // Okna nasłuchu
        windows.forEach { win ->
            val a = x(maxOf(win.start, from))
            val b = x(minOf(win.end ?: win.start, to))
            if (b > a) drawRect(track, Offset(a, 0f), Size(b - a, h))
        }
        // Zdarzenia: krótkie kreski; epizody z końcem — prostokąty
        val hourCounts = IntArray(((to - from) / HOUR).toInt() + 1)
        events.forEach { e -> hourCounts[((e.start - from) / HOUR).toInt().coerceIn(0, hourCounts.size - 1)]++ }
        val maxC = (hourCounts.maxOrNull() ?: 1).coerceAtLeast(1)
        hourCounts.forEachIndexed { i, c ->
            if (c == 0) return@forEachIndexed
            val bh = h * 0.55f * c / maxC * stagger(t, i, hourCounts.size)
            drawRect(color.copy(alpha = 0.35f), Offset(x(from + i * HOUR) + 2f, h - bh), Size(w / hourCounts.size - 4f, bh))
        }
        events.forEachIndexed { i, e ->
            val a = x(e.start)
            if (a > x(from + (span * t).toLong())) return@forEachIndexed
            val end = e.end
            if (end != null && end > e.start) {
                drawRect(color, Offset(a, h * 0.1f), Size((x(end) - a).coerceAtLeast(2f), h * 0.25f))
            } else {
                drawLine(color, Offset(a, h * 0.08f), Offset(a, h * 0.38f), 2.dp.toPx())
            }
        }
        val hours = if (night) listOf(18, 21, 0, 3, 6, 9, 12) else listOf(0, 6, 12, 18)
        hours.forEachIndexed { k, hr ->
            val off = if (night) (k * 3) * HOUR else hr * HOUR
            val tl = measurer.measure("$hr:00", style)
            drawText(tl, topLeft = Offset((x(from + off) - tl.size.width / 2f).coerceIn(leftPx, size.width - tl.size.width), h + 2.dp.toPx()))
        }
    }
}
