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

    fun distance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else String.format(PL, "%.1f km", m / 1000)

    fun steps(n: Long): String = String.format(PL, "%,d", n)

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

    var workoutsHc: Boolean
        get() = p.getBoolean("workoutsHc", true)
        set(v) = p.edit().putBoolean("workoutsHc", v).apply()

    var weeklySummary: Boolean
        get() = p.getBoolean("weeklySummary", true)
        set(v) = p.edit().putBoolean("weeklySummary", v).apply()

    var monthlySummary: Boolean
        get() = p.getBoolean("monthlySummary", true)
        set(v) = p.edit().putBoolean("monthlySummary", v).apply()

    var yearlySummary: Boolean
        get() = p.getBoolean("yearlySummary", true)
        set(v) = p.edit().putBoolean("yearlySummary", v).apply()

    var lastWeekly: Long
        get() = p.getLong("lastWeekly", 0L)
        set(v) = p.edit().putLong("lastWeekly", v).apply()

    /** Folder (SAF) na automatyczną kopię zapasową. */
    var backupTree: String?
        get() = p.getString("backupTree", null)
        set(v) = p.edit().putString("backupTree", v).apply()

    var lastBackupDay: Long
        get() = p.getLong("lastBackupDay", -1L)
        set(v) = p.edit().putLong("lastBackupDay", v).apply()

    var lastBackupAt: Long
        get() = p.getLong("lastBackupAt", 0L)
        set(v) = p.edit().putLong("lastBackupAt", v).apply()

    /** Wzrost do BMI, gdy nie ma go w Health Connect (0 = nie podano). */
    var heightCm: Int
        get() = p.getInt("heightCm", 0)
        set(v) = p.edit().putInt("heightCm", v).apply()

    /** Czas przed ekranem (statystyki użycia). */
    var screenTime: Boolean
        get() = p.getBoolean("screenTime", true)
        set(v) = p.edit().putBoolean("screenTime", v).apply()

    /** Odwożenie / odbieranie dzieci (szkoła, przedszkole). */
    var schoolRuns: Boolean
        get() = p.getBoolean("schoolRuns", true)
        set(v) = p.edit().putBoolean("schoolRuns", v).apply()

    // --- Praca w terenie
    var fieldWork: Boolean
        get() = p.getBoolean("fieldWork", false)
        set(v) = p.edit().putBoolean("fieldWork", v).apply()

    /** Dni tygodnia 1 (pon) … 7 (nd). */
    var fieldDays: Set<Int>
        get() = p.getStringSet("fieldDays", setOf("1", "2", "3", "4", "5"))!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("fieldDays", v.map { it.toString() }.toSet()).apply()

    /** Minuty od północy. */
    var fieldStart: Int
        get() = p.getInt("fieldStart", 8 * 60)
        set(v) = p.edit().putInt("fieldStart", v).apply()

    var fieldEnd: Int
        get() = p.getInt("fieldEnd", 18 * 60)
        set(v) = p.edit().putInt("fieldEnd", v).apply()

    /** Minimalny czas wizyty u klienta (minuty); krótsze postoje to „Postój w trasie”. */
    var fieldMinMinutes: Int
        get() = p.getInt("fieldMinMinutes", 15)
        set(v) = p.edit().putInt("fieldMinMinutes", v).apply()

    var commutes: Boolean
        get() = p.getBoolean("commutes", true)
        set(v) = p.edit().putBoolean("commutes", v).apply()

    var stepsHc: Boolean
        get() = p.getBoolean("stepsHc", true)
        set(v) = p.edit().putBoolean("stepsHc", v).apply()

    var stepGoal: Int
        get() = p.getInt("stepGoal", 8000)
        set(v) = p.edit().putInt("stepGoal", v).apply()

    var stepGoalNotify: Boolean
        get() = p.getBoolean("stepGoalNotify", true)
        set(v) = p.edit().putBoolean("stepGoalNotify", v).apply()

    fun goalNotified(key: String): Boolean = p.getBoolean("gn_$key", false)
    fun setGoalNotified(key: String) = p.edit().putBoolean("gn_$key", true).apply()

    var onboardingDone: Boolean
        get() = p.getBoolean("onboardingDone", false)
        set(v) = p.edit().putBoolean("onboardingDone", v).apply()
}
