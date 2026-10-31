package pl.rafal.lifecycle.domain

import org.json.JSONArray
import org.json.JSONObject
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

/**
 * „czynniki-dnia” v1 z Doby dla Polar EKG (co poprzedza napady AF): kroki, trening, chód, praca, podróż.
 * Ostatnie 180 dni; kroki z wielu źródeł — w każdej godzinie bierzemy największą wartość.
 */
object DayFactors {
    private const val DAYS = 180L

    suspend fun build(): ByteArray {
        val app = LifeApp.instance
        val today = LocalDate.now()
        val from = Fmt.dayStart(today.minusDays(DAYS))
        val to = Fmt.dayStart(today.plusDays(1))
        val steps = HashMap<LocalDate, Long>()
        app.db.steps().getRange(from, to).groupBy { it.hourStart }.forEach { (h, l) ->
            val d = Fmt.toLocalDate(h)
            steps[d] = (steps[d] ?: 0L) + l.maxOf { it.steps }
        }
        val cats = app.db.categories().getAll().associateBy { it.id }
        val mins = HashMap<LocalDate, HashMap<String, Long>>()
        val now = System.currentTimeMillis()
        for (e in app.repo.getRange(from, to)) {
            val kind = when (cats[e.categoryId]?.kind) {
                Kind.WORKOUT, Kind.RUN, Kind.BIKE -> "sport_min"
                Kind.WALK -> "walk_min"
                Kind.WORK, Kind.FIELD -> "work_min"
                Kind.TRAVEL, Kind.COMMUTE, Kind.TRAIN, Kind.TRANSIT -> "travel_min"
                else -> continue
            }
            // podział wpisu na dni
            var a = maxOf(e.startAt, from)
            val b = minOf(e.endAt ?: now, to)
            while (a < b) {
                val d = Fmt.toLocalDate(a)
                val next = Fmt.dayStart(d.plusDays(1))
                val end = minOf(next, b)
                val m = mins.getOrPut(d) { HashMap() }
                m[kind] = (m[kind] ?: 0L) + (end - a) / 60_000L
                a = end
            }
        }
        val days = JSONArray()
        for (d in (steps.keys + mins.keys).toSortedSet()) {
            val o = JSONObject().put("date", d.toString())
            steps[d]?.let { o.put("steps", it) }
            mins[d]?.forEach { (k, v) -> o.put(k, v) }
            days.put(o)
        }
        return JSONObject()
            .put("format", "czynniki-dnia").put("version", 1).put("source", "doba")
            .put("exported_at", now).put("days", days)
            .toString().toByteArray(Charsets.UTF_8)
    }
}
