package pl.rafal.lifecycle.domain

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Geo
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate
import java.util.Locale

/** Wyjazd: co najmniej doba z dala od domu. */
data class Trip(
    val start: Long,
    val end: Long,
    val ongoing: Boolean,
    var name: String,
    val stays: List<Entry>,
    val km: Double,
    val maxFromHomeKm: Double,
    val lat: Double,
    val lng: Double,
) {
    val durationMs: Long get() = end - start
    val nights: Int get() = (Fmt.toLocalDate(end).toEpochDay() - Fmt.toLocalDate(start).toEpochDay()).toInt()
}

data class TripsResult(val trips: List<Trip>, val noHome: Boolean)

object Places2 {
    /** Miejscowość dla punktu (Geocoder, z pamięcią podręczną). */
    suspend fun locality(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("geocode", Context.MODE_PRIVATE)
        val k = "loc:" + String.format(Locale.US, "%.2f,%.2f", lat, lng)
        sp.getString(k, null)?.let { return@withContext it.ifBlank { null } }
        if (!Geocoder.isPresent()) return@withContext null
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Fmt.PL).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@withContext null
            val name = a.locality ?: a.subAdminArea ?: a.adminArea ?: ""
            val country = a.countryCode?.takeIf { it != "PL" }?.let { " (${a.countryName ?: it})" } ?: ""
            val text = if (name.isBlank()) "" else name + country
            sp.edit().putString(k, text).apply()
            text.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}

object Trips {
    private const val MIN_MS = 20 * 3_600_000L

    suspend fun detect(ctx: Context): TripsResult = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val cats = app.db.categories().getAll().associateBy { it.id }
        val homes = app.db.places().getAll().filter { cats[it.categoryId]?.kind == Kind.HOME }
        if (homes.isEmpty()) return@withContext TripsResult(emptyList(), true)
        val km = Prefs(ctx).tripKm * 1000.0
        val all = app.db.entries().all().sortedBy { it.startAt }
        val now = System.currentTimeMillis()
        fun homeDist(e: Entry): Double? {
            val lat = e.lat ?: return null
            val lng = e.lng ?: return null
            return homes.minOf { Geo.distance(lat, lng, it.lat, it.lng) }
        }
        val stays = all.filter { it.source == Source.AUTO_STAY && it.lat != null }
        val trips = mutableListOf<Trip>()
        var i = 0
        while (i < stays.size) {
            val d = homeDist(stays[i]) ?: run { i++; continue }
            if (d < km) { i++; continue }
            // Początek: koniec ostatniego pobytu blisko domu.
            val before = (i - 1 downTo 0).firstOrNull { (homeDist(stays[it]) ?: Double.MAX_VALUE) < km }
            val startAt = before?.let { stays[it].endAt } ?: stays[i].startAt
            var j = i
            while (j + 1 < stays.size && (homeDist(stays[j + 1]) ?: km) >= km) j++
            val after = stays.getOrNull(j + 1)
            val endAt = after?.startAt ?: (stays[j].endAt ?: now)
            val away = stays.subList(i, j + 1).toList()
            if (endAt - startAt >= MIN_MS && startAt != null) {
                val moves = all.filter { it.source == Source.AUTO_MOVE && it.startAt >= startAt && it.startAt < endAt }
                val main = away.groupBy { "%.2f,%.2f".format(Locale.US, it.lat, it.lng) }
                    .maxBy { (_, l) -> l.sumOf { (it.endAt ?: now) - it.startAt } }.value.first()
                trips += Trip(
                    start = startAt, end = endAt, ongoing = after == null, name = "",
                    stays = away, km = moves.sumOf { it.distanceM ?: 0.0 },
                    maxFromHomeKm = away.maxOf { homeDist(it) ?: 0.0 } / 1000,
                    lat = main.lat!!, lng = main.lng!!,
                )
            }
            i = j + 1
        }
        for (t in trips) {
            t.name = Places2.locality(ctx, t.lat, t.lng) ?: "Wyjazd"
        }
        TripsResult(trips.sortedByDescending { it.start }, false)
    }

    fun dates(t: Trip): String {
        val a = Fmt.toLocalDate(t.start)
        val b = Fmt.toLocalDate(t.end)
        val f = java.time.format.DateTimeFormatter.ofPattern("d MMM", Fmt.PL)
        return if (a == b) a.format(f) else "${a.format(f)} – ${b.format(f)} ${b.year}"
    }

    fun nightsWord(n: Int) = when {
        n == 1 -> "noc"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "noce"
        else -> "nocy"
    }
}

