package pl.rafal.lifecycle.domain

import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.ExtEvent
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.util.Fmt

/**
 * Obciążenie AF (z Polar EKG) w podziale na to, co robiłeś i gdzie byłeś (wpisy Doby).
 * Liczy się tylko czas, gdy opaska nagrywała (okna zapisu), więc miejsce, w którym rzadko nosisz opaskę,
 * nie wygląda na „bezpieczne” tylko dlatego, że nie było pomiaru.
 */
object AfContext {
    data class Bucket(
        val key: String,
        val label: String,
        val color: Int?,
        val monitoredMs: Long,
        val afMs: Long,
        val episodes: Int,
    ) {
        val burden: Double get() = if (monitoredMs <= 0) 0.0 else afMs.toDouble() / monitoredMs
    }

    data class Result(
        val total: Bucket,
        val byCategory: List<Bucket>,
        val byPlace: List<Bucket>,
        /** Godzina doby → (nagrany czas, czas AF) w ms. */
        val byHour: List<Pair<Long, Long>>,
    )

    private data class Span(val a: Long, val b: Long)

    private fun overlap(a1: Long, b1: Long, spans: List<Span>): Long {
        var sum = 0L
        for (s in spans) {
            if (s.b <= a1) continue
            if (s.a >= b1) break
            sum += minOf(b1, s.b) - maxOf(a1, s.a)
        }
        return sum
    }

    private fun union(list: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in list.sortedBy { it.a }) {
            val last = out.lastOrNull()
            if (last != null && s.a <= last.b) out[out.size - 1] = Span(last.a, maxOf(last.b, s.b)) else out += s
        }
        return out
    }

    /** Minimalny czas nagrania, żeby kategoria/miejsce pojawiło się w wynikach (30 min). */
    const val MIN_MONITORED = 30 * 60_000L

    fun compute(
        entries: List<Entry>,
        categories: Map<Long, Category>,
        places: Map<Long, Place>,
        ext: List<ExtEvent>,
        now: Long = System.currentTimeMillis(),
    ): Result? {
        val ecg = ext.filter { it.source == ExchangeImport.SRC_ECG }
        val windows = union(ecg.filter { it.type == "window" && it.end != null }.map { Span(it.start, it.end!!) })
        if (windows.isEmpty()) return null
        val af = union(ecg.filter { it.type == "af" && it.end != null }.map { Span(it.start, it.end!!) })
        val afStarts = ecg.filter { it.type == "af" }.map { it.start }.sorted()

        class Acc(val label: String, val color: Int?) {
            var mon = 0L; var af = 0L; var n = 0
        }
        val cat = LinkedHashMap<String, Acc>()
        val plc = LinkedHashMap<String, Acc>()
        for (e in entries.sortedBy { it.startAt }) {
            val a = e.startAt
            val b = e.endAt ?: now
            if (b <= a) continue
            val mon = overlap(a, b, windows)
            if (mon <= 0) continue
            val afMs = overlap(a, b, af)
            val n = afStarts.count { it in a until b }
            val c = categories[e.categoryId]
            cat.getOrPut("c${e.categoryId}") { Acc(c?.name ?: "Inne", c?.color) }.apply { this.mon += mon; this.af += afMs; this.n += n }
            val p = e.placeId?.let { places[it] }
            if (p != null) plc.getOrPut("p${p.id}") { Acc(p.name, c?.color) }.apply { this.mon += mon; this.af += afMs; this.n += n }
        }

        val hours = Array(24) { longArrayOf(0, 0) }
        fun spread(spans: List<Span>, idx: Int) {
            for (s in spans) {
                var t = s.a
                while (t < s.b) {
                    val z = java.time.Instant.ofEpochMilli(t).atZone(Fmt.zone)
                    val next = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                    val end = minOf(next, s.b)
                    hours[z.hour][idx] += end - t
                    t = end
                }
            }
        }
        spread(windows, 0)
        spread(af, 1)

        fun list(m: Map<String, Acc>) = m.map { (k, v) -> Bucket(k, v.label, v.color, v.mon, v.af, v.n) }
            .filter { it.monitoredMs >= MIN_MONITORED }
            .sortedWith(compareByDescending<Bucket> { it.burden }.thenByDescending { it.monitoredMs })

        val totalMon = windows.sumOf { it.b - it.a }
        val totalAf = af.sumOf { it.b - it.a }
        return Result(
            Bucket("all", "Razem", null, totalMon, totalAf, afStarts.size),
            list(cat), list(plc),
            hours.map { it[0] to it[1] },
        )
    }
}
