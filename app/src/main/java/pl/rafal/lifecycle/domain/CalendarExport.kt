package pl.rafal.lifecycle.domain

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate
import java.util.TimeZone

/** Zapis osi czasu Doby jako wydarzenia w kalendarzu (np. Google — widać przeszłość obok planów). */
object CalendarExport {
    private const val MARK = "#doba"
    data class Cal(val id: Long, val name: String, val account: String)

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<Cal> {
        if (!allowed(ctx)) return emptyList()
        val out = mutableListOf<Cal>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                out += Cal(c.getLong(0), c.getString(1) ?: "Kalendarz", c.getString(2) ?: "")
            }
        }
        return out
    }

    /** Nowy kalendarz „Doba” tylko na telefonie. */
    fun createLocal(ctx: Context): Long? {
        if (!allowed(ctx)) return null
        calendars(ctx).firstOrNull { it.name == "Doba" && it.account == "Doba" }?.let { return it.id }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "Doba")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "Doba")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Doba")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Doba")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF5E5CE6.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "Doba")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        return ctx.contentResolver.insert(uri, v)?.lastPathSegment?.toLongOrNull()
    }

    /** Zastępuje wydarzenia Doby w kalendarzu dla dni [from, to]. */
    suspend fun export(ctx: Context, from: LocalDate, to: LocalDate): Int = withContext(Dispatchers.IO) {
        val calId = Prefs(ctx).calendarExport
        if (calId < 0 || !allowed(ctx)) return@withContext 0
        val app = ctx.applicationContext as LifeApp
        val start = Fmt.dayStart(from)
        val end = Fmt.dayStart(to.plusDays(1))
        val now = System.currentTimeMillis()
        val cats = app.db.categories().getAll().associateBy { it.id }
        val places = app.db.places().getAll().associateBy { it.id }
        val segs = Timeline.resolve(app.repo.getRange(start, end), start, minOf(end, now), now)
        // Łączymy sąsiednie odcinki tej samej kategorii i miejsca, pomijamy krótkie i „nieznane”.
        data class Ev(var s: Long, var e: Long, val cat: Long, val place: Long?)
        val evs = mutableListOf<Ev>()
        for (s in segs) {
            val last = evs.lastOrNull()
            if (last != null && last.cat == s.entry.categoryId && last.place == s.entry.placeId && s.start - last.e < 5 * 60_000L) last.e = s.end
            else evs += Ev(s.start, s.end, s.entry.categoryId, s.entry.placeId)
        }
        val keep = evs.filter { it.e - it.s >= 15 * 60_000L && cats[it.cat]?.kind != Kind.UNKNOWN }
        val ops = ArrayList<ContentProviderOperation>()
        // Usuń wcześniejsze wydarzenia Doby z tego zakresu.
        val ids = mutableListOf<Long>()
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
            arrayOf(calId.toString(), (start - 86_400_000L).toString(), end.toString(), "%$MARK"), null,
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        ids.forEach {
            ops += ContentProviderOperation.newDelete(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it)
            ).build()
        }
        val tz = TimeZone.getDefault().id
        for (e in keep) {
            val cat = cats[e.cat]
            val place = e.place?.let { places[it]?.name }
            val title = (cat?.name ?: "Aktywność") + (place?.takeIf { it != cat?.name }?.let { " · $it" } ?: "")
            ops += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calId)
                .withValue(CalendarContract.Events.TITLE, title)
                .withValue(CalendarContract.Events.DTSTART, e.s)
                .withValue(CalendarContract.Events.DTEND, e.e)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, tz)
                .withValue(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
                .withValue(CalendarContract.Events.HAS_ALARM, 0)
                .withValue(CalendarContract.Events.DESCRIPTION, "Zapisane przez Dobę ${Fmt.duration(e.e - e.s)} $MARK")
                .build()
        }
        try {
            ops.chunked(200).forEach { ctx.contentResolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(it)) }
        } catch (ex: Exception) {
            Log.w("Doba", "Eksport do kalendarza: ${ex.message}")
            return@withContext 0
        }
        keep.size
    }

    /** Codziennie: wczoraj i dziś. */
    suspend fun daily(ctx: Context) {
        if (Prefs(ctx).calendarExport < 0) return
        val sp = ctx.getSharedPreferences("calexport", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last", 0L) < 3 * 3_600_000L) return
        export(ctx, LocalDate.now().minusDays(1), LocalDate.now())
        sp.edit().putLong("last", now).apply()
    }

    /** Usuwa wszystkie wydarzenia Doby z wybranego kalendarza (przy wyłączaniu). */
    suspend fun clear(ctx: Context, calId: Long) = withContext(Dispatchers.IO) {
        if (!allowed(ctx)) return@withContext
        try {
            ctx.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
                arrayOf(calId.toString(), "%$MARK"),
            )
        } catch (_: Exception) {
        }
    }
}
