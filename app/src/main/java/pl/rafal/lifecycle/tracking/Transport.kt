package pl.rafal.lifecycle.tracking

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.LocationSample
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Geo
import pl.rafal.lifecycle.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rozpoznaje środek transportu w zakończonych przejazdach („Podróż”):
 * – pociąg / metro / tramwaj: trasa biegnie wzdłuż torów (OpenStreetMap, Overpass API),
 * – autobus: większość postojów wypada na przystankach,
 * – hulajnoga: równe 12–30 km/h (gdy użytkownik ją ma).
 */
object TransportDetect {
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    data class Profile(
        val samples: List<LocationSample>,
        val distanceM: Double,
        val p90Kmh: Double,
        val avgMovingKmh: Double,
        val stops: List<Pair<Double, Double>>,
    )

    fun profile(samples: List<LocationSample>): Profile? {
        val pts = samples.filter { it.accuracy <= 60f }
        if (pts.size < 8) return null
        val speeds = ArrayList<Double>()
        var dist = 0.0
        val stops = mutableListOf<Pair<Double, Double>>()
        var slowStart: Int? = null
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val dt = (b.time - a.time) / 1000.0
            if (dt < 3) continue
            val d = Geo.distance(a.lat, a.lng, b.lat, b.lng)
            dist += d
            val v = d / dt
            speeds += v
            if (v < 1.0) {
                if (slowStart == null) slowStart = i - 1
            } else {
                slowStart?.let { s ->
                    val len = (pts[i - 1].time - pts[s].time) / 1000
                    if (len in 12..240) {
                        val seg = pts.subList(s, i)
                        stops += seg.map { it.lat }.average() to seg.map { it.lng }.average()
                    }
                }
                slowStart = null
            }
        }
        if (speeds.size < 5) return null
        val sorted = speeds.sorted()
        val p90 = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)] * 3.6
        val moving = speeds.filter { it >= 1.5 }
        return Profile(pts, dist, p90, if (moving.isEmpty()) 0.0 else moving.average() * 3.6, stops)
    }

    /** Odległość punktu od odcinka (metry, rzut lokalny). */
    private fun segDist(lat: Double, lng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val k = cos(Math.toRadians(lat)) * 111_320.0
        val px = (lng - aLng) * k
        val py = (lat - aLat) * 110_540.0
        val bx = (bLng - aLng) * k
        val by = (bLat - aLat) * 110_540.0
        val len = bx * bx + by * by
        val t = if (len == 0.0) 0.0 else ((px * bx + py * by) / len).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    private fun overpass(query: String): JSONObject {
        val c = URL(OVERPASS).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.setRequestProperty("User-Agent", "Doba/1.0 (Android)")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("Overpass HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Zwraca rodzaj środka transportu albo null (zostaje „Podróż”). */
    fun classify(p: Profile, transit: Boolean, scooter: Boolean): String? {
        if (transit && p.distanceM >= 1500) {
            val step = max(1, p.samples.size / 40)
            val line = p.samples.filterIndexed { i, _ -> i % step == 0 }
            fun f(v: Double) = String.format(Locale.US, "%.5f", v)
            val poly = line.joinToString(",") { "${f(it.lat)},${f(it.lng)}" }
            val stopQ = p.stops.take(15).joinToString("") { (la, lo) ->
                "node(around:35,${f(la)},${f(lo)})[highway=bus_stop];node(around:35,${f(la)},${f(lo)})[public_transport=platform];"
            }
            val q = "[out:json][timeout:25];(way(around:30,$poly)[railway~\"^(rail|light_rail|subway|tram|narrow_gauge)$\"];$stopQ);out geom;"
            val els = overpass(q).optJSONArray("elements")
            val rails = mutableListOf<Pair<String, List<Pair<Double, Double>>>>()
            val stopNodes = mutableListOf<Pair<Double, Double>>()
            if (els != null) for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                when (e.optString("type")) {
                    "way" -> {
                        val g = e.optJSONArray("geometry") ?: continue
                        val pts = (0 until g.length()).map { g.getJSONObject(it).let { o -> o.getDouble("lat") to o.getDouble("lon") } }
                        rails += (e.optJSONObject("tags")?.optString("railway") ?: "rail") to pts
                    }
                    "node" -> stopNodes += e.getDouble("lat") to e.getDouble("lon")
                }
            }
            if (rails.isNotEmpty()) {
                val hits = HashMap<String, Int>()
                var near = 0
                for (s in line) {
                    var best = Double.MAX_VALUE
                    var type = ""
                    for ((t, pts) in rails) for (k in 1 until pts.size) {
                        val d = segDist(s.lat, s.lng, pts[k - 1].first, pts[k - 1].second, pts[k].first, pts[k].second)
                        if (d < best) { best = d; type = t }
                    }
                    if (best <= 30) { near++; hits[type] = (hits[type] ?: 0) + 1 }
                }
                if (near >= line.size * 0.75) {
                    val top = hits.maxByOrNull { it.value }?.key
                    return if (top == "tram") Kind.TRANSIT else Kind.TRAIN
                }
            }
            if (p.stops.size >= 3 && p.p90Kmh <= 75) {
                val matched = p.stops.count { (la, lo) -> stopNodes.any { Geo.distance(la, lo, it.first, it.second) <= 40 } }
                if (matched >= 3 && matched >= p.stops.size * 0.6) return Kind.TRANSIT
            }
        }
        if (scooter && p.distanceM >= 800 && p.p90Kmh in 12.0..30.0 && p.avgMovingKmh >= 9) return Kind.SCOOTER
        return null
    }

    suspend fun run(ctx: Context) = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!prefs.transitDetect && !prefs.scooter) return@withContext
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val sp = ctx.getSharedPreferences("transport", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val upTo = maxOf(sp.getLong("checkedUpTo", 0L), now - 85L * 86_400_000L)
        val travel = app.repo.categoryByKind(Kind.TRAVEL).id
        val moves = db.entries().getRange(upTo, now)
            .filter { it.source == Source.AUTO_MOVE && it.endAt != null && it.endAt!! > upTo && it.endAt!! < now - 120_000L }
            .sortedBy { it.endAt }
            .take(12)
        for (m in moves) {
            val end = m.endAt!!
            if (m.categoryId == travel && end - m.startAt >= 4 * 60_000L) {
                val p = profile(db.locations().range(m.startAt, end))
                if (p != null) {
                    val kind = try {
                        classify(p, prefs.transitDetect, prefs.scooter)
                    } catch (e: Exception) {
                        Log.w(TrackingService.TAG, "Rozpoznawanie transportu: ${e.message}")
                        return@withContext // spróbujemy później
                    }
                    if (kind != null) {
                        val cur = db.entries().byId(m.id)
                        if (cur != null && cur.categoryId == travel) {
                            db.entries().update(cur.copy(categoryId = app.repo.categoryByKind(kind).id))
                        }
                    }
                }
            }
            sp.edit().putLong("checkedUpTo", end).apply()
        }
    }
}
