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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.domain.AfContext
import pl.rafal.lifecycle.domain.ExtVisibility
import pl.rafal.lifecycle.domain.ExchangeImport
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

private val AF_RED = Color(0xFFFF2D55)

/** Obciążenie AF według aktywności, miejsc i pory dnia — tylko w czasie nagrań Polar EKG. */
@Composable
fun AfContextSection(days: Int) {
    var res by remember { mutableStateOf<AfContext.Result?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(days) {
        res = withContext(Dispatchers.IO) {
            val app = LifeApp.instance
            val to = Fmt.dayStart(LocalDate.now().plusDays(1))
            val from = Fmt.dayStart(LocalDate.now().minusDays(days.toLong() - 1))
            if (!ExtVisibility.visible(ExchangeImport.SRC_ECG, "")) return@withContext null
            val ext = app.db.ext().all().filter { it.start < to && (it.end ?: it.start) >= from }
            val entries = app.repo.getRange(from, to)
            val cats = app.db.categories().getAll().associateBy { it.id }
            val places = app.db.places().getAll().associateBy { it.id }
            AfContext.compute(entries, cats, places, ext)
        }
        loaded = true
    }
    val r = res
    if (!loaded) return
    SectionTitle("Gdzie i przy czym")
    TrendCard {
        if (r == null || r.total.monitoredMs <= 0) {
            Text("Brak nagrań z Polar EKG w tym okresie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@TrendCard
        }
        Text(
            "Nagrania: ${Fmt.durationShort(r.total.monitoredMs)} · AF: ${Fmt.durationShort(r.total.afMs)} (${pct(r.total.burden)})",
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Procent czasu w AF w trakcie nagrań, osobno dla każdej aktywności i miejsca z Doby. " +
                "Pokazane, gdy nagrano tam co najmniej 30 min.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.byCategory.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Aktywność", style = MaterialTheme.typography.labelLarge)
            r.byCategory.forEach { BurdenRow(it, r.total.burden) }
        }
        if (r.byPlace.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Miejsce", style = MaterialTheme.typography.labelLarge)
            r.byPlace.forEach { BurdenRow(it, r.total.burden) }
        }
        if (r.total.afMs > 0) {
            Spacer(Modifier.height(10.dp))
            Text("Pora dnia", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            HourBurden(r.byHour)
        }
    }
}

private fun pct(v: Double): String = if (v > 0 && v < 0.01) "<1%" else "${Math.round(v * 100)}%"

@Composable
private fun BurdenRow(b: AfContext.Bucket, avg: Double) {
    val max = 1.0.coerceAtMost(maxOf(avg * 3, 0.05))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(b.color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(b.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (b.burden > avg * 1.5 && b.afMs > 0) FontWeight.Bold else null)
            Text(
                "nagrano ${Fmt.durationShort(b.monitoredMs)}" + if (b.episodes > 0) " · napady: ${b.episodes}" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.width(90.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxWidth((b.burden / max).coerceIn(0.0, 1.0).toFloat()).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(AF_RED)
            )
        }
        Text(pct(b.burden), Modifier.width(48.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HourBurden(hours: List<Pair<Long, Long>>) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val none = MaterialTheme.colorScheme.surfaceVariant
    val vals = hours.map { (m, a) -> if (m < 10 * 60_000L) null else a.toDouble() / m }
    val max = (vals.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(0.05)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val bottom = 16.dp.toPx()
        val h = size.height - bottom
        val slot = size.width / 24
        vals.forEachIndexed { i, v ->
            if (v == null) {
                drawRect(none, Offset(i * slot + slot * 0.15f, h - 2.dp.toPx()), Size(slot * 0.7f, 2.dp.toPx()))
            } else {
                val bh = (h * v / max).toFloat().coerceAtLeast(1.dp.toPx())
                drawRect(AF_RED.copy(alpha = 0.85f), Offset(i * slot + slot * 0.15f, h - bh), Size(slot * 0.7f, bh))
            }
        }
        for (k in listOf(0, 6, 12, 18)) drawText(measurer.measure(k.toString(), style), topLeft = Offset(k * slot, h + 2.dp.toPx()))
    }
}
