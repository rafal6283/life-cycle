package pl.rafal.lifecycle.domain

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Geo
import pl.rafal.lifecycle.util.Prefs
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Jeden dzień pracy: od pierwszego do ostatniego pobytu w pracy / u klienta. */
data class WorkDay(
    val day: LocalDate,
    val start: Long,
    val end: Long,
    /** Czas w pracy i u klientów. */
    val atWorkMs: Long,
    /** Przejazdy między miejscami pracy (liczone do czasu pracy, gdy włączone). */
    val travelMs: Long,
    /** Pozostały czas między początkiem a końcem pracy (obiad, sprawy prywatne, brak danych). */
    val breakMs: Long,
    val normMs: Long,
    val km: Double,
    val field: Boolean,
) {
    val workedMs: Long get() = atWorkMs + travelMs
    val overtimeMs: Long get() = workedMs - normMs
}

/** Rozliczenie jednego klienta (miejsca wizyt) w miesiącu. */
data class ClientTotal(
    val key: String,
    val placeId: Long?,
    val lat: Double,
    val lng: Double,
    var name: String,
    val visits: Int,
    val visitMs: Long,
    val travelMs: Long,
    val km: Double,
    val days: List<LocalDate>,
)

data class WorkMonth(
    val month: YearMonth,
    val days: List<WorkDay>,
    val normMs: Long,
    val clients: List<ClientTotal>,
) {
    val workedMs: Long get() = days.sumOf { it.workedMs }
    val balanceMs: Long get() = workedMs - normMs
}

object WorkTime {
    private val workKinds = setOf(Kind.WORK, Kind.FIELD)
    val moveKinds = setOf(
        Kind.TRAVEL, Kind.WALK, Kind.RUN, Kind.BIKE, Kind.COMMUTE, Kind.STOP,
        Kind.TRAIN, Kind.TRANSIT, Kind.SCOOTER,
    )

    fun normFor(ctx: Context, day: LocalDate): Long {
        val p = Prefs(ctx)
        return if (day.dayOfWeek.value in p.workDays) p.workNormMin * 60_000L else 0L
    }

    fun day(ctx: Context, entries: List<Entry>, cats: Map<Long, Category>, day: LocalDate, now: Long): WorkDay? {
        val from = Fmt.dayStart(day)
        val to = Fmt.dayStart(day.plusDays(1))
        if (from > now) return null
        val segs = Timeline.resolve(entries.filter { it.startAt < to && (it.endAt ?: now) > from }, from, to, now)
        val work = segs.filter { cats[it.entry.categoryId]?.kind in workKinds }
        if (work.isEmpty() || work.sumOf { it.duration } < 15 * 60_000L) return null
        val start = work.first().start
        val end = work.last().end
        val countTravel = Prefs(ctx).workCountTravel
        var atWork = 0L
        var travel = 0L
        var km = 0.0
        for (s in segs) {
            if (s.end <= start || s.start >= end) continue
            val kind = cats[s.entry.categoryId]?.kind
            when {
                kind in workKinds -> atWork += s.duration
                countTravel && kind in moveKinds && kind != Kind.COMMUTE -> {
                    travel += s.duration
                    km += (s.entry.distanceM ?: 0.0) * fraction(s)
                }
            }
        }
        val field = work.any { cats[it.entry.categoryId]?.kind == Kind.FIELD }
        return WorkDay(
            day, start, end, atWork, travel, (end - start - atWork - travel).coerceAtLeast(0L),
            normFor(ctx, day), km, field,
        )
    }

    /** Część wpisu mieszcząca się w segmencie (dystans rozkładany proporcjonalnie). */
    private fun fraction(s: Segment): Double {
        val e = s.entry
        val len = (e.endAt ?: s.end) - e.startAt
        return if (len <= 0) 1.0 else (s.duration.toDouble() / len).coerceIn(0.0, 1.0)
    }

