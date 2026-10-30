package pl.rafal.lifecycle.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.ScreenDay
import pl.rafal.lifecycle.data.WeatherDay
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.domain.Trends
import pl.rafal.lifecycle.tracking.WeatherSync
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private fun deg(v: Double) = "${v.roundToInt()}°"

/** Jedna linijka pogody nad kartami dnia. */
@Composable
fun WeatherLine(day: LocalDate) {
    val ctx = LocalContext.current
    if (!remember { Prefs(ctx).weather }) return
    val list by remember(day) { LifeApp.instance.db.weather().observe(day.toEpochDay(), day.toEpochDay() + 1) }
        .collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { try { WeatherSync.run(ctx) } catch (_: Exception) {} }
    val w = list.firstOrNull() ?: return
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(WeatherSync.emoji(w.code), fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            "${deg(w.tMax)} / ${deg(w.tMin)} · ${WeatherSync.label(w.code)}" +
                (if (w.precip >= 0.2) " · ${String.format(Fmt.PL, "%.1f", w.precip)} mm" else "") +
                (if (w.sunH >= 0.5) " · ${w.sunH.roundToInt()} h słońca" else ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

object WeatherInsights {
    /** Jak pogoda wpływa na kroki, sen, czas poza domem i telefon. */
    fun compute(
        weather: List<WeatherDay>, entries: List<Entry>, cats: Map<Long, Category>,
        steps: Map<LocalDate, Long>, screens: List<ScreenDay>, now: Long,
    ): List<String> {
        val today = LocalDate.now()
        val wx = weather.associateBy { LocalDate.ofEpochDay(it.day) }.filterKeys { it < today }
        if (wx.size < 10) return emptyList()
        val days = wx.keys.sorted()
        val totals = days.associateWith { Timeline.dayTotals(entries, it, now) }
        val sleepCat = cats.values.firstOrNull { it.kind == Kind.SLEEP }?.id
        val nights = if (sleepCat != null) Trends.nights(entries, sleepCat, days.first(), today, now).associate { it.day to (it.end - it.start) } else emptyMap()
        val screen = screens.associateBy { LocalDate.ofEpochDay(it.day) }
        val outKinds = setOf(Kind.HOME, Kind.SLEEP, Kind.UNKNOWN)
        val medT = days.map { wx[it]!!.tMax }.sorted()[days.size / 2]
        val out = mutableListOf<Pair<String, Double>>()

        fun compare(
            name: String, yes: String, no: String, value: (LocalDate) -> Double?, split: (WeatherDay) -> Boolean,
            minDiff: Double, fmt: (Double) -> String, higherWord: String, lowerWord: String,
        ) {
            val a = ArrayList<Double>()
            val b = ArrayList<Double>()
            for (d in days) {
                val v = value(d) ?: continue
                if (split(wx[d]!!)) a += v else b += v
            }
            if (a.size < 3 || b.size < 3) return
            val x = a.average()
            val y = b.average()
            if (abs(x - y) < minDiff) return
            out += "${yes.replaceFirstChar { it.uppercase() }} $name średnio ${if (x > y) higherWord else lowerWord} o ${fmt(abs(x - y))} (${fmt(x)} vs ${fmt(y)} $no)" to
                abs(x - y) / minDiff
        }
        val stepsF = { v: Double -> Fmt.steps(v.toLong()) }
        val durF = { v: Double -> Fmt.durationShort(v.toLong()) }
        compare("robisz kroków", "w deszczowe dni", "w suche", { steps[it]?.toDouble() }, { it.precip >= 1.0 }, 800.0, stepsF, "więcej", "mniej")
        compare("robisz kroków", "w cieplejsze dni (≥ ${deg(medT)})", "w chłodniejsze", { steps[it]?.toDouble() }, { it.tMax >= medT }, 800.0, stepsF, "więcej", "mniej")
        compare(
            "spędzasz poza domem", "w słoneczne dni", "w pochmurne",
            { d -> totals[d]?.filterKeys { cats[it]?.kind !in outKinds }?.values?.sum()?.toDouble() },
            { it.sunH >= 6 }, 20 * 60_000.0, durF, "więcej", "mniej",
        )
        compare("śpisz", "po ciepłych nocach (≥ 15°)", "po chłodniejszych", { d -> nights[d]?.toDouble() }, { it.tMin >= 15 }, 15 * 60_000.0, durF, "dłużej", "krócej")
        compare("siedzisz w telefonie", "w deszczowe dni", "w suche", { d -> screen[d]?.screenMs?.toDouble() }, { it.precip >= 1.0 }, 15 * 60_000.0, durF, "dłużej", "krócej")
        return out.sortedByDescending { it.second }.take(5).map { it.first }
    }
}

@Composable
fun WeatherTrendSection(period: Period) {
    val ctx = LocalContext.current
    if (!remember { Prefs(ctx).weather }) return
    val app = LifeApp.instance
    val list by remember(period) { app.db.weather().observe(period.start.toEpochDay(), period.end.toEpochDay()) }
        .collectAsState(initial = emptyList())
    val insights by produceState(emptyList<String>(), period, list.size) {
        value = withContext(Dispatchers.Default) {
            val start = minOf(period.start, LocalDate.now().minusDays(90))
            val end = minOf(period.end, LocalDate.now().plusDays(1))
            val from = Fmt.dayStart(start)
            val to = Fmt.dayStart(end)
            WeatherInsights.compute(
                app.db.weather().range(start.toEpochDay(), end.toEpochDay()), app.repo.getRange(from, to),
                app.db.categories().getAll().associateBy { it.id },
                pl.rafal.lifecycle.tracking.Steps.perDay(app.db.steps().getRange(from, to)),
                app.db.screen().range(start.toEpochDay(), end.toEpochDay()), System.currentTimeMillis(),
            )
        }
    }
    if (list.isEmpty()) return
    SectionTitle("Pogoda")
    TrendCard {
        val warm = list.maxBy { it.tMax }
        val cold = list.minBy { it.tMin }
        Row {
            WStat("${String.format(Fmt.PL, "%.0f", list.map { it.tMax }.average())}°", "średnio w dzień", Modifier.weight(1f))
            WStat("${list.count { it.precip >= 1.0 }}", "dni z deszczem", Modifier.weight(1f))
            WStat("${list.sumOf { it.sunH }.roundToInt()} h", "słońca", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Najcieplej ${deg(warm.tMax)} (${Fmt.dayTitle(LocalDate.ofEpochDay(warm.day))}), " +
                "najzimniej ${deg(cold.tMin)} (${Fmt.dayTitle(LocalDate.ofEpochDay(cold.day))})",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (insights.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Pogoda a Ty", style = MaterialTheme.typography.labelLarge)
            insights.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun WStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
