package pl.rafal.lifecycle.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.tracking.Notifications
import pl.rafal.lifecycle.tracking.Steps
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Wiersz podsumowania: etykieta, wartość, zmiana względem poprzedniego okresu (+ czy to dobrze). */
data class SumRow(val label: String, val value: String, val delta: String? = null, val good: Boolean? = null, val icon: String? = null, val color: Int? = null)

data class SumSection(val title: String, val icon: String, val color: Long, val rows: List<SumRow>, val notes: List<String> = emptyList())

data class PeriodSummary(val period: Period, val headline: String, val sections: List<SumSection>) {
    fun asText(): String = buildString {
        append("Doba — ").append(period.label()).append("\n").append(headline).append("\n")
        sections.forEach { s ->
            append("\n").append(s.title.uppercase(Fmt.PL)).append("\n")
            s.rows.forEach { r ->
                append("• ").append(r.label).append(": ").append(r.value)
                r.delta?.let { append(" (").append(it).append(")") }
                append("\n")
            }
            s.notes.forEach { append("– ").append(it).append("\n") }
        }
    }
}

object Summaries {
    private val dayNames = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")

    private fun dMs(cur: Long, prev: Long?): String? {
        if (prev == null || prev <= 0) return null
        val d = cur - prev
        if (abs(d) < 60_000L) return "bez zmian"
        return (if (d > 0) "+" else "−") + Fmt.durationShort(abs(d))
    }

    private fun dNum(cur: Double, prev: Double?, decimals: Int, unit: String = ""): String? {
        if (prev == null || prev == 0.0) return null
        val d = cur - prev
        if (abs(d) < Math.pow(10.0, -decimals.toDouble()) / 2) return "bez zmian"
        return (if (d > 0) "+" else "−") + String.format(Fmt.PL, "%.${decimals}f", abs(d)) + unit
    }

    suspend fun build(ctx: Context, p: Period): PeriodSummary = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val now = System.currentTimeMillis()
        val prev = p.prev()
        val from = Fmt.dayStart(prev.start)
        val to = Fmt.dayStart(p.end)
        val curFrom = Fmt.dayStart(p.start)
        val entries = app.repo.getRange(from, to)
        val cats = db.categories().getAll().associateBy { it.id }
        val places = db.places().getAll().associateBy { it.id }
        val stats = Stats.compute(entries, p, now)
        val sections = mutableListOf<SumSection>()
        val catOf = { kind: String -> cats.values.firstOrNull { it.kind == kind } }

        // --- Czas
        val days = stats.days
        val top = stats.totals.toList().filter { cats[it.first]?.kind != Kind.UNKNOWN }.sortedByDescending { it.second }.take(6)
        if (top.isNotEmpty()) {
            val tracked = stats.totals.values.sum()
            sections += SumSection(
                "Czas · średnio dziennie", "donut", 0xFF007AFF,
                listOf(SumRow("Śledzono", Fmt.durationShort(tracked / days), null, null)) + top.map { (id, v) ->
                    val c = cats[id]
                    SumRow(c?.name ?: "—", Fmt.durationShort(v / days), dMs(v / days, (stats.prevTotals[id] ?: 0L) / stats.prevDays), null, c?.icon, c?.color)
                },
                listOf("Dni z danymi: $days"),
            )
        }

        // --- Sen
        val sleep = catOf(Kind.SLEEP)
        if (sleep != null) {
            val nights = Trends.nights(entries, sleep.id, p.start, p.end, now)
            val prevNights = Trends.nights(entries, sleep.id, prev.start, prev.end, now)
            if (nights.isNotEmpty()) {
                val avg = nights.sumOf { it.end - it.start } / nights.size
                val prevAvg = if (prevNights.isEmpty()) null else prevNights.sumOf { it.end - it.start } / prevNights.size
                fun clock(list: List<Long>, shift: Boolean): String {
                    val m = list.map {
                        val t = java.time.Instant.ofEpochMilli(it).atZone(Fmt.zone).toLocalTime()
                        val mm = t.hour * 60 + t.minute
                        if (shift && mm < 12 * 60) mm + 1440 else mm
                    }.average().toInt() % 1440
                    return "%02d:%02d".format(m / 60, m % 60)
                }
                val longest = nights.maxBy { it.end - it.start }
                val shortest = nights.minBy { it.end - it.start }
                val f = DateTimeFormatter.ofPattern("d MMM", Fmt.PL)
                sections += SumSection(
                    "Sen", "moon", sleep.color.toLong() and 0xFFFFFFFFL,
                    listOf(
                        SumRow("Średnio", Fmt.durationShort(avg), dMs(avg, prevAvg), prevAvg?.let { avg >= it }),
                        SumRow("Zasypianie", clock(nights.map { it.start }, true)),
                        SumRow("Pobudka", clock(nights.map { it.end }, false)),
                        SumRow("Najdłużej", "${Fmt.durationShort(longest.end - longest.start)} · ${longest.day.format(f)}"),
                        SumRow("Najkrócej", "${Fmt.durationShort(shortest.end - shortest.start)} · ${shortest.day.format(f)}"),
                    ),
                )
            }
        }

