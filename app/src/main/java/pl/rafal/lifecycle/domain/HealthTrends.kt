package pl.rafal.lifecycle.domain

import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.HealthSample
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.util.Fmt
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/** Podsumowanie jednego parametru zdrowotnego w okresie na tle poprzedniego. */
data class MetricTrend(
    val m: Metric,
    val avg: Double,
    val prevAvg: Double?,
    val v2: Double?,
    val prevV2: Double?,
    val min: Double,
    val max: Double,
    val days: Int,
    /** Słupki/punkty wykresu: dni (tydzień, miesiąc) albo miesiące (rok); null = brak danych. */
    val buckets: List<Double?>,
)

object HealthTrends {
    fun metricTrends(samples: List<HealthSample>, p: Period): List<MetricTrend> {
        val prev = p.prev()
        val byMetric = samples.groupBy { it.metric }
        return Metric.entries.mapNotNull { m ->
            val daily = HealthAgg.daily(m, byMetric[m.key].orEmpty())
            val cur = daily.filter { it.day >= p.start && it.day < p.end }
            if (cur.isEmpty()) return@mapNotNull null
            val pr = daily.filter { it.day >= prev.start && it.day < prev.end }
            val buckets: List<Double?> = if (p.type == PeriodType.YEAR) {
                (1..12).map { mo -> cur.filter { it.day.monthValue == mo }.takeIf { it.isNotEmpty() }?.map { it.value }?.average() }
            } else {
                val map = cur.associateBy { it.day }
                generateSequence(p.start) { it.plusDays(1) }.takeWhile { it < p.end }.map { map[it]?.value }.toList()
            }
            MetricTrend(
                m = m,
                avg = cur.map { it.value }.average(),
                prevAvg = pr.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
                v2 = cur.mapNotNull { it.v2 }.takeIf { it.isNotEmpty() }?.average(),
                prevV2 = pr.mapNotNull { it.v2 }.takeIf { it.isNotEmpty() }?.average(),
                min = cur.minOf { it.min ?: it.value },
                max = cur.maxOf { it.max ?: it.value },
                days = cur.size,
                buckets = buckets,
            )
        }
    }

    /** Czy wzrost parametru to dobra wiadomość (null = obojętne). */
    fun higherIsBetter(m: Metric): Boolean? = when (m) {
        Metric.HRV, Metric.VO2, Metric.ACTIVE_KCAL, Metric.FLOORS, Metric.WATER -> true
        Metric.RESTING_HR, Metric.BP, Metric.BODY_FAT, Metric.FAT_MASS -> false
        Metric.LEAN_MASS -> true
        else -> null
    }

    private data class Outcome(
        val label: String, val unit: String, val minDiff: Double, val lowerIsBetter: Boolean?,
        val values: Map<LocalDate, Double>, val decimals: Int, val feminine: Boolean = false,
    )

    private fun fmt(v: Double, d: Int) = String.format(Fmt.PL, "%.${d}f", v)

    /**
     * Zależności w danych: porównanie średniej parametru w dni „wysokie” i „niskie” wg czynnika
     * (sen, kroki dzień wcześniej, trening dzień wcześniej, dzień pracy, weekend).
     */
    fun correlations(
        entries: List<Entry>,
        cats: Map<Long, Category>,
        stepsPerDay: Map<LocalDate, Long>,
        samples: List<HealthSample>,
        farts: Map<LocalDate, Int>,
        start: LocalDate,
        end: LocalDate,
        now: Long,
    ): List<String> {
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it < end && it <= LocalDate.now() }.toList()
        if (days.size < 7) return emptyList()
        val byMetric = samples.groupBy { it.metric }
        fun daily(m: Metric) = HealthAgg.daily(m, byMetric[m.key].orEmpty()).associate { it.day to it.value }

