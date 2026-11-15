package pl.rafal.lifecycle.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.data.RouteTrack
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Geo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class RoutePoint(val lat: Double, val lng: Double, val t: Long)

/** Przejazd / spacer z opisem skąd–dokąd. */
data class RouteInfo(
    val entry: Entry,
    val from: String,
    val to: String,
    val fromPlace: Long?,
    val toPlace: Long?,
) {
    val durationMs: Long get() = (entry.endAt ?: System.currentTimeMillis()) - entry.startAt
    val pairKey: String? get() = if (fromPlace != null && toPlace != null && fromPlace != toPlace) "$fromPlace>$toPlace" else null
}

data class RouteStats(
    val distanceM: Double,
    val avgKmh: Double,
    val maxKmh: Double,
    val paceSecPerKm: Double?,
    /** Czas w ruchu (bez postojów dłuższych niż ~20 s). */
    val movingMs: Long = 0,
    val stops: Int = 0,
    val stopMs: Long = 0,
    val avgMovingKmh: Double = 0.0,
    /** Czas kolejnych pełnych kilometrów (ms). */
    val splits: List<Long> = emptyList(),
    /** Prędkość w czasie: (ms od startu, km/h). */
    val profile: List<Pair<Long, Double>> = emptyList(),
    val steps: Long = 0,
)

object Routes {
    private val moveKinds = setOf(
        Kind.TRAVEL, Kind.WALK, Kind.RUN, Kind.BIKE, Kind.COMMUTE, Kind.TRAIN, Kind.TRANSIT, Kind.SCOOTER, Kind.SCHOOL,
    )

    fun isRoute(e: Entry, cats: Map<Long, Category>): Boolean =
        e.source == Source.AUTO_MOVE || (cats[e.categoryId]?.kind in moveKinds && e.lat == null)

    // ------------------------------------------------------------------ zapis

    fun encode(pts: List<RoutePoint>, start: Long): String =
        pts.joinToString(";") { String.format(Locale.US, "%.5f,%.5f,%d", it.lat, it.lng, (it.t - start) / 1000) }

    fun decode(s: String, start: Long): List<RoutePoint> =
        s.split(";").mapNotNull { p ->
            val a = p.split(",")
            if (a.size < 3) null else RoutePoint(a[0].toDoubleOrNull() ?: return@mapNotNull null, a[1].toDoubleOrNull() ?: return@mapNotNull null,
                start + (a[2].toLongOrNull() ?: 0L) * 1000)
        }