        // --- Ruch
        val stepDays = Steps.perDay(db.steps().getRange(from, to))
        val curSteps = stepDays.filterKeys { it >= p.start && it < p.end }
        val prevSteps = stepDays.filterKeys { it >= prev.start && it < prev.end }
        val moveRows = mutableListOf<SumRow>()
        if (curSteps.isNotEmpty()) {
            val avg = curSteps.values.sum() / curSteps.size
            val prevAvg = if (prevSteps.isEmpty()) null else prevSteps.values.sum() / prevSteps.size
            val goal = Prefs(ctx).stepGoal
            val best = curSteps.maxBy { it.value }
            moveRows += SumRow("Kroki dziennie", Fmt.steps(avg), prevAvg?.let { dNum(avg.toDouble(), it.toDouble(), 0) }, prevAvg?.let { avg >= it }, "walk")
            moveRows += SumRow("Kroki razem", Fmt.steps(curSteps.values.sum()))
            moveRows += SumRow("Najwięcej", "${Fmt.steps(best.value)} · ${Fmt.dayTitle(best.key).lowercase()}")
            if (goal > 0) moveRows += SumRow("Dni z celem ${Fmt.steps(goal.toLong())}", "${curSteps.count { it.value >= goal }} z ${curSteps.size}")
        }
        val activeKinds = setOf(Kind.WALK, Kind.RUN, Kind.BIKE, Kind.WORKOUT)
        val active = stats.totals.filterKeys { cats[it]?.kind in activeKinds }.values.sum()
        val prevActive = stats.prevTotals.filterKeys { cats[it]?.kind in activeKinds }.values.sum()
        if (active > 0) moveRows += SumRow("Ruch dziennie", Fmt.durationShort(active / days), dMs(active / days, prevActive / stats.prevDays), active / days >= prevActive / stats.prevDays, "run")
        val workouts = entries.filter { it.source == Source.WORKOUT_HC && it.startAt >= curFrom }
        if (workouts.isNotEmpty()) {
            moveRows += SumRow("Treningi", "${workouts.size} · ${Fmt.durationShort(workouts.sumOf { (it.endAt ?: now) - it.startAt })}", null, null, "gym")
        }
        val dist = entries.filter { it.startAt >= curFrom }.mapNotNull { it.distanceM }.sum()
        if (dist > 0) moveRows += SumRow("Przebyty dystans", Fmt.distance(dist), null, null, "car")
        if (moveRows.isNotEmpty()) sections += SumSection("Ruch", "run", 0xFF34C759, moveRows)

        // --- Miejsca i dojazdy
        val stays = entries.filter { it.source == Source.AUTO_STAY && it.startAt >= curFrom && it.placeId != null }
        if (stays.isNotEmpty()) {
            val home = catOf(Kind.HOME)?.id
            val byPlace = stays.groupBy { it.placeId!! }.mapValues { (_, l) -> l.sumOf { (minOf(it.endAt ?: now, to)) - it.startAt } }
            val topPlaces = byPlace.filterKeys { places[it]?.categoryId != home }.toList().sortedByDescending { it.second }.take(3)
            val rows = mutableListOf(SumRow("Odwiedzone miejsca", "${byPlace.size}"))
            topPlaces.forEach { (id, ms) ->
                val pl = places[id]
                val c = pl?.let { cats[it.categoryId] }
                rows += SumRow(pl?.name ?: "—", Fmt.durationShort(ms), null, null, c?.icon, c?.color)
            }
            val newPlaces = places.values.count { it.createdAt >= curFrom }
            if (newPlaces > 0) rows += SumRow("Nowe miejsca", "$newPlaces")
            Commutes.stats(entries.filter { it.startAt >= curFrom }, cats)?.let { c ->
                c.toWorkAvg?.let { rows += SumRow("Dojazd do pracy", "${Fmt.durationShort(it)} · ${c.toWorkN}×", null, null, "commute") }
                c.toHomeAvg?.let { rows += SumRow("Powrót do domu", "${Fmt.durationShort(it)} · ${c.toHomeN}×", null, null, "commute") }
            }
            sections += SumSection("Miejsca", "place", 0xFFFF9F0A, rows)
        }

        // --- Zdrowie
        val samples = db.health().getRange(from, to)
        val trends = HealthTrends.metricTrends(samples, p)
        if (trends.isNotEmpty()) {
            sections += SumSection(
                "Zdrowie", "heart", 0xFFFF3B30,
                trends.map { t ->
                    val m = t.m
                    val sum = m.agg == DayAgg.SUM
                    val value = if (m == Metric.BP) "${m.format(t.avg)}/${m.format(t.v2 ?: 0.0)} ${m.unit}"
                    else m.format(t.avg) + (if (m.unit.isNotEmpty()) " ${m.unit}" else "") + if (sum) " / dzień" else ""
                    val hb = HealthTrends.higherIsBetter(m)
                    val good = if (hb == null || t.prevAvg == null) null else (t.avg >= t.prevAvg) == hb
                    SumRow(m.label, value, dNum(t.avg, t.prevAvg, m.decimals), good, m.icon, m.color.toInt())
                },
            )
        }