        val outcomes = listOfNotNull(
            Outcome("tętno spoczynkowe", "bpm", 1.5, true, daily(Metric.RESTING_HR), 0),
            Outcome("HRV", "ms", 3.0, false, daily(Metric.HRV), 0),
            Outcome("ciśnienie skurczowe", "mmHg", 4.0, true, daily(Metric.BP), 0),
            Outcome("średnie tętno", "bpm", 3.0, null, daily(Metric.HR), 0),
            Outcome("liczba oddechów", "/min", 0.6, null, daily(Metric.RESP), 1, feminine = true),
            Outcome("liczba wiatrów w nocy", "", 1.0, null, farts.mapValues { it.value.toDouble() }, 1, feminine = true),
        ).filter { o -> days.count { it in o.values } >= 8 }
        if (outcomes.isEmpty()) return emptyList()

        val sleepCat = cats.values.firstOrNull { it.kind == Kind.SLEEP }?.id
        val workCat = cats.values.firstOrNull { it.kind == Kind.WORK }?.id
        val activeKinds = setOf(Kind.WALK, Kind.RUN, Kind.BIKE, Kind.WORKOUT)
        val totals = (listOf(start.minusDays(1)) + days).associateWith { Timeline.dayTotals(entries, it, now) }
        val nights = if (sleepCat != null) Trends.nights(entries, sleepCat, start, end, now).associate { it.day to (it.end - it.start) } else emptyMap()
        val workout = entries.filter { cats[it.categoryId]?.kind == Kind.WORKOUT || it.source == "WORKOUT_HC" }
            .map { Fmt.toLocalDate(it.startAt) }.toSet()

        data class Driver(val high: String, val low: String, val split: (LocalDate) -> Boolean?)

        fun median(vals: List<Double>) = vals.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val sleepMed = median(nights.values.map { it.toDouble() })
        val stepsMed = median(days.mapNotNull { stepsPerDay[it.minusDays(1)]?.toDouble() })
        val drivers = listOf(
            Driver("po nocach z co najmniej ${Fmt.durationShort(sleepMed.toLong())} snu", "po krótszych nocach") { d ->
                nights[d]?.let { it >= sleepMed }
            },
            Driver("po dniach z co najmniej ${Fmt.steps(stepsMed.toLong())} kroków", "po mniej aktywnych dniach") { d ->
                stepsPerDay[d.minusDays(1)]?.let { it >= stepsMed }
            },
            Driver("po dniach z treningiem", "po dniach bez treningu") { d -> d.minusDays(1) in workout },
            Driver("w dni pracy", "w dni wolne") { d ->
                if (workCat == null) null else (totals[d]?.get(workCat) ?: 0L) > 60 * 60_000L
            },
            Driver("w weekendy", "w dni robocze") { d -> d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY },
            Driver("po dniach z ponad godziną ruchu", "po dniach z mniejszą ilością ruchu") { d ->
                totals[d.minusDays(1)]?.let { m -> m.filterKeys { cats[it]?.kind in activeKinds }.values.sum() >= 60 * 60_000L }
            },
        )

        data class Found(val text: String, val score: Double)
        val found = mutableListOf<Found>()
        for (o in outcomes) for (dr in drivers) {
            val hi = ArrayList<Double>()
            val lo = ArrayList<Double>()
            for (d in days) {
                val v = o.values[d] ?: continue
                when (dr.split(d)) {
                    true -> hi += v
                    false -> lo += v
                    null -> {}
                }
            }
            if (hi.size < 4 || lo.size < 4) continue
            val a = hi.average()
            val b = lo.average()
            val diff = a - b
            if (abs(diff) < o.minDiff) continue
            val word = if (o.feminine) (if (diff > 0) "wyższa" else "niższa") else (if (diff > 0) "wyższe" else "niższe")
            val verdict = when {
                o.lowerIsBetter == null -> ""
                (diff < 0) == o.lowerIsBetter -> " 👍"
                else -> ""
            }
            val unit = if (o.unit.isNotEmpty()) " ${o.unit}" else ""
            val text = "${dr.high.replaceFirstChar { it.uppercase() }} ${o.label} jest średnio $word o " +
                "${fmt(abs(diff), o.decimals)}$unit (${fmt(a, o.decimals)} vs ${fmt(b, o.decimals)} ${dr.low})$verdict"
            found += Found(text, abs(diff) / o.minDiff * minOf(hi.size, lo.size))
        }
        return found.sortedByDescending { it.score }.take(6).map { it.text }
    }
}
