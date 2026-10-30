package pl.rafal.lifecycle.domain

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.util.Prefs

/** Wydarzenie z kalendarza pasujące do pobytu — podpowiedź nazwy miejsca. */
data class CalendarHint(val title: String, val location: String?, val start: Long, val end: Long)

object CalendarHints {
    fun allowed(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun enabled(ctx: Context) = Prefs(ctx).calendarHints && allowed(ctx)

    /** Wydarzenia (bez całodniowych) nachodzące na przedział. */
    suspend fun events(ctx: Context, from: Long, to: Long): List<CalendarHint> = withContext(Dispatchers.IO) {
        if (!enabled(ctx)) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<CalendarHint>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                ),
                null, null, CalendarContract.Instances.BEGIN,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) == 1) continue
                    val title = c.getString(0)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    out += CalendarHint(title, c.getString(1)?.trim()?.ifBlank { null }, c.getLong(2), c.getLong(3))
                }
            }
        } catch (_: SecurityException) {
        }
        out
    }

    /** Najlepiej pasujące wydarzenie dla pobytów (co najmniej połowa wydarzenia lub pobytu się pokrywa). */
    suspend fun bestFor(ctx: Context, stays: List<Entry>): CalendarHint? {
        if (!enabled(ctx) || stays.isEmpty()) return null
        val now = System.currentTimeMillis()
        val votes = HashMap<String, Pair<CalendarHint, Long>>()
        for (s in stays.sortedByDescending { it.startAt }.take(15)) {
            val end = s.endAt ?: now
            for (e in events(ctx, s.startAt, end)) {
                val overlap = minOf(end, e.end) - maxOf(s.startAt, e.start)
                if (overlap <= 0) continue
                val shorter = minOf(end - s.startAt, e.end - e.start).coerceAtLeast(1)
                if (overlap * 2 < shorter) continue
                val k = e.title.lowercase()
                votes[k] = e to ((votes[k]?.second ?: 0L) + overlap)
            }
        }
        return votes.values.maxByOrNull { it.second }?.first
    }
}
