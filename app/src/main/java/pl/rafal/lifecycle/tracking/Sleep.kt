package pl.rafal.lifecycle.tracking

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.Instant
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

object HealthConnectHelper {
    val SLEEP_PERMISSIONS = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
    val WORKOUT_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )
    /** Pozwala czytać dane, gdy Doba działa w tle (Android 14+). */
    const val BACKGROUND_READ = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val PERMISSIONS = SLEEP_PERMISSIONS + WORKOUT_PERMISSIONS +
        setOf(HealthPermission.getReadPermission(StepsRecord::class)) + BACKGROUND_READ

    /** Wszystkie zgody poza odczytem w tle (nie każdy telefon go obsługuje). */
    val CORE_PERMISSIONS = PERMISSIONS - BACKGROUND_READ

    suspend fun grantedSet(ctx: Context): Set<String> = granted(ctx)

    fun status(ctx: Context): Int = HealthConnectClient.getSdkStatus(ctx)

    fun available(ctx: Context) = status(ctx) == HealthConnectClient.SDK_AVAILABLE

    private suspend fun granted(ctx: Context): Set<String> = try {
        if (available(ctx)) HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        else emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Czy przyznano wszystkie zgody (sen + treningi). */
    suspend fun hasPermission(ctx: Context): Boolean = granted(ctx).containsAll(CORE_PERMISSIONS)
    suspend fun hasSleep(ctx: Context): Boolean = granted(ctx).containsAll(SLEEP_PERMISSIONS)
    suspend fun hasWorkouts(ctx: Context): Boolean =
        granted(ctx).contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))

    data class Workout(
        val start: Long, val end: Long, val type: Int, val title: String?, val distanceM: Double?,
        val app: String = "",
    )

    suspend fun readWorkouts(ctx: Context, from: Long, to: Long): List<Workout>? = try {
        val client = HealthConnectClient.getOrCreate(ctx)
        val resp = client.readRecords(
            ReadRecordsRequest(
                ExerciseSessionRecord::class,
                TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
            )
        )
        resp.records.map { r ->
            val dist = try {
                client.aggregate(
                    AggregateRequest(
                        setOf(DistanceRecord.DISTANCE_TOTAL),
                        TimeRangeFilter.between(r.startTime, r.endTime),
                    )
                )[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            } catch (e: Exception) {
                null
            }
            Workout(
                r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.exerciseType, r.title, dist,
                r.metadata.dataOrigin.packageName,
            )
        }
    } catch (e: Exception) {
        Log.w(TrackingService.TAG, "Odczyt treningów z Health Connect nie powiódł się", e)
        null
    }

    /** Zwraca sesje snu (start, koniec) albo null przy błędzie. */
    suspend fun readSleep(ctx: Context, from: Long, to: Long): List<Pair<Long, Long>>? = try {
        val client = HealthConnectClient.getOrCreate(ctx)
        val resp = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
            )
        )
        resp.records.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() }
    } catch (e: Exception) {
        Log.w(TrackingService.TAG, "Odczyt snu z Health Connect nie powiódł się", e)
        null
    }
}

/**
 * Sen: najpierw z Health Connect (zegarek, Sleep as Android itp.),
 * a gdy go brak — z najdłuższej nocnej przerwy w używaniu telefonu.
 */
object SleepDetector {
    private const val H = 3_600_000L

    suspend fun run(ctx: Context) {
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val sleepCat = app.repo.categoryByKind(Kind.SLEEP)
        val from = now - 5 * 24 * H
        val hc = mutableListOf<Pair<Long, Long>>()

        if (prefs.sleepHc && HealthConnectHelper.hasSleep(ctx)) {
            val sessions = HealthConnectHelper.readSleep(ctx, from, now)
            if (sessions != null) {
                db.entries().deleteSourceIn(Source.SLEEP_HC, from, now + H)
                sessions.filter { it.first >= from && it.second > it.first }.forEach { (s, e) ->
                    db.entries().insert(
                        Entry(startAt = s, endAt = e, categoryId = sleepCat.id, source = Source.SLEEP_HC)
                    )
                }
                hc += sessions
            }
        }

        if (!prefs.sleepAuto) {
            db.entries().deleteSourceIn(Source.SLEEP_AUTO, from, now + H)
            return
        }

        val today = LocalDate.now()
        for (i in 4 downTo 0) {
            val d = today.minusDays(i.toLong())
            val wStart = d.atTime(19, 0).atZone(Fmt.zone).toInstant().toEpochMilli()
            val wEnd = d.plusDays(1).atTime(13, 0).atZone(Fmt.zone).toInstant().toEpochMilli()
            if (wStart > now) continue
            db.entries().deleteSourceIn(Source.SLEEP_AUTO, wStart, wEnd)
            if (hc.any { it.first < wEnd && it.second > wStart }) continue
            if (db.entries().countCatSourceIn(sleepCat.id, Source.MANUAL, wStart, wEnd) > 0) continue

            val offs = db.screenOff().range(wStart, wEnd)
                .map { max(it.startAt, wStart) to min(it.endAt, wEnd) }
                .sortedBy { it.first }
            val merged = mutableListOf<Pair<Long, Long>>()
            for (iv in offs) {
                val last = merged.lastOrNull()
                if (last != null && iv.first - last.second < 15 * 60_000L) {
                    merged[merged.size - 1] = last.first to max(last.second, iv.second)
                } else {
                    merged += iv
                }
            }
            val best = merged.maxByOrNull { it.second - it.first } ?: continue
            if (best.second - best.first >= 3 * H) {
                db.entries().insert(
                    Entry(startAt = best.first, endAt = best.second, categoryId = sleepCat.id, source = Source.SLEEP_AUTO)
                )
            }
        }
    }
}

/** Treningi z zegarka (przez Health Connect) jako dokładne wpisy na osi czasu. */
object WorkoutImporter {
    private const val DAY = 24 * 3_600_000L

    private fun kindFor(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Kind.RUN
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> Kind.BIKE
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> Kind.WALK
        else -> Kind.WORKOUT
    }

    suspend fun run(ctx: Context) {
        val app = ctx.applicationContext as LifeApp
        if (!Prefs(ctx).workoutsHc || !HealthConnectHelper.hasWorkouts(ctx)) return
        val now = System.currentTimeMillis()
        val from = now - 30 * DAY
        val list = HealthConnectHelper.readWorkouts(ctx, from, now) ?: return
        val db = app.db
        db.entries().deleteSourceIn(Source.WORKOUT_HC, from, now + DAY)
        for (w in list) {
            if (w.start < from || w.end <= w.start) continue
            val cat = app.repo.categoryByKind(kindFor(w.type))
            db.entries().insert(
                Entry(
                    startAt = w.start, endAt = w.end, categoryId = cat.id,
                    source = Source.WORKOUT_HC, note = w.title?.takeIf { it.isNotBlank() },
                    distanceM = w.distanceM,
                )
            )
        }
    }
}
