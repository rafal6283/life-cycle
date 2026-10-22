package pl.rafal.lifecycle.domain

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Geo
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

data class Segment(val entry: Entry, val start: Long, val end: Long) {
    val duration: Long get() = end - start
}

object Timeline {
    private fun priority(source: String) = when (source) {
        Source.MANUAL, Source.OVERRIDE -> 4
        Source.WORKOUT_HC -> 3
        Source.SLEEP_HC, Source.SLEEP_AUTO -> 2
        else -> 1
    }

    /**
     * Rozwiązuje nakładające się wpisy w ciągłe segmenty. Wpisy ręczne wygrywają ze snem,
     * a sen z automatycznie wykrytymi pobytami.
     */
    fun resolve(entries: List<Entry>, from: Long, to: Long, now: Long): List<Segment> {
        val clipped = entries.mapNotNull { e ->
            val s = max(e.startAt, from)
            val en = min(e.endAt ?: now, to)
            if (en > s) Triple(e, s, en) else null
        }
        if (clipped.isEmpty()) return emptyList()
        val points = clipped.flatMap { listOf(it.second, it.third) }.distinct().sorted()
        val out = mutableListOf<Segment>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val best = clipped
                .filter { it.second <= a && it.third >= b }
                .maxWithOrNull(compareBy({ priority(it.first.source) }, { it.first.startAt }))
                ?: continue
            val last = out.lastOrNull()
            if (last != null && last.entry.id == best.first.id && last.end == a) {
                out[out.size - 1] = last.copy(end = b)
            } else {
                out += Segment(best.first, a, b)
            }
        }
        return out
    }

    fun totals(segments: List<Segment>): Map<Long, Long> {
        val m = HashMap<Long, Long>()
        for (s in segments) m[s.entry.categoryId] = (m[s.entry.categoryId] ?: 0L) + s.duration
        return m
    }

    fun dayTotals(entries: List<Entry>, day: LocalDate, now: Long): Map<Long, Long> {
        val f = Fmt.dayStart(day)
        val t = Fmt.dayStart(day.plusDays(1))
        if (f > now) return emptyMap()
        val es = entries.filter { it.startAt < t && (it.endAt ?: now) > f }
        return totals(resolve(es, f, t, now))
    }
}

enum class PeriodType(val label: String) { WEEK("Tydzień"), MONTH("Miesiąc"), YEAR("Rok") }

data class Period(val type: PeriodType, val start: LocalDate) {
    val end: LocalDate
        get() = when (type) {
            PeriodType.WEEK -> start.plusWeeks(1)
            PeriodType.MONTH -> start.plusMonths(1)
            PeriodType.YEAR -> start.plusYears(1)
        }

    fun prev() = Period(
        type, when (type) {
            PeriodType.WEEK -> start.minusWeeks(1)
            PeriodType.MONTH -> start.minusMonths(1)
            PeriodType.YEAR -> start.minusYears(1)
        }
    )

    fun next() = Period(type, end)

    fun label(): String = when (type) {
        PeriodType.WEEK -> {
            val last = end.minusDays(1)
            val f = DateTimeFormatter.ofPattern("d MMM", Fmt.PL)
            if (start.month == last.month) "${start.dayOfMonth}–${last.format(f)} ${last.year}"
            else "${start.format(f)} – ${last.format(f)} ${last.year}"
        }
        PeriodType.MONTH -> start.format(DateTimeFormatter.ofPattern("LLLL yyyy", Fmt.PL))
            .replaceFirstChar { it.titlecase(Fmt.PL) }
        PeriodType.YEAR -> start.year.toString()
    }

    companion object {
        fun current(type: PeriodType): Period {
            val t = LocalDate.now()
            return Period(
                type, when (type) {
                    PeriodType.WEEK -> t.with(DayOfWeek.MONDAY)
                    PeriodType.MONTH -> t.withDayOfMonth(1)
                    PeriodType.YEAR -> t.withDayOfYear(1)
                }
            )
        }
    }
}

data class StatsResult(
    val totals: Map<Long, Long>,
    val prevTotals: Map<Long, Long>,
    val days: Int,
    val prevDays: Int,
    /** Słupki wykresu: dla tygodnia/miesiąca dni, dla roku miesiące (średnio na dzień). */
    val buckets: List<Map<Long, Long>>,
    val labels: List<String>,
)