        // --- Z innych aplikacji
        val ext = db.ext().all().filter { it.start in from until to }
        val extRows = mutableListOf<SumRow>()
        ext.map { it.source to it.label }.distinct().forEach { (src, label) ->
            val counts = ExchangeImport.dailyCounts(ext, src, label)
            val cur = counts.filterKeys { it >= p.start && it < p.end }
            val pr = counts.filterKeys { it >= prev.start && it < prev.end }
            if (cur.isEmpty()) return@forEach
            val name = when (src) {
                ExchangeImport.SRC_NIGHT -> "Wiatry nocne"
                ExchangeImport.SRC_ECG -> "Epizody AF"
                else -> "Licznik $label"
            }
            val avg = cur.values.sum().toDouble() / cur.size
            val pAvg = if (pr.isEmpty()) null else pr.values.sum().toDouble() / pr.size
            extRows += SumRow(name, "${cur.values.sum()} · śr. ${String.format(Fmt.PL, "%.1f", avg)} / ${if (src == ExchangeImport.SRC_NIGHT) "noc" else "dzień"}", dNum(avg, pAvg, 1), null)
        }
        if (extRows.isNotEmpty()) sections += SumSection("Z innych aplikacji", "air", 0xFF8E44AD, extRows)

        // --- Zależności
        val farts = ExchangeImport.dailyCounts(ext, ExchangeImport.SRC_NIGHT, "")
        val insights = if (p.type == PeriodType.WEEK) emptyList() else HealthTrends.correlations(
            entries, cats, stepDays, samples, farts, p.start, p.end, now,
        )
        if (insights.isNotEmpty()) sections += SumSection("Zależności", "bulb", 0xFFFFCC00, emptyList(), insights)

        // --- Nagłówek
        val headline = buildString {
            val work = catOf(Kind.WORK)?.let { stats.totals[it.id] } ?: 0L
            if (work > 0) append("Praca: ${Fmt.durationShort(work)}. ")
            if (curSteps.isNotEmpty()) append("Kroki: ${Fmt.steps(curSteps.values.sum())}. ")
            val w = catOf(Kind.WORK)
            if (w != null && p.type == PeriodType.WEEK) {
                val best = stats.buckets.withIndex().maxByOrNull { it.value[w.id] ?: 0L }
                if (best != null && (best.value[w.id] ?: 0L) > 0) append("Najdłuższy dzień pracy: ${dayNames[best.index]}.")
            }
        }.trim().ifEmpty { "Za mało danych w tym okresie." }
        PeriodSummary(p, headline, sections)
    }

    /** Powiadomienia: tydzień (niedziela 19:00), miesiąc i rok (pierwszego dnia kolejnego, od 9:00). */
    suspend fun maybeNotify(ctx: Context) {
        val prefs = Prefs(ctx)
        val now = LocalDateTime.now()
        val sp = ctx.getSharedPreferences("summaries", Context.MODE_PRIVATE)
        suspend fun send(key: String, p: Period, title: String, id: Int) {
            if (sp.getLong(key, -1L) == p.start.toEpochDay()) return
            val s = build(ctx, p)
            if (s.sections.isEmpty()) return
            sp.edit().putLong(key, p.start.toEpochDay()).apply()
            Notifications.simple(
                ctx, id, Notifications.CH_SUMMARY, title, s.headline,
                "summary:${p.type.name}:${p.start.toEpochDay()}",
            )
        }
        if (prefs.weeklySummary && now.dayOfWeek == DayOfWeek.SUNDAY && now.hour >= 19) {
            send("week", Period.current(PeriodType.WEEK), "Twój tydzień w Dobie", 4000)
        }
        if (prefs.monthlySummary && now.hour >= 9) {
            val last = Period.current(PeriodType.MONTH).prev()
            if (now.toLocalDate() < last.end.plusDays(7)) send("month", last, "Podsumowanie: ${last.label()}", 4001)
        }
        if (prefs.yearlySummary && now.hour >= 9) {
            val last = Period.current(PeriodType.YEAR).prev()
            if (now.toLocalDate() < last.end.plusDays(14)) send("year", last, "Twój rok ${last.label()} w Dobie", 4002)
        }
    }

    fun parseKey(key: String): Period? = try {
        val parts = key.split(":")
        Period(PeriodType.valueOf(parts[1]), LocalDate.ofEpochDay(parts[2].toLong()))
    } catch (e: Exception) {
        null
    }
}