    /** Douglas–Peucker: usuwa punkty bliżej niż [tol] m od linii. */
    fun simplify(pts: List<RoutePoint>, tol: Double = 8.0): List<RoutePoint> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = segDist(pts[i], pts[a], pts[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tol) {
                keep[idx] = true
                stack.add(a to idx); stack.add(idx to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun segDist(p: RoutePoint, a: RoutePoint, b: RoutePoint): Double {
        val k = cos(Math.toRadians(p.lat)) * 111_320.0
        val px = (p.lng - a.lng) * k; val py = (p.lat - a.lat) * 110_540.0
        val bx = (b.lng - a.lng) * k; val by = (b.lat - a.lat) * 110_540.0
        val len = bx * bx + by * by
        val t = if (len == 0.0) 0.0 else ((px * bx + py * by) / len).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    private suspend fun raw(ctx: Context, e: Entry): List<RoutePoint> {
        val db = (ctx.applicationContext as LifeApp).db
        return db.locations().range(e.startAt - 60_000L, (e.endAt ?: System.currentTimeMillis()) + 60_000L)
            .filter { it.accuracy <= 80f }
            .map { RoutePoint(it.lat, it.lng, it.time) }
    }

    /** Punkty trasy: świeże z GPS albo zapisane na stałe. */
    suspend fun points(ctx: Context, e: Entry): List<RoutePoint> = withContext(Dispatchers.IO) {
        val r = raw(ctx, e)
        if (r.size >= 2) return@withContext r
        (ctx.applicationContext as LifeApp).db.routes().get(e.id)?.let { decode(it.points, e.startAt) } ?: emptyList()
    }

    /** Utrwala trasy zakończonych przejazdów, zanim punkty GPS zostaną skasowane (90 dni). */
    suspend fun archive(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as LifeApp
        val now = System.currentTimeMillis()
        val cats = app.db.categories().getAll().associateBy { it.id }
        val have = app.db.routes().ids().toHashSet()
        val moves = app.db.entries().getRange(now - 89L * 86_400_000L, now - 30 * 60_000L)
            .filter { it.endAt != null && it.endAt!! < now - 30 * 60_000L && it.id !in have && isRoute(it, cats) }
        for (m in moves.take(400)) {
            val pts = raw(ctx, m)
            if (pts.size < 2) continue
            val d = distance(pts)
            if (d < 150) continue
            app.db.routes().upsert(RouteTrack(m.id, encode(simplify(pts), m.startAt), d))
        }
        app.db.routes().pruneOrphans()
    }

    // ------------------------------------------------------------------ analiza

    fun distance(pts: List<RoutePoint>): Double =
        (1 until pts.size).sumOf { Geo.distance(pts[it - 1].lat, pts[it - 1].lng, pts[it].lat, pts[it].lng) }

    /** Prędkości odcinków (km/h), wygładzone po kilku punktach. */
    fun speeds(pts: List<RoutePoint>): List<Double> = (1 until pts.size).map { i ->
        val a = pts[maxOf(0, i - 2)]
        val b = pts[minOf(pts.size - 1, i + 1)]
        val dt = (b.t - a.t) / 1000.0
        var d = 0.0
        for (k in maxOf(1, i - 1)..minOf(pts.size - 1, i + 1)) d += Geo.distance(pts[k - 1].lat, pts[k - 1].lng, pts[k].lat, pts[k].lng)
        if (dt <= 0) 0.0 else d / dt * 3.6
    }

    fun stats(e: Entry, pts: List<RoutePoint>, kind: String?, steps: Long = 0): RouteStats {
        val d = if (pts.size >= 2) distance(pts) else e.distanceM ?: 0.0
        val dur = ((e.endAt ?: System.currentTimeMillis()) - e.startAt) / 1000.0
        val raw = speeds(pts)
        val sp = raw.sorted()
        val max = if (sp.isEmpty()) 0.0 else sp[(sp.size * 0.95).toInt().coerceAtMost(sp.size - 1)]
        val pace = if ((kind == Kind.WALK || kind == Kind.RUN) && d > 200) dur / (d / 1000) else null
        // Postoje: odcinki wolniejsze niż 2 km/h (pieszo 1 km/h) trwające ponad 20 s.
        val slow = if (kind == Kind.WALK || kind == Kind.RUN) 1.0 else 2.0
        var stops = 0
        var stopMs = 0L
        var runStart: Long? = null
        for (i in 1 until pts.size) {
            val v = raw[i - 1]
            if (v < slow) {
                if (runStart == null) runStart = pts[i - 1].t
            } else if (runStart != null) {
                val len = pts[i - 1].t - runStart
                if (len >= 20_000L) { stops++; stopMs += len }
                runStart = null
            }
        }
        runStart?.let { val len = pts.last().t - it; if (len >= 20_000L) { stops++; stopMs += len } }
        val totalMs = (dur * 1000).toLong()
        val moving = (totalMs - stopMs).coerceAtLeast(0L)
        // Międzyczasy co kilometr.
        val splits = mutableListOf<Long>()
        var acc = 0.0
        var last = pts.firstOrNull()?.t ?: e.startAt
        for (i in 1 until pts.size) {
            acc += Geo.distance(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
            if (acc >= 1000.0 * (splits.size + 1)) { splits += pts[i].t - last; last = pts[i].t }
        }
        val t0 = pts.firstOrNull()?.t ?: e.startAt
        val profile = (1 until pts.size).map { (pts[it].t - t0) to raw[it - 1] }
        return RouteStats(
            d, if (dur > 0) d / dur * 3.6 else 0.0, max, pace, moving, stops, stopMs,
            if (moving > 0) d / (moving / 1000.0) * 3.6 else 0.0, splits, profile, steps,
        )
    }

    /** Opis skąd–dokąd dla przejazdów z zakresu (na podstawie pobytów przed i po). */
    suspend fun describe(ctx: Context, from: Long, to: Long): List<RouteInfo> = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val cats = app.db.categories().getAll().associateBy { it.id }
        val places = app.db.places().getAll().associateBy { it.id }
        val all = app.repo.getRange(from - 12 * 3_600_000L, to + 12 * 3_600_000L).sortedBy { it.startAt }
        val stays = all.filter { it.source == Source.AUTO_STAY || it.placeId != null }
        val moves = all.filter { it.startAt in from until to && isRoute(it, cats) && (it.endAt ?: Long.MAX_VALUE) - it.startAt >= 60_000L }
        val addr = HashMap<String, String?>()
        suspend fun label(e: Entry?): Pair<String, Long?> {
            if (e == null) return "?" to null
            e.placeId?.let { id -> places[id]?.let { return it.name to id } }
            val lat = e.lat ?: return (cats[e.categoryId]?.name ?: "?") to null
            val lng = e.lng ?: return (cats[e.categoryId]?.name ?: "?") to null
            val k = String.format(Locale.US, "%.3f,%.3f", lat, lng)
            val a = addr.getOrPut(k) { FieldWork.address(ctx, lat, lng)?.substringBefore(',') }
            return (a ?: cats[e.categoryId]?.name ?: "?") to null
        }
        moves.map { m ->
            val before = stays.lastOrNull { (it.endAt ?: Long.MAX_VALUE) <= m.startAt + 60_000L && m.startAt - (it.endAt ?: 0L) < 30 * 60_000L }
            val after = stays.firstOrNull { it.startAt >= (m.endAt ?: Long.MAX_VALUE) - 60_000L && it.startAt - (m.endAt ?: 0L) < 30 * 60_000L }
            val (f, fp) = label(before)
            val (t, tp) = label(after)
            RouteInfo(m, f, t, fp, tp)
        }
    }

    fun placeName(places: Map<Long, Place>, id: Long?) = id?.let { places[it]?.name }

    /** Czy dwa czasy przejazdu różnią się istotnie (do porównań „szybciej / wolniej”). */
    fun differs(a: Long, b: Long) = abs(a - b) >= 60_000L
}