object Stats {
    private val weekLabels = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")
    private val monthLabels = listOf("S", "L", "M", "K", "M", "C", "L", "S", "W", "P", "L", "G")

    private fun sum(maps: List<Map<Long, Long>>): Map<Long, Long> {
        val m = HashMap<Long, Long>()
        maps.forEach { mm -> mm.forEach { (k, v) -> m[k] = (m[k] ?: 0L) + v } }
        return m
    }

    fun compute(entries: List<Entry>, p: Period, now: Long): StatsResult {
        fun days(a: LocalDate, b: LocalDate): List<Pair<LocalDate, Map<Long, Long>>> {
            val out = mutableListOf<Pair<LocalDate, Map<Long, Long>>>()
            var d = a
            while (d < b) {
                out += d to Timeline.dayTotals(entries, d, now)
                d = d.plusDays(1)
            }
            return out
        }

        val cur = days(p.start, p.end)
        val prevP = p.prev()
        val prev = days(prevP.start, prevP.end)
        val trackedDays = cur.count { it.second.isNotEmpty() }.coerceAtLeast(1)
        val prevTrackedDays = prev.count { it.second.isNotEmpty() }.coerceAtLeast(1)

        val buckets: List<Map<Long, Long>>
        val labels: List<String>
        if (p.type == PeriodType.YEAR) {
            val byMonth = cur.groupBy { it.first.monthValue }
            buckets = (1..12).map { m ->
                val list = byMonth[m].orEmpty()
                val n = list.count { it.second.isNotEmpty() }.coerceAtLeast(1)
                sum(list.map { it.second }).mapValues { it.value / n }
            }
            labels = monthLabels
        } else {
            buckets = cur.map { it.second }
            labels = if (p.type == PeriodType.WEEK) weekLabels else cur.map { it.first.dayOfMonth.toString() }
        }

        return StatsResult(
            totals = sum(cur.map { it.second }),
            prevTotals = sum(prev.map { it.second }),
            days = trackedDays,
            prevDays = prevTrackedDays,
            buckets = buckets,
            labels = labels,
        )
    }
}

data class Cluster(val lat: Double, val lng: Double, val visits: Int, val totalMs: Long, val lastAt: Long)

object Clusters {
    fun build(stays: List<Entry>, now: Long): List<Cluster> {
        class Acc(var lat: Double, var lng: Double, var n: Int, var total: Long, var last: Long)

        val acc = mutableListOf<Acc>()
        for (e in stays.sortedBy { it.startAt }) {
            val lat = e.lat ?: continue
            val lng = e.lng ?: continue
            val dur = (e.endAt ?: now) - e.startAt
            val c = acc.firstOrNull { Geo.distance(it.lat, it.lng, lat, lng) < 150 }
            if (c == null) {
                acc += Acc(lat, lng, 1, dur, e.startAt)
            } else {
                c.lat = (c.lat * c.n + lat) / (c.n + 1)
                c.lng = (c.lng * c.n + lng) / (c.n + 1)
                c.n += 1
                c.total += dur
                c.last = max(c.last, e.startAt)
            }
        }
        return acc.map { Cluster(it.lat, it.lng, it.n, it.total, it.last) }.sortedByDescending { it.totalMs }
    }
}

object CsvExport {
    suspend fun write(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = LifeApp.instance.db
        val cats = db.categories().getAll().associateBy { it.id }
        val places = db.places().getAll().associateBy { it.id }
        val entries = db.entries().all()
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun esc(s: String?) = (s ?: "").replace(";", ",").replace("\n", " ")
        ctx.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
            w.write("﻿")
            w.write("start;koniec;minuty;kategoria;miejsce;zrodlo;notatka\n")
            val now = System.currentTimeMillis()
            for (e in entries) {
                val end = e.endAt ?: now
                val start = Instant.ofEpochMilli(e.startAt).atZone(Fmt.zone).format(iso)
                val stop = Instant.ofEpochMilli(end).atZone(Fmt.zone).format(iso)
                w.write(
                    listOf(
                        start, stop, ((end - e.startAt) / 60_000).toString(),
                        esc(cats[e.categoryId]?.name), esc(e.placeId?.let { places[it]?.name }),
                        e.source, esc(e.note),
                    ).joinToString(";") + "\n"
                )
            }
        }
        entries.size
    }
}

