package pl.rafal.lifecycle.domain

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Praca w terenie (np. serwis u klientów): w ustawione dni i godziny nowe, nienazwane pobyty to wizyty
 * u klienta. Raport dnia: wizyty, przejazdy między nimi i kilometry; kilometrówka miesięczna z eksportem CSV.
 */
object FieldWork {
    fun activeAt(ctx: Context, t: Long): Boolean {
        val p = Prefs(ctx)
        if (!p.fieldWork) return false
        val z = Instant.ofEpochMilli(t).atZone(Fmt.zone)
        if (z.dayOfWeek.value !in p.fieldDays) return false
        val m = z.hour * 60 + z.minute
        return m >= p.fieldStart && m < p.fieldEnd
    }

    data class Visit(val entry: Entry, val start: Long, val end: Long, val name: String?, val lat: Double?, val lng: Double?)

    data class Trip(
        val start: Long, val end: Long, val distanceM: Double?,
        val from: String, val to: String, val toVisit: Visit?,
        /** Krótkie postoje po drodze (sklep, stacja). */
        val stops: Int = 0,
    )

    data class DayReport(
        val day: LocalDate,
        val visits: List<Visit>,
        val trips: List<Trip>,
        val workStart: Long?,
        val workEnd: Long?,
    ) {
        val visitMs get() = visits.sumOf { it.end - it.start }
        val driveMs get() = trips.sumOf { it.end - it.start }
        val km get() = trips.mapNotNull { it.distanceM }.sum() / 1000.0
        val spanMs get() = if (workStart != null && workEnd != null) workEnd - workStart else 0L
    }

    // ------------------------------------------------------------------ adresy

    private fun geoKey(lat: Double, lng: Double) = String.format(Locale.US, "%.4f,%.4f", lat, lng)

