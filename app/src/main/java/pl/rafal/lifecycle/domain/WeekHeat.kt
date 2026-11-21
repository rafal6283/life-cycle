package pl.rafal.lifecycle.domain

import pl.rafal.lifecycle.data.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Tydzień w kratkach: jak często o danej godzinie danego dnia tygodnia trwa wybrana kategoria. */
object WeekHeat {
    /**
     * Udział czasu (0…1) kategorii [catId] w każdej godzinie tygodnia: [dzień 0 = pn][godzina 0–23],
     * w dniach od [from] do [to] (bez [to]), tylko przeszłość (do [now]).
     */
    fun compute(entries: List<Entry>, catId: Long, from: LocalDate, to: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): Array<FloatArray> {
        val ms = Array(7) { DoubleArray(24) }
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(to.atStartOfDay(zone).toInstant().toEpochMilli(), now)
        for (e in entries) {
            if (e.categoryId != catId) continue
            var cur = maxOf(e.startAt, start)
            val en = minOf(e.endAt ?: now, end)
            while (cur < en) {
                val z = Instant.ofEpochMilli(cur).atZone(zone)
                val hourEnd = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val e2 = minOf(hourEnd, en)
                ms[z.dayOfWeek.value - 1][z.hour] += (e2 - cur).toDouble()
                cur = e2
            }
        }
        // ile razy każdy dzień tygodnia wystąpił (dzisiaj liczy się częściowo – jak cały, prościej i bezpiecznie)
        val cnt = IntArray(7)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var d = from
        while (d.isBefore(to) && !d.isAfter(today)) {
            cnt[d.dayOfWeek.value - 1]++
            d = d.plusDays(1)
        }
        return Array(7) { di ->
            FloatArray(24) { h -> if (cnt[di] == 0) 0f else (ms[di][h] / (cnt[di] * 3_600_000.0)).toFloat().coerceIn(0f, 1f) }
        }
    }

    /** Najbardziej „typowa” godzina: (dzień, godzina, udział) albo null. */
    fun peak(heat: Array<FloatArray>): Triple<Int, Int, Float>? {
        var best: Triple<Int, Int, Float>? = null
        for (d in 0 until 7) for (h in 0 until 24) {
            val v = heat[d][h]
            if (v > 0f && (best == null || v > best.third)) best = Triple(d, h, v)
        }
        return best
    }
}