/** Dodatkowe analizy do ekranu Trendy. */
object Trends {
    /** Typowy dzień: dla każdej z 24 godzin suma czasu kategorii ze wszystkich dni okresu. */
    fun typicalDay(entries: List<Entry>, start: LocalDate, end: LocalDate, now: Long): List<Map<Long, Long>> {
        val hours = List(24) { HashMap<Long, Long>() }
        var d = start
        val today = LocalDate.now()
        while (d < end && d <= today) {
            val f = Fmt.dayStart(d)
            val t = Fmt.dayStart(d.plusDays(1))
            val es = entries.filter { it.startAt < t && (it.endAt ?: now) > f }
            for (s in Timeline.resolve(es, f, t, now)) {
                var a = s.start
                while (a < s.end) {
                    val z = Instant.ofEpochMilli(a).atZone(Fmt.zone)
                    val hourEnd = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                    val b = min(hourEnd, s.end)
                    val m = hours[z.hour]
                    m[s.entry.categoryId] = (m[s.entry.categoryId] ?: 0L) + (b - a)
                    a = b
                }
            }
            d = d.plusDays(1)
        }
        return hours
    }

    data class Night(val day: LocalDate, val start: Long, val end: Long)

    /** Noce w okresie (przypisane do dnia pobudki): najwcześniejszy początek i najpóźniejszy koniec snu. */
    fun nights(entries: List<Entry>, sleepCat: Long, start: LocalDate, end: LocalDate, now: Long): List<Night> =
        entries.filter { it.categoryId == sleepCat && (it.endAt ?: now) - it.startAt >= 2 * 3_600_000L }
            .groupBy { Fmt.toLocalDate(it.endAt ?: now) }
            .filterKeys { it >= start && it < end }
            .map { (d, l) -> Night(d, l.minOf { it.startAt }, l.maxOf { it.endAt ?: now }) }
            .sortedBy { it.day }

    data class Record(val title: String, val value: String, val day: LocalDate, val iconKey: String?, val color: Int?)

    fun records(
        entries: List<Entry>, cats: Map<Long, Category>, steps: Map<LocalDate, Long>,
        start: LocalDate, end: LocalDate, now: Long,
    ): List<Record> {
        val today = LocalDate.now()
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it < end && it <= today }
            .associateWith { Timeline.dayTotals(entries, it, now) }
            .filterValues { it.isNotEmpty() }
        if (days.isEmpty()) return emptyList()
        val out = mutableListOf<Record>()
        fun cat(kind: String) = cats.values.firstOrNull { it.kind == kind }
        fun best(c: Category?, title: String, lowest: Boolean = false) {
            if (c == null) return
            val vals = days.mapNotNull { (d, m) -> m[c.id]?.let { d to it } }.filter { it.second > 0 }
            val r = (if (lowest) vals.minByOrNull { it.second } else vals.maxByOrNull { it.second }) ?: return
            out += Record(title, Fmt.durationShort(r.second), r.first, c.icon, c.color)
        }
        best(cat(Kind.SLEEP), "Najdłuższy sen")
        if (days.size >= 3) best(cat(Kind.SLEEP), "Najkrótszy sen", lowest = true)
        best(cat(Kind.WORK), "Najdłuższy dzień pracy")
        best(cat(Kind.HOME), "Najwięcej czasu w domu")
        val activeKinds = setOf(Kind.WALK, Kind.RUN, Kind.BIKE, Kind.WORKOUT)
        val active = days.map { (d, m) -> d to m.filterKeys { cats[it]?.kind in activeKinds }.values.sum() }
            .maxByOrNull { it.second }
        if (active != null && active.second > 0) {
            out += Record("Najaktywniejszy dzień", Fmt.durationShort(active.second), active.first, "run", 0xFF34C759.toInt())
        }
        steps.filterKeys { it >= start && it < end }.maxByOrNull { it.value }?.let {
            if (it.value > 0) out += Record("Najwięcej kroków", Fmt.steps(it.value), it.key, "walk", 0xFF34C759.toInt())
        }
        return out
    }
}
