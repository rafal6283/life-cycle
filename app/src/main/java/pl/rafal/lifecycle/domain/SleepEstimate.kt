package pl.rafal.lifecycle.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Sen z nocnej przerwy w używaniu telefonu bywa za długi: ktoś odkłada telefon o 18–19 i nie dotyka go do rana.
 * Wtedy początek snu szacujemy z innych śladów:
 *  - zwykła pora zasypiania (ustawiona albo z nocy z zegarka / wpisanych ręcznie / z późnym końcem używania telefonu),
 *  - kroki wieczorem (ktoś chodzi z telefonem – nie śpi),
 *  - podłączenie ładowarki w okolicy zwykłej pory snu.
 * Koniec: pierwsze używanie telefonu, chyba że wcześniej rano są kroki (wstał, a po telefon sięgnął później).
 */
object SleepEstimate {
    private const val MIN = 60_000L
    private const val H = 60 * MIN

    /** Kroki w godzinie, od których uznajemy, że ktoś nie spał (nocne wyjście do łazienki to zwykle mniej). */
    const val AWAKE_STEPS = 60L

    /** Domyślna pora zasypiania i budzenia (minuty od północy), gdy brak danych o nawykach. */
    const val DEFAULT_BED = 22 * 60 + 30
    const val DEFAULT_WAKE = 6 * 60 + 30

    /** Odłożenie telefonu w tych godzinach (minuty od północy) uznajemy za „wcześnie wieczorem”. */
    const val EARLY_FROM = 15 * 60
    const val EARLY_TO = 21 * 60

    data class Norm(val bedMin: Int, val wakeMin: Int, val fromNights: Int)

    data class Night(val start: Long, val end: Long, val estimatedStart: Boolean, val estimatedEnd: Boolean)

    /** Minuty od 12:00 – pozwala liczyć medianę pór wokół północy. */
    private fun fromNoon(t: Long, zone: ZoneId): Int {
        val lt = Instant.ofEpochMilli(t).atZone(zone).toLocalTime()
        return (lt.hour * 60 + lt.minute - 12 * 60 + 24 * 60) % (24 * 60)
    }

    private fun median(xs: List<Int>): Int = xs.sorted().let { it[it.size / 2] }

    /**
     * Zwykła pora snu z ostatnich nocy. [trusted] – noce z zegarka lub wpisane ręcznie; [auto] – z przerwy w telefonie
     * (liczy się tylko ich koniec oraz początek, jeśli wypadł między 20:30 a 3:00 – wtedy telefon był używany do późna).
     * [userBed] – pora ustawiona przez użytkownika (minuty od północy) albo -1.
     */
    fun norm(trusted: List<Pair<Long, Long>>, auto: List<Pair<Long, Long>>, userBed: Int, zone: ZoneId): Norm {
        val lateFrom = 20 * 60 + 30 - 12 * 60
        val lateTo = 27 * 60 - 12 * 60
        val starts = trusted.map { fromNoon(it.first, zone) } +
            auto.map { fromNoon(it.first, zone) }.filter { it in lateFrom..lateTo }
        val ends = (trusted + auto).map { fromNoon(it.second, zone) }
        val bed = when {
            userBed >= 0 -> userBed
            starts.size >= 3 -> (median(starts) + 12 * 60) % (24 * 60)
            else -> DEFAULT_BED
        }
        val wake = if (ends.size >= 3) (median(ends) + 12 * 60) % (24 * 60) else DEFAULT_WAKE
        return Norm(bed, wake, starts.size)
    }

    /** Chwila o podanej porze (minuty od północy) najbliższa [around]. */
    private fun at(minOfDay: Int, around: Long, zone: ZoneId): Long {
        val d = Instant.ofEpochMilli(around).atZone(zone).toLocalDate()
        return listOf(d.minusDays(1), d, d.plusDays(1))
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() + minOfDay * MIN }
            .minBy { kotlin.math.abs(it - around) }
    }

    /**
     * [gapStart]–[gapEnd]: najdłuższa nocna przerwa w używaniu telefonu. [steps]: początek godziny → kroki.
     * [charges]: chwile podłączenia ładowarki.
     */
    fun night(
        gapStart: Long, gapEnd: Long, norm: Norm, steps: Map<Long, Long>, charges: List<Long>, zone: ZoneId,
    ): Night {
        val mid = gapStart + (gapEnd - gapStart) / 2
        val bed = at(norm.bedMin, mid, zone).let { if (it > gapEnd) it - 24 * H else it }
        val wake = at(norm.wakeMin, mid, zone).let { if (it < gapStart) it + 24 * H else it }

        // wieczorem chodził z telefonem – nie spał (do ~45 min po początku takiej godziny)
        var awake = gapStart
        for ((h, n) in steps) {
            if (n >= AWAKE_STEPS && h >= gapStart - H && h < bed + H && h + 45 * MIN > awake) awake = min(h + 45 * MIN, gapEnd)
        }

        var start = max(gapStart, awake)
        var estStart = awake > gapStart
        // tylko gdy telefon odłożono wcześnie wieczorem (przed 21:00) i ≥ 1 h przed zwykłą porą snu –
        // późniejsze odłożenie telefonu to zwykle po prostu pójście spać (choćby wcześniej niż zwykle)
        val putDown = Instant.ofEpochMilli(gapStart).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        if (putDown in EARLY_FROM until EARLY_TO && gapStart < bed - H) {
            // telefon odłożony dużo przed zwykłą porą snu: zasypianie o zwykłej porze albo przy ładowarce
            val charge = charges.filter { it in max(gapStart, bed - H)..(bed + 90 * MIN) && it < gapEnd }.minOrNull()
            start = max(charge ?: bed, awake)
            estStart = true
        }

        // rano wstał (kroki), a po telefon sięgnął później
        var end = gapEnd
        var estEnd = false
        val morning = steps.filter { (h, n) -> n >= AWAKE_STEPS && h >= max(start + 3 * H, wake - 90 * MIN) && h + 30 * MIN < gapEnd }
            .keys.minOrNull()
        if (morning != null) {
            end = morning + 30 * MIN
            estEnd = true
        }
        if (start >= end) return Night(gapStart, gapEnd, false, false)
        return Night(start, end, estStart, estEnd)
    }
}