    suspend fun month(ctx: Context, ym: YearMonth): WorkMonth = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val start = ym.atDay(1)
        val end = ym.plusMonths(1).atDay(1)
        val entries = app.repo.getRange(Fmt.dayStart(start), Fmt.dayStart(end))
        val cats = app.db.categories().getAll().associateBy { it.id }
        val places = app.db.places().getAll().associateBy { it.id }
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it < end && it <= today }
            .mapNotNull { day(ctx, entries, cats, it, now) }.toList()
        // Norma liczona do dziś (bieżący miesiąc) — żeby saldo nie było ujemne z góry.
        val norm = generateSequence(start) { it.plusDays(1) }.takeWhile { it < end && it <= today }
            .sumOf { normFor(ctx, it) }
        WorkMonth(ym, days, norm, clients(ctx, entries, cats, places, start, end, now))
    }

    /** Wizyty w kategorii „u klienta” pogrupowane wg zapisanego miejsca albo okolicy (100 m). */
    suspend fun clients(
        ctx: Context, entries: List<Entry>, cats: Map<Long, Category>, places: Map<Long, Place>,
        start: LocalDate, end: LocalDate, now: Long,
    ): List<ClientTotal> {
        val from = Fmt.dayStart(start)
        val to = Fmt.dayStart(end)
        val sorted = entries.sortedBy { it.startAt }
        val visits = sorted.filter {
            cats[it.categoryId]?.kind == Kind.FIELD && it.startAt >= from && it.startAt < to && it.lat != null
        }
        class Acc(val placeId: Long?, var lat: Double, var lng: Double) {
            var n = 0; var ms = 0L; var travel = 0L; var km = 0.0
            val days = sortedSetOf<LocalDate>()
        }
        val accs = mutableListOf<Acc>()
        for (v in visits) {
            val lat = v.lat ?: continue
            val lng = v.lng ?: continue
            val acc = if (v.placeId != null) {
                accs.firstOrNull { it.placeId == v.placeId } ?: Acc(v.placeId, lat, lng).also { accs += it }
            } else {
                accs.firstOrNull { it.placeId == null && Geo.distance(it.lat, it.lng, lat, lng) < 100 }
                    ?: Acc(null, lat, lng).also { accs += it }
            }
            acc.n++
            acc.ms += (v.endAt ?: now) - v.startAt
            acc.days += Fmt.toLocalDate(v.startAt)
            // Dojazd do klienta: przejazdy tuż przed wizytą (od poprzedniego pobytu).
            val i = sorted.indexOf(v)
            var j = i - 1
            var t = v.startAt
            while (j >= 0) {
                val e = sorted[j]
                val kind = cats[e.categoryId]?.kind
                val eEnd = e.endAt ?: now
                if (kind !in moveKinds || t - eEnd > 3 * 60_000L) break
                acc.travel += eEnd - e.startAt
                acc.km += e.distanceM ?: 0.0
                t = e.startAt
                j--
            }
        }
        return accs.map { a ->
            val name = a.placeId?.let { places[it]?.name }
                ?: FieldWork.address(ctx, a.lat, a.lng)
                ?: String.format(Fmt.PL, "%.4f, %.4f", a.lat, a.lng)
            ClientTotal(
                key = a.placeId?.let { "p$it" } ?: String.format(java.util.Locale.US, "%.4f,%.4f", a.lat, a.lng),
                placeId = a.placeId, lat = a.lat, lng = a.lng, name = name, visits = a.n, visitMs = a.ms,
                travelMs = a.travel, km = a.km, days = a.days.toList(),
            )
        }.sortedByDescending { it.visitMs }
    }

    // ------------------------------------------------------------------ eksport

    private val dayF = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun hm(ms: Long): String {
        val neg = ms < 0
        val m = kotlin.math.abs(ms) / 60_000
        return (if (neg) "-" else "") + "%d:%02d".format(m / 60, m % 60)
    }

    fun monthTitle(ym: YearMonth): String =
        ym.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Fmt.PL)).replaceFirstChar { it.titlecase(Fmt.PL) }

    fun csv(m: WorkMonth): String = buildString {
        append("﻿")
        append("data;dzien;od;do;w_pracy;przejazdy;przerwy;przepracowano;norma;nadgodziny;km\n")
        for (d in m.days) {
            append(listOf(
                d.day.format(dayF), d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Fmt.PL),
                Fmt.time(d.start), Fmt.time(d.end), hm(d.atWorkMs), hm(d.travelMs), hm(d.breakMs),
                hm(d.workedMs), hm(d.normMs), hm(d.overtimeMs), String.format(Fmt.PL, "%.1f", d.km / 1000),
            ).joinToString(";"))
            append('\n')
        }
        append("RAZEM;;;;;;;${hm(m.workedMs)};${hm(m.normMs)};${hm(m.balanceMs)};${String.format(Fmt.PL, "%.1f", m.days.sumOf { it.km } / 1000)}\n")
        if (m.clients.isNotEmpty()) {
            append("\nklient;wizyty;czas_wizyt;dojazdy;km;dni\n")
            for (c in m.clients) {
                append(listOf(
                    c.name.replace(";", ","), c.visits.toString(), hm(c.visitMs), hm(c.travelMs),
                    String.format(Fmt.PL, "%.1f", c.km / 1000), c.days.joinToString(" ") { it.dayOfMonth.toString() },
                ).joinToString(";"))
                append('\n')
            }
        }
    }

    suspend fun csvFile(ctx: Context, m: WorkMonth): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "czas-pracy-${m.month}.csv").apply { writeText(csv(m)) }
    }

    /** Raport miesięczny w PDF (A4) do wysłania lub wydruku. */
    suspend fun pdfFile(ctx: Context, m: WorkMonth): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        val head = Paint().apply { textSize = 9.5f; isFakeBoldText = true; isAntiAlias = true }
        val text = Paint().apply { textSize = 9.5f; isAntiAlias = true }
        val small = Paint().apply { textSize = 8.5f; isAntiAlias = true; color = 0xFF666666.toInt() }
        val line = Paint().apply { color = 0xFFDDDDDD.toInt(); strokeWidth = 0.7f }
        val cols = listOf(40f, 120f, 165f, 210f, 265f, 320f, 375f, 440f, 500f)
        val headers = listOf("Data", "Od", "Do", "W pracy", "Przejazdy", "Przerwy", "Razem", "Nadgodz.", "km")
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = 50f
            val c = page!!.canvas
            if (pageNo == 1) {
                c.drawText("Ewidencja czasu pracy — ${monthTitle(m.month)}", 40f, y, title)
                y += 18f
                c.drawText("Wygenerowano w aplikacji Doba ${Fmt.dateTime(System.currentTimeMillis())} na podstawie lokalizacji telefonu.", 40f, y, small)
                y += 24f
            }
            headers.forEachIndexed { i, s -> c.drawText(s, cols[i], y, head) }
            y += 6f
            c.drawLine(40f, y, w - 40f, y, line)
            y += 13f
        }
        newPage()
        val dF = DateTimeFormatter.ofPattern("EEE d.MM", Fmt.PL)
        for (d in m.days) {
            if (y > h - 60) newPage()
            val c = page!!.canvas
            val row = listOf(
                d.day.format(dF), Fmt.time(d.start), Fmt.time(d.end), hm(d.atWorkMs), hm(d.travelMs),
                hm(d.breakMs), hm(d.workedMs), hm(d.overtimeMs), String.format(Fmt.PL, "%.1f", d.km / 1000),
            )
            row.forEachIndexed { i, s -> c.drawText(s, cols[i], y, text) }
            y += 14f
        }
        if (y > h - 120) newPage()
        var c = page!!.canvas
        c.drawLine(40f, y - 8f, w - 40f, y - 8f, line)
        y += 6f
        c.drawText("Dni pracy: ${m.days.size}    Przepracowano: ${hm(m.workedMs)}    Norma: ${hm(m.normMs)}    Saldo: ${hm(m.balanceMs)}", 40f, y, head)
        y += 30f
        if (m.clients.isNotEmpty()) {
            if (y > h - 100) { newPage(); c = page!!.canvas }
            c.drawText("Klienci", 40f, y, title.apply { textSize = 14f })
            y += 18f
            val cc = listOf(40f, 300f, 350f, 415f, 480f)
            listOf("Klient", "Wizyty", "Czas", "Dojazdy", "km").forEachIndexed { i, s -> c.drawText(s, cc[i], y, head) }
            y += 16f
            for (cl in m.clients) {
                if (y > h - 50) { newPage(); c = page!!.canvas }
                val name = if (cl.name.length > 48) cl.name.take(47) + "…" else cl.name
                listOf(name, cl.visits.toString(), hm(cl.visitMs), hm(cl.travelMs), String.format(Fmt.PL, "%.1f", cl.km / 1000))
                    .forEachIndexed { i, s -> c.drawText(s, cc[i], y, text) }
                y += 14f
            }
        }
        page?.let { doc.finishPage(it) }
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "czas-pracy-${m.month}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        f
    }
}
