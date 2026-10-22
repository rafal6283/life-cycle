package pl.rafal.lifecycle.util

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Odległość w metrach (haversine). */
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

object Fmt {
    val PL: Locale = Locale.forLanguageTag("pl-PL")
    val zone: ZoneId get() = ZoneId.systemDefault()

    private val timeF = DateTimeFormatter.ofPattern("HH:mm")
    private val dayF = DateTimeFormatter.ofPattern("EEEE, d MMMM", PL)
    private val dayYearF = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", PL)
    private val dateF = DateTimeFormatter.ofPattern("d MMM yyyy", PL)
    private val dateTimeF = DateTimeFormatter.ofPattern("d MMM, HH:mm", PL)

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeF)
    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateF)
    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateTimeF)

    fun duration(ms: Long): String {
        val m = (ms / 60_000).toInt()
        val h = m / 60
        val mm = m % 60
        return when {
            h == 0 -> "$mm min"
            mm == 0 -> "$h h"
            else -> "$h h $mm min"
        }
    }

    fun durationShort(ms: Long): String {
        val m = (ms / 60_000).toInt()
        val h = m / 60
        val mm = m % 60
        return if (h == 0) "${mm}m" else "${h}h ${mm.toString().padStart(2, '0')}m"
    }

    fun dayTitle(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Dziś"
            today.minusDays(1) -> "Wczoraj"
            else -> {
                val f = if (d.year == today.year) dayF else dayYearF
                d.format(f).replaceFirstChar { it.titlecase(PL) }
            }
        }
    }

    fun dayStart(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    fun toLocalDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tracking: Boolean
        get() = p.getBoolean("tracking", true)
        set(v) = p.edit().putBoolean("tracking", v).apply()

    var sleepHc: Boolean
        get() = p.getBoolean("sleepHc", true)
        set(v) = p.edit().putBoolean("sleepHc", v).apply()

    var sleepAuto: Boolean
        get() = p.getBoolean("sleepAuto", true)
        set(v) = p.edit().putBoolean("sleepAuto", v).apply()

    var onboardingDone: Boolean
        get() = p.getBoolean("onboardingDone", false)
        set(v) = p.edit().putBoolean("onboardingDone", v).apply()
}
