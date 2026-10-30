package pl.rafal.lifecycle.domain

import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.MoodDay
import pl.rafal.lifecycle.data.ScreenDay
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.data.WeatherDay
import pl.rafal.lifecycle.util.Fmt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

object Mood {
    val faces = listOf("😞", "🙁", "😐", "🙂", "😄")
    val labels = listOf("Źle", "Słabo", "Tak sobie", "Dobrze", "Świetnie")
    val colors = listOf(0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF8BD450, 0xFF30D158).map { it.toInt() }

    fun face(v: Double): String = faces[(v.coerceIn(1.0, 5.0) - 1).toInt().coerceIn(0, 4)]

    /**
     * Co idzie w parze z lepszym nastrojem: porównanie średniej oceny w dni „z czymś” i „bez”.
     * Zwraca zdania od najsilniejszych zależności.
     */
    fun insights(
        moods: List<MoodDay>,
        entries: List<Entry>,
        cats: Map<Long, Category>,
        stepsPerDay: Map<LocalDate, Long>,
        screens: List<ScreenDay>,
        weather: List<WeatherDay>,
        now: Long,
    ): List<String> {
        if (moods.size < 10) return emptyList()
        val mood = moods.associate { LocalDate.ofEpochDay(it.day) to it.value.toDouble() }
        val days = mood.keys.sorted()
        val totals = days.associateWith { Timeline.dayTotals(entries, it, now) }
        val sleepCat = cats.values.firstOrNull { it.kind == Kind.SLEEP }?.id
        val nights = if (sleepCat != null)
            Trends.nights(entries, sleepCat, days.first(), days.last().plusDays(1), now).associate { it.day to (it.end - it.start) }
        else emptyMap()
        val screen = screens.associateBy { LocalDate.ofEpochDay(it.day) }
        val wx = weather.associateBy { LocalDate.ofEpochDay(it.day) }
        val activeKinds = setOf(Kind.WALK, Kind.RUN, Kind.BIKE, Kind.WORKOUT)
        val outKinds = setOf(Kind.HOME, Kind.SLEEP, Kind.UNKNOWN)
        val workout = entries.filter { cats[it.categoryId]?.kind == Kind.WORKOUT || it.source == Source.WORKOUT_HC }
            .map { Fmt.toLocalDate(it.startAt) }.toSet()
        val friends = cats.values.filter { it.icon == "friends" || it.name.contains("Znajom", true) || it.icon == "groups" }
            .map { it.id }.toSet()

        fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val sleepMed = median(nights.values.map { it.toDouble() })
        val stepsMed = median(days.mapNotNull { stepsPerDay[it]?.toDouble() })
        val screenMed = median(days.mapNotNull { screen[it]?.screenMs?.toDouble() })
        val outMed = median(days.mapNotNull { d -> totals[d]?.filterKeys { cats[it]?.kind !in outKinds }?.values?.sum()?.toDouble() })

        data class Driver(val yes: String, val no: String, val split: (LocalDate) -> Boolean?)
        val drivers = listOfNotNull(
            Driver("po nocach z co najmniej ${Fmt.durationShort(sleepMed.toLong())} snu", "po krótszych") { d ->
                nights[d]?.let { it >= sleepMed }
            }.takeIf { nights.size >= 6 },
            Driver("w dni z co najmniej ${Fmt.steps(stepsMed.toLong())} kroków", "w mniej aktywne") { d ->
                stepsPerDay[d]?.let { it >= stepsMed }
            }.takeIf { stepsMed > 0 },
            Driver("w dni z ponad godziną ruchu", "w pozostałe") { d ->
                totals[d]?.let { m -> m.filterKeys { cats[it]?.kind in activeKinds }.values.sum() >= 3_600_000L }
            },
            Driver("w dni z treningiem", "bez treningu") { d -> d in workout },
            Driver("w dni z mniej niż ${Fmt.durationShort(screenMed.toLong())} przed ekranem", "gdy telefonu było więcej") { d ->
                screen[d]?.let { it.screenMs < screenMed }
            }.takeIf { screenMed > 0 },
            Driver("gdy po 22:00 telefon był odłożony (< 30 min)", "gdy siedziałeś w nim wieczorem") { d ->
                screen[d]?.hourList()?.let { h -> h[22] + h[23] < 30 * 60_000L }
            },
            Driver("w dni ze spotkaniami ze znajomymi", "bez spotkań") { d ->
                if (friends.isEmpty()) null else totals[d]?.let { m -> friends.any { (m[it] ?: 0L) >= 30 * 60_000L } }
            },
            Driver("gdy dłużej byłeś poza domem", "gdy większość dnia w domu") { d ->
                totals[d]?.let { m -> m.filterKeys { cats[it]?.kind !in outKinds }.values.sum() >= outMed }
            }.takeIf { outMed > 0 },
            Driver("w dni z ponad 9 h pracy", "w krótsze dni pracy") { d ->
                val work = totals[d]?.filterKeys { cats[it]?.kind == Kind.WORK || cats[it]?.kind == Kind.FIELD }?.values?.sum() ?: 0L
                if (work < 3_600_000L) null else work >= 9 * 3_600_000L
            },
            Driver("w słoneczne dni (≥ 6 h słońca)", "w pochmurne") { d -> wx[d]?.let { it.sunH >= 6 } },
            Driver("w deszczowe dni", "w suche") { d -> wx[d]?.let { it.precip >= 1.0 } },
            Driver("w weekendy", "w dni robocze") { d -> d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY },
        )

        data class Found(val text: String, val score: Double)
        val out = mutableListOf<Found>()
        for (dr in drivers) {
            val yes = ArrayList<Double>()
            val no = ArrayList<Double>()
            for (d in days) {
                when (dr.split(d)) {
                    true -> yes += mood[d]!!
                    false -> no += mood[d]!!
                    null -> {}
                }
            }
            if (yes.size < 3 || no.size < 3) continue
            val a = yes.average()
            val b = no.average()
            if (abs(a - b) < 0.3) continue
            fun f(v: Double) = String.format(Fmt.PL, "%.1f", v)
            val text = "${dr.yes.replaceFirstChar { it.uppercase() }} nastrój średnio ${f(a)} ${face(a)} " +
                "vs ${f(b)} ${face(b)} ${dr.no}"
            out += Found(text, abs(a - b) * sqrt(minOf(yes.size, no.size).toDouble()))
        }
        return out.sortedByDescending { it.score }.take(6).map { it.text }
    }
}

