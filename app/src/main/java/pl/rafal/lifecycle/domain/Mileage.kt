package pl.rafal.lifecycle.domain

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Jeden przejazd służbowy w ewidencji przebiegu pojazdu. */
data class MileageTrip(
    val day: LocalDate,
    val start: Long,
    val end: Long,
    val from: String,
    val to: String,
    val purpose: String,
    val km: Double,
    val driving: Boolean,
)

data class MileageMonth(val month: YearMonth, val trips: List<MileageTrip>, val rate: Double) {
    val km: Double get() = trips.sumOf { it.km }
    val amount: Double get() = trips.sumOf { Math.round(it.km * 10) / 10.0 * rate }
}

/**
 * Ewidencja przebiegu pojazdu (kilometrówka): przejazdy samochodem w czasie pracy,
 * ze skąd–dokąd, celem i kwotą wg stawki za km.
 */
object Mileage {
    /** Stawki z rozporządzenia (2023): auto > 900 cm³ 1,15 zł; ≤ 900 cm³ 0,89 zł. */
    fun defaultRate(cc: Int): Double = if (cc in 1..900) 0.89 else 1.15

    fun rate(ctx: Context): Double {
        val p = Prefs(ctx)
        return if (p.mileageRate > 0f) p.mileageRate.toDouble() else defaultRate(p.carEngineCc)
    }

    suspend fun month(ctx: Context, ym: YearMonth): MileageMonth = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val prefs = Prefs(ctx)
        val start = ym.atDay(1)
        val end = ym.plusMonths(1).atDay(1)
        val from = Fmt.dayStart(start)
        val to = Fmt.dayStart(end)
        val entries = app.repo.getRange(from - 12 * 3_600_000L, to + 12 * 3_600_000L).sortedBy { it.startAt }
        val cats = app.db.categories().getAll().associateBy { it.id }
        val places = app.db.places().getAll().associateBy { it.id }
        val cars = app.db.cars().range(from, to)
        val now = System.currentTimeMillis()
        val carKinds = setOf(Kind.TRAVEL, Kind.COMMUTE)
        val today = LocalDate.now()
        val out = mutableListOf<MileageTrip>()

        suspend fun label(e: Entry?): String {
            e ?: return "—"
            e.placeId?.let { places[it]?.name }?.let { return it }
            val lat = e.lat ?: return cats[e.categoryId]?.name ?: "—"
            val lng = e.lng ?: return "—"
            return FieldWork.address(ctx, lat, lng) ?: cats[e.categoryId]?.name ?: "—"
        }