    /** Adres z współrzędnych (Geocoder systemu), z pamięcią podręczną — bez sieci zwraca null. */
    suspend fun address(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("geocode", Context.MODE_PRIVATE)
        val k = geoKey(lat, lng)
        sp.getString(k, null)?.let { return@withContext it }
        if (!Geocoder.isPresent()) return@withContext null
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Fmt.PL).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@withContext null
            val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null }
            val text = listOfNotNull(street ?: a.featureName, a.locality ?: a.subAdminArea).distinct().joinToString(", ")
            if (text.isNotBlank()) sp.edit().putString(k, text).apply()
            text.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ raport dnia

    suspend fun report(ctx: Context, day: LocalDate): DayReport = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val from = Fmt.dayStart(day)
        val to = Fmt.dayStart(day.plusDays(1))
        val now = System.currentTimeMillis()
        val cats = app.db.categories().getAll().associateBy { it.id }
        val places = app.db.places().getAll().associateBy { it.id }
        val auto = app.repo.getRange(from, to)
            .filter { it.source == Source.AUTO_STAY || it.source == Source.AUTO_MOVE }
            .sortedBy { it.startAt }
        fun isField(e: Entry) = e.source == Source.AUTO_STAY && cats[e.categoryId]?.kind == Kind.FIELD
        fun isStop(e: Entry) = e.source == Source.AUTO_STAY && cats[e.categoryId]?.kind == Kind.STOP
        fun label(e: Entry?): String {
            if (e == null) return "—"
            e.placeId?.let { places[it]?.name }?.let { return it }
            e.note?.takeIf { it.isNotBlank() }?.let { return it }
            return cats[e.categoryId]?.name ?: "—"
        }
        val visits = auto.filter { isField(it) }.map {
            Visit(it, maxOf(it.startAt, from), minOf(it.endAt ?: now, to), it.placeId?.let { id -> places[id]?.name } ?: it.note, it.lat, it.lng)
        }
        if (visits.isEmpty()) return@withContext DayReport(day, emptyList(), emptyList(), null, null)

        val firstIdx = auto.indexOfFirst { isField(it) }
        val lastIdx = auto.indexOfLast { isField(it) }
        // Wyjazd: koniec ostatniego pobytu przed pierwszą wizytą; powrót: początek pierwszego pobytu po ostatniej.
        val before = auto.subList(0, firstIdx).lastOrNull { it.source == Source.AUTO_STAY && !isStop(it) }
        val after = auto.subList(lastIdx + 1, auto.size).firstOrNull { it.source == Source.AUTO_STAY && !isStop(it) }
        val workStart = maxOf(before?.endAt ?: visits.first().start, from)
        val workEnd = minOf(after?.startAt ?: visits.last().end, to)

        val trips = mutableListOf<Trip>()
        var prevStay: Entry? = before
        var acc: MutableList<Entry> = mutableListOf()
        for (e in auto) {
            if (e.startAt < workStart - 60_000L || e.startAt > workEnd + 60_000L) {
                if (e.source == Source.AUTO_STAY && e.startAt < workStart) prevStay = e
                continue
            }
            if (e.source == Source.AUTO_MOVE || isStop(e)) {
                acc.add(e)
            } else {
                if (acc.isNotEmpty()) {
                    val dist = acc.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
                    trips += Trip(
                        acc.first().startAt, acc.last().endAt ?: now, dist,
                        label(prevStay), label(e), visits.firstOrNull { it.entry.id == e.id },
                        acc.count { isStop(it) },
                    )
                    acc = mutableListOf()
                }
                prevStay = e
            }
        }
        if (acc.isNotEmpty()) {
            val dist = acc.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
            trips += Trip(acc.first().startAt, acc.last().endAt ?: now, dist, label(prevStay), "—", null)
        }
        DayReport(day, visits, trips, workStart, workEnd)
    }

    /** Oznacza wcześniejsze nienazwane pobyty z godzin pracy jako wizyty. Zwraca ich liczbę. */
    suspend fun applyToPast(ctx: Context, days: Int): Int {
        val app = ctx.applicationContext as LifeApp
        val field = app.repo.categoryByKind(Kind.FIELD)
        val since = Fmt.dayStart(LocalDate.now().minusDays(days.toLong()))
        var n = 0
        for (e in app.db.entries().unknownStaysSince(since)) {
            if (activeAt(ctx, e.startAt)) {
                app.db.entries().update(e.copy(categoryId = field.id))
                n++
            }
        }
        return n
    }

    // ------------------------------------------------------------------ kilometrówka

    data class MonthRow(val day: LocalDate, val visits: Int, val km: Double, val spanMs: Long, val visitMs: Long)

    suspend fun month(ctx: Context, ym: YearMonth): List<MonthRow> {
        val out = mutableListOf<MonthRow>()
        var d = ym.atDay(1)
        val today = LocalDate.now()
        while (d.month == ym.month && d <= today) {
            val r = report(ctx, d)
            if (r.visits.isNotEmpty()) out += MonthRow(d, r.visits.size, r.km, r.spanMs, r.visitMs)
            d = d.plusDays(1)
        }
        return out
    }

    /** CSV (Excel, średnik): każdy przejazd służbowy z godzinami, skąd–dokąd i kilometrami. */
    suspend fun exportCsv(ctx: Context, uri: Uri, ym: YearMonth): Int = withContext(Dispatchers.IO) {
        val rows = StringBuilder("﻿data;wyjazd;przyjazd;skąd;dokąd;km;cel / klient\n")
        var n = 0
        var totalKm = 0.0
        var d = ym.atDay(1)
        val today = LocalDate.now()
        while (d.month == ym.month && d <= today) {
            val r = report(ctx, d)
            for (t in r.trips) {
                val km = (t.distanceM ?: 0.0) / 1000
                totalKm += km
                val v = t.toVisit
                val goal = when {
                    v == null -> "powrót"
                    v.name != null -> v.name
                    v.lat != null && v.lng != null -> address(ctx, v.lat, v.lng) ?: "wizyta u klienta"
                    else -> "wizyta u klienta"
                }
                fun esc(s: String) = s.replace(";", ",").replace("\n", " ")
                rows.append(d).append(';').append(Fmt.time(t.start)).append(';').append(Fmt.time(t.end)).append(';')
                    .append(esc(t.from)).append(';').append(esc(t.to)).append(';')
                    .append(String.format(Fmt.PL, "%.1f", km)).append(';').append(esc(goal)).append('\n')
                n++
            }
            d = d.plusDays(1)
        }
        rows.append(";;;;RAZEM;").append(String.format(Fmt.PL, "%.1f", totalKm)).append(";\n")
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(rows.toString().toByteArray(Charsets.UTF_8)) }
        n
    }

    // ------------------------------------------------------------------ postoje, „to nie klient”, częste miejsca

    /** Wizyty krótsze niż ustawione minimum (bez zapisanego miejsca) pokazują się jako „Postój w trasie”. */
    fun applyStops(entries: List<Entry>, categories: List<pl.rafal.lifecycle.data.Category>): List<Entry> {
        val field = categories.firstOrNull { it.kind == Kind.FIELD } ?: return entries
        val stop = categories.firstOrNull { it.kind == Kind.STOP } ?: return entries
        val minMs = try {
            Prefs(LifeApp.instance).fieldMinMinutes * 60_000L
        } catch (e: Exception) {
            15 * 60_000L
        }
        if (minMs <= 0) return entries
        return entries.map { e ->
            val end = e.endAt
            if (e.source == Source.AUTO_STAY && e.placeId == null && e.categoryId == field.id &&
                end != null && end - e.startAt < minMs
            ) e.copy(categoryId = stop.id) else e
        }
    }

    /**
     * „To nie klient”: zmienia kategorię wpisów; jeśli podano nazwę — zapisuje miejsce, a wcześniejsze
     * wizyty i postoje w jego promieniu dostają nowe miejsce i kategorię (kolejne rozpoznają się same).
     */
    suspend fun reassign(ctx: Context, entries: List<Entry>, categoryId: Long, placeName: String?) {
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val ref = entries.firstOrNull { it.lat != null && it.lng != null }
        val rLat = ref?.lat
        val rLng = ref?.lng
        var placeId: Long? = null
        if (!placeName.isNullOrBlank() && rLat != null && rLng != null) {
            placeId = app.repo.savePlace(
                pl.rafal.lifecycle.data.Place(name = placeName.trim(), lat = rLat, lng = rLng, radius = 80.0, categoryId = categoryId)
            )
            val field = app.repo.categoryByKind(Kind.FIELD).id
            for (e in db.entries().fieldStaysSince(field, 0L)) {
                val lat = e.lat ?: continue
                val lng = e.lng ?: continue
                if (pl.rafal.lifecycle.util.Geo.distance(lat, lng, rLat, rLng) <= 110) {
                    db.entries().update(e.copy(categoryId = categoryId, placeId = placeId))
                }
            }
        }
        for (e in entries) {
            val raw = db.entries().byId(e.id) ?: continue
            db.entries().update(raw.copy(categoryId = categoryId, placeId = placeId ?: raw.placeId))
        }
    }

    data class Frequent(val key: String, val lat: Double, val lng: Double, val visits: Int, val days: Int, val entries: List<Entry>)

    /** Miejsca bez nazwy, w których w godzinach pracy bywasz w kilku różnych dniach — raczej sklep niż klient. */
    suspend fun frequent(ctx: Context, days: Int = 30): List<Frequent> = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val field = app.db.categories().byKind(Kind.FIELD)?.id ?: return@withContext emptyList()
        val since = Fmt.dayStart(LocalDate.now().minusDays(days.toLong()))
        val stays = app.db.entries().fieldStaysSince(field, since).filter { it.lat != null && it.lng != null }
        val dismissed = ctx.getSharedPreferences("field_dismissed", Context.MODE_PRIVATE).all.keys
        data class Acc(var lat: Double, var lng: Double, val list: MutableList<Entry>)
        val clusters = mutableListOf<Acc>()
        for (e in stays) {
            val c = clusters.firstOrNull { pl.rafal.lifecycle.util.Geo.distance(it.lat, it.lng, e.lat!!, e.lng!!) < 100 }
            if (c == null) clusters += Acc(e.lat!!, e.lng!!, mutableListOf(e)) else c.list += e
        }
        clusters.mapNotNull { c ->
            val d = c.list.map { Fmt.toLocalDate(it.startAt) }.distinct().size
            val key = String.format(Locale.US, "%.3f,%.3f", c.lat, c.lng)
            if (d >= 3 && key !in dismissed) Frequent(key, c.lat, c.lng, c.list.size, d, c.list) else null
        }.sortedByDescending { it.days }
    }

    fun dismiss(ctx: Context, key: String) {
        ctx.getSharedPreferences("field_dismissed", Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()
    }
}