/** Regularność snu: jak stałe są pory zasypiania i pobudki. */
data class SleepRegularity(
    val score: Int,
    val bedSdMin: Int,
    val wakeSdMin: Int,
    /** Różnica środka snu: weekend vs dni robocze (społeczny jet lag), null gdy za mało danych. */
    val socialJetlagMs: Long?,
    val nights: Int,
)

object SleepRhythm {
    /** Minuty od 12:00 — noc nie „przeskakuje” przez północ. */
    private fun fromNoon(ms: Long): Double {
        val z = Instant.ofEpochMilli(ms).atZone(Fmt.zone)
        val m = z.hour * 60 + z.minute
        return ((m - 720 + 1440) % 1440).toDouble()
    }

    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val avg = v.average()
        return sqrt(v.sumOf { (it - avg) * (it - avg) } / (v.size - 1))
    }

    fun compute(nights: List<Trends.Night>): SleepRegularity? {
        if (nights.size < 5) return null
        val bed = nights.map { fromNoon(it.start) }
        val wake = nights.map { fromNoon(it.end) }
        val sdBed = sd(bed)
        val sdWake = sd(wake)
        val score = (100 - (sdBed + sdWake) / 2 * 1.2).toInt().coerceIn(0, 100)
        val weekend = nights.filter { it.day.dayOfWeek == DayOfWeek.SATURDAY || it.day.dayOfWeek == DayOfWeek.SUNDAY }
        val weekday = nights - weekend.toSet()
        val jet = if (weekend.size >= 2 && weekday.size >= 3) {
            fun mid(l: List<Trends.Night>) = l.map { (fromNoon(it.start) + fromNoon(it.end)) / 2 }.average()
            ((mid(weekend) - mid(weekday)) * 60_000).toLong()
        } else null
        return SleepRegularity(score, sdBed.toInt(), sdWake.toInt(), jet, nights.size)
    }
}
