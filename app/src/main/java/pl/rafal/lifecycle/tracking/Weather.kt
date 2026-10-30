package pl.rafal.lifecycle.tracking

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.WeatherDay
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/**
 * Pogoda z Open-Meteo (bezpłatne, bez klucza): dla każdego dnia w miejscu, gdzie spędzono najwięcej czasu.
 * Ostatnie tygodnie z API prognoz (ma też przeszłe dni), starsze z archiwum.
 */
object WeatherSync {
    private const val DAY = 86_400_000L

    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun label(code: Int): String = when (code) {
        0 -> "bezchmurnie"
        1, 2 -> "małe zachmurzenie"
        3 -> "pochmurno"
        45, 48 -> "mgła"
        in 51..57 -> "mżawka"
        in 61..65, in 80..82 -> "deszcz"
        66, 67 -> "marznący deszcz"
        in 71..77, 85, 86 -> "śnieg"
        in 95..99 -> "burza"
        else -> ""
    }

    suspend fun run(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!prefs.weather) return@withContext
        val sp = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("last", 0L) < 3 * 3_600_000L) return@withContext
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val today = LocalDate.now()
        val first = db.entries().firstStart()?.let { Fmt.toLocalDate(it) } ?: return@withContext
        val start = maxOf(first, today.minusDays(365))
        val have = db.weather().range(start.toEpochDay(), today.toEpochDay() + 1).associateBy { it.day }
        // Dzień jest „ostateczny”, gdy pobrano go co najmniej dobę po jego końcu.
        val need = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= today }.filter { d ->
            val w = have[d.toEpochDay()] ?: return@filter true
            w.updatedAt < Fmt.dayStart(d.plusDays(2)) && now - w.updatedAt > 3 * 3_600_000L
        }.toList()
        if (need.isEmpty()) { sp.edit().putLong("last", now).apply(); return@withContext }

        val home = db.places().getAll().firstOrNull { p ->
            db.categories().getAll().firstOrNull { it.id == p.categoryId }?.kind == Kind.HOME
        }
        val fallback = home?.let { it.lat to it.lng }
            ?: db.locations().last()?.let { it.lat to it.lng }
            ?: return@withContext
        val entries = db.entries().getRange(Fmt.dayStart(need.first()), Fmt.dayStart(today.plusDays(1)))
        // Miejsce dnia: pobyt z największym łącznym czasem (siatka ~5 km, żeby grupować zapytania).
        fun key(lat: Double, lng: Double) = (Math.round(lat * 20) / 20.0) to (Math.round(lng * 20) / 20.0)
        val byLoc = need.groupBy { d ->
            val f = Fmt.dayStart(d)
            val t = Fmt.dayStart(d.plusDays(1))
            entries.filter { it.lat != null && it.startAt < t && (it.endAt ?: now) > f }
                .groupBy { key(it.lat!!, it.lng!!) }
                .maxByOrNull { (_, l) -> l.sumOf { minOf(it.endAt ?: now, t) - maxOf(it.startAt, f) } }?.key
                ?: key(fallback.first, fallback.second)
        }
        var requests = 0
        for ((loc, days) in byLoc) {
            val recentFrom = today.minusDays(60)
            val parts = listOf(days.filter { it < recentFrom } to true, days.filter { it >= recentFrom } to false)
            for ((list, archive) in parts) {
                if (list.isEmpty()) continue
                if (requests >= 8) break
                requests++
                try {
                    val got = fetch(loc.first, loc.second, list.min(), list.max(), archive, now)
                    val wanted = list.map { it.toEpochDay() }.toSet()
                    db.weather().upsertAll(got.filter { it.day in wanted })
                } catch (e: Exception) {
                    Log.w(TrackingService.TAG, "Pogoda: ${e.message}")
                    return@withContext
                }
            }
        }
        sp.edit().putLong("last", now).apply()
    }

    private fun fetch(lat: Double, lng: Double, from: LocalDate, to: LocalDate, archive: Boolean, now: Long): List<WeatherDay> {
        val host = if (archive) "https://archive-api.open-meteo.com/v1/archive" else "https://api.open-meteo.com/v1/forecast"
        val url = String.format(
            Locale.US,
            "%s?latitude=%.3f&longitude=%.3f&start_date=%s&end_date=%s&daily=temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,weather_code,sunshine_duration&timezone=auto",
            host, lat, lng, from, to,
        )
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Doba/1.0 (Android)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("daily")
            val time = j.getJSONArray("time")
            val tMax = j.getJSONArray("temperature_2m_max")
            val tMin = j.getJSONArray("temperature_2m_min")
            val pr = j.getJSONArray("precipitation_sum")
            val code = j.getJSONArray("weather_code")
            val sun = j.optJSONArray("sunshine_duration")
            return (0 until time.length()).mapNotNull { i ->
                if (tMax.isNull(i) || tMin.isNull(i)) return@mapNotNull null
                WeatherDay(
                    day = LocalDate.parse(time.getString(i)).toEpochDay(),
                    tMax = tMax.getDouble(i), tMin = tMin.getDouble(i),
                    precip = if (pr.isNull(i)) 0.0 else pr.getDouble(i),
                    code = if (code.isNull(i)) -1 else code.getInt(i),
                    sunH = if (sun == null || sun.isNull(i)) 0.0 else sun.getDouble(i) / 3600.0,
                    lat = lat, lng = lng, updatedAt = now,
                )
            }
        } finally {
            c.disconnect()
        }
    }
}
