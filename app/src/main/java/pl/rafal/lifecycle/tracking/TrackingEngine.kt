package pl.rafal.lifecycle.tracking

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.rafal.lifecycle.data.AppDatabase
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.LocationSample
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.data.Repository
import pl.rafal.lifecycle.data.ScreenOff
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Geo
import kotlin.math.max

/**
 * Zamienia surowe pozycje GPS i zdarzenia ruchu na wpisy osi czasu:
 * pobyty w miejscach (AUTO_STAY) oraz przemieszczanie się (AUTO_MOVE).
 */
class TrackingEngine(
    private val ctx: Context,
    private val db: AppDatabase,
    private val repo: Repository,
) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Czy użytkownik się porusza — usługa zwiększa wtedy częstotliwość odczytów GPS. */
    val moving = MutableStateFlow(prefs.getInt("mode", MODE_NONE) == MODE_MOVE)

    private var mode: Int
        get() = prefs.getInt("mode", MODE_NONE)
        set(v) {
            prefs.edit().putInt("mode", v).apply()
        }

    private fun getD(k: String) = Double.fromBits(prefs.getLong(k, 0L))
    private val hasAnchor get() = prefs.getBoolean("hasAnchor", false)
    private val aLat get() = getD("aLat")
    private val aLng get() = getD("aLng")
    private val anchorSince get() = prefs.getLong("aSince", 0L)
    private var lastSeen: Long
        get() = prefs.getLong("aSeen", 0L)
        set(v) {
            prefs.edit().putLong("aSeen", v).apply()
        }
    private var pendingOut: Boolean
        get() = prefs.getBoolean("pendingOut", false)
        set(v) {
            prefs.edit().putBoolean("pendingOut", v).apply()
        }

    private fun setAnchor(lat: Double, lng: Double, t: Long) {
        prefs.edit()
            .putLong("aLat", lat.toRawBits())
            .putLong("aLng", lng.toRawBits())
            .putLong("aSince", t)
            .putLong("aSeen", t)
            .putBoolean("hasAnchor", true)
            .putBoolean("pendingOut", false)
            .apply()
    }

    suspend fun onLocation(lat: Double, lng: Double, acc: Float, time: Long) {
        mutex.withLock {
            if (acc > MAX_ACCURACY) return@withLock
            val lastFix = prefs.getLong("lastFix", 0L)
            if (time <= lastFix) return@withLock
            db.locations().insert(LocationSample(time = time, lat = lat, lng = lng, accuracy = acc))
            prefs.edit().putLong("lastFix", time).apply()

            // Długa przerwa w danych podczas jazdy — zamknij przejazd.
            if (mode == MODE_MOVE && lastFix > 0 && time - lastFix > GAP_MS) {
                closeOpen(lastFix)
                mode = MODE_NONE
            }

            if (!hasAnchor) {
                setAnchor(lat, lng, time)
                return@withLock
            }

            val d = Geo.distance(aLat, aLng, lat, lng)
            val radius = max(STAY_RADIUS, acc * 0.8)

            if (d <= radius) {
                lastSeen = time
                pendingOut = false
                if (mode != MODE_STAY && time - anchorSince >= DWELL_MS) {
                    startStay(anchorSince, aLat, aLng)
                }
                return@withLock
            }

            when (mode) {
                MODE_STAY -> {
                    // Pojedynczy „skok” GPS nie kończy pobytu — potrzebne potwierdzenie.
                    if (!pendingOut && d < radius * 3) {
                        pendingOut = true
                        return@withLock
                    }
                    val leaveAt = lastSeen
                    startMove(leaveAt, lat, lng)
                }
                MODE_NONE -> startMove(lastSeen, lat, lng)
                else -> Unit
            }
            setAnchor(lat, lng, time)
        }
    }

    suspend fun onActivity(type: Int, time: Long) {
        mutex.withLock {
            prefs.edit().putInt("act", type).putLong("actAt", time).apply()
            val kind = kindFor(type)
            moving.value = kind != null || mode == MODE_MOVE
            if (mode != MODE_MOVE || kind == null) return@withLock
            val open = db.entries().openAuto() ?: return@withLock
            if (open.source != Source.AUTO_MOVE) return@withLock
            val cat = repo.categoryByKind(kind)
            if (open.categoryId == cat.id) return@withLock
            if (time - open.startAt < SWITCH_MS) {
                db.entries().update(open.copy(categoryId = cat.id))
            } else {
                val at = max(time, open.startAt)
                db.entries().update(open.copy(endAt = at))
                db.entries().insert(open.copy(id = 0, startAt = at, endAt = null, categoryId = cat.id))
            }
        }
    }

    fun onScreenOff(t: Long) {
        prefs.edit().putLong("scrOff", t).apply()
    }

    suspend fun onScreenOn(t: Long) {
        val off = prefs.getLong("scrOff", 0L)
        prefs.edit().remove("scrOff").apply()
        if (off > 0 && t - off >= 10 * 60_000L) {
            db.screenOff().insert(ScreenOff(startAt = off, endAt = t))
        }
    }

    private suspend fun startStay(since: Long, lat: Double, lng: Double) {
        val place = matchPlace(lat, lng)
        val recent = db.entries().lastAuto(2)
        val open = recent.firstOrNull()?.takeIf { it.endAt == null }

        // Krótki „przejazd” między dwoma pobytami w tym samym miejscu to szum GPS — scal.
        if (open != null && open.source == Source.AUTO_MOVE && recent.size == 2) {
            val prev = recent[1]
            val prevEnd = prev.endAt
            if (prev.source == Source.AUTO_STAY && prevEnd != null &&
                since - prevEnd < MERGE_MS && samePlace(prev, place, lat, lng)
            ) {
                db.entries().delete(open)
                db.entries().update(prev.copy(endAt = null))
                mode = MODE_STAY
                moving.value = false
                return
            }
        }

        closeOpen(since)
        db.entries().closeOverrides(since)
        // Praca w terenie: nowy pobyt w godzinach pracy = wizyta u klienta (bez pytania o nazwę miejsca).
        val field = place == null && pl.rafal.lifecycle.domain.FieldWork.activeAt(ctx, since)
        val catId = place?.categoryId
            ?: if (field) repo.categoryByKind(Kind.FIELD).id else repo.categoryByKind(Kind.UNKNOWN).id
        db.entries().insert(
            Entry(
                startAt = since, endAt = null, categoryId = catId, placeId = place?.id,
                lat = lat, lng = lng, source = Source.AUTO_STAY,
            )
        )
        mode = MODE_STAY
        moving.value = false
        if (place == null && !field) Notifications.notifyNewPlace(ctx)
    }

    private suspend fun startMove(at: Long, lat: Double, lng: Double) {
        closeOpen(at)
        db.entries().closeOverrides(at)
        val cat = categoryForMotion()
        db.entries().insert(
            Entry(startAt = at, endAt = null, categoryId = cat.id, lat = lat, lng = lng, source = Source.AUTO_MOVE)
        )
        mode = MODE_MOVE
        moving.value = true
    }

    private suspend fun closeOpen(at: Long) {
        val open = db.entries().openAuto() ?: return
        val end = max(at, open.startAt)
        if (open.source == Source.AUTO_MOVE && end - open.startAt < MIN_MOVE_MS) {
            db.entries().delete(open)
        } else {
            val dist = if (open.source == Source.AUTO_MOVE) routeDistance(open.startAt, end) else null
            db.entries().update(open.copy(endAt = end, distanceM = dist))
        }
    }

    /** Dystans trasy z zapisanych punktów GPS (pomija niedokładne odczyty i drgania). */
    private suspend fun routeDistance(from: Long, to: Long): Double? {
        val pts = db.locations().range(from, to).filter { it.accuracy <= 100f }
        if (pts.size < 2) return null
        var sum = 0.0
        var prev = pts.first()
        for (p in pts.drop(1)) {
            val d = Geo.distance(prev.lat, prev.lng, p.lat, p.lng)
            if (d >= max(prev.accuracy, p.accuracy) * 0.5) {
                sum += d
                prev = p
            }
        }
        return if (sum > 0) sum else null
    }

    private suspend fun categoryForMotion(): Category {
        val type = prefs.getInt("act", -1)
        val at = prefs.getLong("actAt", 0L)
        val fresh = System.currentTimeMillis() - at < ACTIVITY_FRESH_MS
        val kind = if (fresh) kindFor(type) ?: Kind.TRAVEL else Kind.TRAVEL
        return repo.categoryByKind(kind)
    }

    private suspend fun matchPlace(lat: Double, lng: Double): Place? =
        db.places().getAll()
            .map { it to Geo.distance(lat, lng, it.lat, it.lng) }
            .filter { (p, d) -> d <= p.radius + 25 }
            .minByOrNull { it.second }
            ?.first

    private fun samePlace(prev: Entry, place: Place?, lat: Double, lng: Double): Boolean {
        if (place != null) return prev.placeId == place.id
        val pLat = prev.lat ?: return false
        val pLng = prev.lng ?: return false
        return prev.placeId == null && Geo.distance(pLat, pLng, lat, lng) < 200
    }

    companion object {
        const val MODE_NONE = 0
        const val MODE_STAY = 1
        const val MODE_MOVE = 2

        const val STAY_RADIUS = 120.0
        const val MAX_ACCURACY = 250f
        const val DWELL_MS = 5 * 60_000L
        const val GAP_MS = 2 * 3_600_000L
        const val MERGE_MS = 15 * 60_000L
        const val MIN_MOVE_MS = 60_000L
        const val SWITCH_MS = 2 * 60_000L
        const val ACTIVITY_FRESH_MS = 10 * 60_000L

        fun kindFor(type: Int): String? = when (type) {
            DetectedActivity.IN_VEHICLE -> Kind.TRAVEL
            DetectedActivity.ON_BICYCLE -> Kind.BIKE
            DetectedActivity.RUNNING -> Kind.RUN
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Kind.WALK
            else -> null
        }
    }
}
