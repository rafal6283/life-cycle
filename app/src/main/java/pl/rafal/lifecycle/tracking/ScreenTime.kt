package pl.rafal.lifecycle.tracking

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.ScreenDay
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.Instant
import java.time.LocalDate

/**
 * Czas przed ekranem ze statystyk użycia Androida (to samo źródło, z którego korzysta Cyfrowa równowaga):
 * czas w aplikacjach, odblokowania, włączenia ekranu, powiadomienia i pierwsze użycie dnia.
 * System trzyma szczegółowe zdarzenia tylko kilka dni wstecz, więc Doba zapisuje wyniki każdego dnia u siebie.
 */
object ScreenTime {
    // Stałe zdarzeń (liczbowo, by nie wymagać nowszego API przy kompilacji warunkowej)
    private const val RESUMED = 1
    private const val PAUSED = 2
    private const val NOTIFICATION = 12
    private const val SCREEN_ON = 15
    private const val SCREEN_OFF = 16
    private const val UNLOCK = 18

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
        }

    /** Pakiety nieliczone jako „używanie telefonu”: ekran główny i interfejs systemu. */
    private fun ignored(ctx: Context): Set<String> {
        val out = mutableSetOf("com.android.systemui", "android")
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { out += it.activityInfo.packageName }
        } catch (_: Exception) {
        }
        return out
    }

    fun compute(ctx: Context, day: LocalDate): ScreenDay? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val from = Fmt.dayStart(day)
        val to = minOf(Fmt.dayStart(day.plusDays(1)), now)
        if (to <= from) return null
        val skip = ignored(ctx)
        val events = usm.queryEvents(from - 3 * 3_600_000L, to)
        val e = UsageEvents.Event()
        val apps = HashMap<String, Long>()
        val hours = LongArray(24)
        var unlocks = 0
        var screenOns = 0
        var notifs = 0
        var firstUse: Long? = null
        var cur: String? = null
        var curStart = 0L
        var any = false

        fun close(t: Long) {
            val pkg = cur ?: return
            cur = null
            val a = maxOf(curStart, from)
            val b = minOf(t, to)
            if (b <= a || pkg in skip) return
            apps[pkg] = (apps[pkg] ?: 0L) + (b - a)
            if (firstUse == null || a < firstUse!!) firstUse = a
            var x = a
            while (x < b) {
                val z = Instant.ofEpochMilli(x).atZone(Fmt.zone)
                val hourEnd = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val y = minOf(hourEnd, b)
                hours[z.hour] += y - x
                x = y
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            if (t >= from) any = true
            when (e.eventType) {
                RESUMED -> if (cur != e.packageName) {
                    close(t)
                    cur = e.packageName
                    curStart = t
                }
                PAUSED -> if (cur == e.packageName) close(t)
                SCREEN_OFF -> close(t)
                UNLOCK -> if (t >= from) unlocks++
                SCREEN_ON -> if (t >= from) screenOns++
                NOTIFICATION -> if (t >= from) notifs++
            }
        }
        if (cur != null) close(to)
        if (!any) return null
        val top = apps.entries.sortedByDescending { it.value }.take(40)
        return ScreenDay(
            day = day.toEpochDay(),
            screenMs = apps.values.sum(),
            unlocks = unlocks,
            screenOns = screenOns,
            notifs = notifs,
            firstUse = firstUse,
            apps = top.joinToString(";") { "${it.key}=${it.value}" },
            hours = hours.joinToString(","),
            updatedAt = now,
        )
    }

    /** Przelicza ostatnie dni (tyle, ile system jeszcze pamięta) i zapisuje w bazie Doby. */
    suspend fun sync(ctx: Context) = withContext(Dispatchers.IO) {
        if (!Prefs(ctx).screenTime || !hasPermission(ctx)) return@withContext
        val db = (ctx.applicationContext as LifeApp).db
        val today = LocalDate.now()
        for (i in 0..9) {
            val d = today.minusDays(i.toLong())
            try {
                val r = compute(ctx, d) ?: continue
                val old = db.screen().get(d.toEpochDay())
                // Nie nadpisuj pełniejszych danych uboższymi (system mógł już usunąć część zdarzeń).
                if (old != null && i > 1 && r.screenMs < old.screenMs * 0.8) continue
                db.screen().upsert(r)
            } catch (e: Exception) {
                Log.w(TrackingService.TAG, "Czas przed ekranem: $d", e)
            }
        }
    }

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