/** Mapa cieplna i odwiedzone miejscowości. */
object Visited {
    data class Point(val lat: Double, val lng: Double, val weight: Double)
    data class Locality(val name: String, val ms: Long, val first: LocalDate, val visits: Int)
    data class Result(
        val points: List<Point>,
        val localities: List<Locality>,
        val newPlacesThisMonth: Int,
        val pending: Int,
    )

    suspend fun compute(ctx: Context, sinceDays: Int?, resolveLimit: Int = 60): Result = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val now = System.currentTimeMillis()
        val from = sinceDays?.let { now - it * 86_400_000L } ?: 0L
        val allStays = app.db.entries().allStays()
        val stays = allStays.filter { (it.endAt ?: now) > from }
        val pts = stays.map { Point(it.lat!!, it.lng!!, ((it.endAt ?: now) - it.startAt) / 3_600_000.0) }.toMutableList()
        // Przejazdy z punktów GPS (ostatnie 90 dni) — cieńsza warstwa.
        app.db.locations().range(maxOf(from, now - 90 * 86_400_000L), now).forEachIndexed { i, l ->
            if (i % 3 == 0) pts += Point(l.lat, l.lng, 0.05)
        }
        // Miejscowości: siatka ~1 km, nazwy z Geocodera.
        class Cell(val lat: Double, val lng: Double) { var ms = 0L; var first = Long.MAX_VALUE; var n = 0 }
        val cells = LinkedHashMap<String, Cell>()
        for (s in stays) {
            val k = "%.2f,%.2f".format(Locale.US, s.lat, s.lng)
            val c = cells.getOrPut(k) { Cell(s.lat!!, s.lng!!) }
            c.ms += (s.endAt ?: now) - s.startAt
            c.first = minOf(c.first, s.startAt)
            c.n++
        }
        val names = HashMap<String, Locality>()
        var resolved = 0
        var pending = 0
        for (c in cells.values.sortedByDescending { it.ms }) {
            val sp = ctx.getSharedPreferences("geocode", Context.MODE_PRIVATE)
            val cached = sp.contains("loc:" + String.format(Locale.US, "%.2f,%.2f", c.lat, c.lng))
            if (!cached && resolved >= resolveLimit) { pending++; continue }
            if (!cached) resolved++
            val name = Places2.locality(ctx, c.lat, c.lng) ?: continue
            val old = names[name]
            names[name] = Locality(
                name, (old?.ms ?: 0L) + c.ms,
                minOf(old?.first ?: LocalDate.MAX, Fmt.toLocalDate(c.first)), (old?.visits ?: 0) + c.n,
            )
        }
        // Nowe miejsca w tym miesiącu: skupiska pobytów (150 m) odwiedzone pierwszy raz w tym miesiącu.
        val monthStart = Fmt.dayStart(LocalDate.now().withDayOfMonth(1))
        val seen = mutableListOf<Pair<Double, Double>>()
        var newCount = 0
        for (s in allStays) {
            val lat = s.lat!!
            val lng = s.lng!!
            if (seen.any { Geo.distance(it.first, it.second, lat, lng) < 150 }) continue
            seen += lat to lng
            if (s.startAt >= monthStart) newCount++
        }
        Result(pts, names.values.sortedByDescending { it.ms }, newCount, pending)
    }
}