        var d = start
        while (d < end && d <= today) {
            val wd = WorkTime.day(ctx, entries, cats, d, now)
            if (wd != null) {
                val dayMoves = entries.filter {
                    it.source == Source.AUTO_MOVE && (it.distanceM ?: 0.0) > 300 &&
                        cats[Commutes.info(it.id)?.originalCategoryId ?: SchoolRuns.info(it.id)?.originalCategoryId ?: it.categoryId]?.kind in carKinds &&
                        Fmt.toLocalDate(it.startAt) == d
                }
                for (m in dayMoves) {
                    val mEnd = m.endAt ?: now
                    val inside = m.startAt >= wd.start - 60_000L && mEnd <= wd.end + 60_000L
                    val toWork = prefs.mileageHomeTrips && wd.field && kotlin.math.abs(mEnd - wd.start) < 10 * 60_000L
                    val fromWork = prefs.mileageHomeTrips && wd.field && kotlin.math.abs(m.startAt - wd.end) < 10 * 60_000L
                    if (!inside && !toWork && !fromWork) continue
                    val before = entries.lastOrNull { it.source == Source.AUTO_STAY && (it.endAt ?: 0) <= m.startAt + 60_000L }
                    val after = entries.firstOrNull { it.source == Source.AUTO_STAY && it.startAt >= mEnd - 60_000L }
                    val purpose = when (cats[after?.categoryId]?.kind) {
                        Kind.FIELD -> "wizyta u klienta"
                        Kind.WORK -> "dojazd do miejsca pracy"
                        Kind.HOME -> "powrót"
                        else -> cats[after?.categoryId]?.name?.lowercase() ?: "przejazd służbowy"
                    }
                    val driving = cars.any { c -> c.startAt < mEnd && (c.endAt ?: now) > m.startAt }
                    out += MileageTrip(d, m.startAt, mEnd, label(before), label(after), purpose, (m.distanceM ?: 0.0) / 1000, driving)
                }
            }
            d = d.plusDays(1)
        }
        MileageMonth(ym, out, rate(ctx))
    }

    private fun km(v: Double) = String.format(Fmt.PL, "%.1f", v)
    private fun zl(v: Double) = String.format(Fmt.PL, "%.2f zł", v)

    fun csv(m: MileageMonth): String = buildString {
        append("﻿")
        append("lp;data;skad;dokad;cel;km;stawka;kwota\n")
        m.trips.forEachIndexed { i, t ->
            val k = Math.round(t.km * 10) / 10.0
            append(listOf(
                "${i + 1}", t.day.toString(), t.from.replace(";", ","), t.to.replace(";", ","), t.purpose,
                km(k), String.format(Fmt.PL, "%.2f", m.rate), String.format(Fmt.PL, "%.2f", k * m.rate),
            ).joinToString(";"))
            append('\n')
        }
        append(";;;;RAZEM;${km(m.km)};;${String.format(Fmt.PL, "%.2f", m.amount)}\n")
    }

    suspend fun csvFile(ctx: Context, m: MileageMonth): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "kilometrowka-${m.month}.csv").apply { writeText(csv(m)) }
    }

    /** PDF w układzie ewidencji przebiegu pojazdu (A4 poziomo). */
    suspend fun pdfFile(ctx: Context, m: MileageMonth): File = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        val doc = PdfDocument()
        val w = 842
        val h = 595
        val title = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val head = Paint().apply { textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val text = Paint().apply { textSize = 9f; isAntiAlias = true }
        val line = Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 0.6f }
        val cols = listOf(30f, 55f, 110f, 300f, 490f, 640f, 690f, 750f)
        val headers = listOf("Lp.", "Data", "Skąd", "Dokąd", "Cel wyjazdu", "Km", "Stawka", "Kwota")
        var page: PdfDocument.Page? = null
        var pageNo = 0
        var y = 0f
        fun cut(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val c = page!!.canvas
            y = 40f
            if (pageNo == 1) {
                c.drawText("Ewidencja przebiegu pojazdu — ${WorkTime.monthTitle(m.month)}", 30f, y, title)
                y += 18f
                c.drawText(
                    "Imię i nazwisko: ${p.carOwner.ifBlank { "…………………………" }}    Nr rejestracyjny: ${p.carPlate.ifBlank { "………………" }}" +
                        "    Pojemność silnika: ${if (p.carEngineCc > 0) "${p.carEngineCc} cm³" else "………"}",
                    30f, y, text,
                )
                y += 22f
            }
            headers.forEachIndexed { i, s -> c.drawText(s, cols[i], y, head) }
            y += 5f
            c.drawLine(30f, y, w - 30f, y, line)
            y += 13f
        }
        newPage()
        val dF = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        m.trips.forEachIndexed { i, t ->
            if (y > h - 70) newPage()
            val c = page!!.canvas
            val k = Math.round(t.km * 10) / 10.0
            listOf(
                "${i + 1}", t.day.format(dF), cut(t.from, 36), cut(t.to, 36), cut(t.purpose, 28),
                km(k), String.format(Fmt.PL, "%.2f", m.rate), zl(k * m.rate),
            ).forEachIndexed { j, s -> c.drawText(s, cols[j], y, text) }
            y += 13f
        }
        if (y > h - 90) newPage()
        val c = page!!.canvas
        c.drawLine(30f, y - 6f, w - 30f, y - 6f, line)
        y += 8f
        c.drawText("Razem: ${km(m.km)} km × ${String.format(Fmt.PL, "%.2f", m.rate)} zł = ${zl(m.amount)}", 30f, y, head)
        y += 40f
        c.drawText("……………………………………", w - 230f, y, text)
        c.drawText("podpis", w - 180f, y + 12f, text)
        page?.let { doc.finishPage(it) }
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "kilometrowka-${m.month}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        f
    }
}
