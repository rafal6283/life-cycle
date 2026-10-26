package pl.rafal.lifecycle.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.StepBucket
import pl.rafal.lifecycle.data.StepSource
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Steps {
    fun hourStart(t: Long): Long =
        Instant.ofEpochMilli(t).atZone(Fmt.zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /** Kroki na godzinę: z każdej godziny wyższa wartość z telefonu i Health Connect. */
    fun hourly(buckets: List<StepBucket>): Map<Long, Long> =
        buckets.groupBy { it.hourStart }.mapValues { (_, l) -> l.maxOf { it.steps } }

    fun total(buckets: List<StepBucket>): Long = hourly(buckets).values.sum()

    fun perDay(buckets: List<StepBucket>): Map<LocalDate, Long> =
        hourly(buckets).entries.groupBy { Fmt.toLocalDate(it.key) }.mapValues { (_, l) -> l.sumOf { it.value } }

    /** 24 wartości dla godzin danego dnia. */
    fun hoursOfDay(buckets: List<StepBucket>, day: LocalDate): List<Long> {
        val h = hourly(buckets)
        val out = MutableList(24) { 0L }
        for ((start, v) in h) {
            val z = Instant.ofEpochMilli(start).atZone(Fmt.zone)
            if (z.toLocalDate() == day) out[z.hour] += v
        }
        return out
    }
}

/**
 * Zamienia skumulowany licznik kroków z czujnika telefonu na kroki w godzinach.
 *
 * Czujnik oddaje sumę kroków od uruchomienia telefonu. Zdarzenia przychodzą w paczkach i były
 * przetwarzane równolegle — starsza wartość po nowszej wyglądała jak restart telefonu i do dnia
 * dopisywał się cały licznik od uruchomienia (stąd 65 000 kroków). Teraz:
 * – restart rozpoznajemy po liczniku uruchomień systemu, nie po spadku wartości,
 * – zdarzenia starsze od ostatnio przetworzonego są pomijane,
 * – przyrost nie może przekroczyć tego, co da się przejść w danym czasie.
 */
object StepsRecorder {
    private val mutex = Mutex()

    /** Najszybszy realny marsz/bieg: ~4 kroki na sekundę. */
    private const val MAX_STEPS_PER_SEC = 4.0

    private fun bootCount(ctx: Context): Int = try {
        android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }

    /** Chwila uruchomienia telefonu (ms), zaokrąglona do minuty — zapas, gdy brak licznika uruchomień. */
    private fun bootMinute(): Long = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000

    suspend fun onCounter(ctx: Context, value: Long, time: Long, eventNanos: Long) = mutex.withLock {
        val prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val boot = bootCount(ctx)
        val bootMin = bootMinute()
        val lastBoot = prefs.getInt("stepBoot", Int.MIN_VALUE)
        val lastBootMin = prefs.getLong("stepBootMin", Long.MIN_VALUE)
        val last = prefs.getLong("stepLast", -1L)
        val lastNanos = prefs.getLong("stepLastNs", -1L)

        val rebooted = if (boot >= 0 && lastBoot != Int.MIN_VALUE) boot != lastBoot
        else lastBootMin != Long.MIN_VALUE && kotlin.math.abs(bootMin - lastBootMin) > 2

        fun save() = prefs.edit()
            .putLong("stepLast", value).putLong("stepLastNs", eventNanos)
            .putInt("stepBoot", boot).putLong("stepBootMin", bootMin)
            .apply()

        // Pierwszy odczyt (albo pierwsza wersja z tą poprawką) — tylko punkt odniesienia.
        if (last < 0 || lastNanos < 0 || (lastBoot == Int.MIN_VALUE && lastBootMin == Long.MIN_VALUE)) {
            save(); return@withLock
        }

        val delta: Long
        val seconds: Double
        if (rebooted) {
            // Po restarcie licznik liczy od zera — wartość to kroki od uruchomienia.
            delta = value
            seconds = SystemClock.elapsedRealtime() / 1000.0
        } else {
            if (eventNanos <= lastNanos) return@withLock          // stare zdarzenie z paczki
            if (value < last) { save(); return@withLock }          // nie powinno się zdarzyć — nowy punkt odniesienia
            delta = value - last
            seconds = (eventNanos - lastNanos) / 1e9
        }
        save()
        if (delta <= 0) return@withLock
        if (delta > seconds * MAX_STEPS_PER_SEC + 50) {
            Log.w(TrackingService.TAG, "Pominięto nierealny przyrost kroków: $delta w ${seconds.toLong()} s")
            return@withLock
        }
        val db = (ctx.applicationContext as LifeApp).db
        val hour = Steps.hourStart(time)
        db.steps().ensure(hour, StepSource.PHONE)
        db.steps().add(hour, StepSource.PHONE, delta)
    }

    /** Usuwa kroki z czujnika telefonu zapisane przed poprawką (wcześniej mogły być zawyżone). */
    suspend fun clearPhoneBefore(ctx: Context, before: Long) {
        val db = (ctx.applicationContext as LifeApp).db
        db.steps().deleteSourceIn(StepSource.PHONE, 0L, before)
        try {
            StepsImporter.run(ctx)
        } catch (_: Exception) {
        }
    }

    /** Godzina pierwszego uruchomienia wersji z poprawką; starsze kroki z telefonu są podejrzane. */
    fun fixedSince(ctx: Context): Long {
        val prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val v = prefs.getLong("stepFixAt", 0L)
        if (v > 0) return v
        val now = Steps.hourStart(System.currentTimeMillis())
        prefs.edit().putLong("stepFixAt", now).apply()
        return now
    }
}

class StepSensor(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var registered = false

    fun start() {
        if (registered || !TrackingControl.hasActivity(ctx)) return
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        // Zdarzenia zbierane w paczki co ~2 min — oszczędza baterię.
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 120_000_000)
    }

    fun stop() {
        if (registered) sm?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull()?.toLong() ?: return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val time = System.currentTimeMillis() - ageMs.coerceIn(0L, 3_600_000L)
        val app = ctx.applicationContext as LifeApp
        val nanos = event.timestamp
        app.appScope.launch { StepsRecorder.onCounter(ctx, value, time, nanos) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun available(ctx: Context): Boolean =
            ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
}

/** Kroki z Health Connect (zegarek, Google Fit, Samsung Health…) w godzinach. */
object StepsImporter {
    val PERMISSION = HealthPermission.getReadPermission(StepsRecord::class)

    suspend fun run(ctx: Context) {
        if (!Prefs(ctx).stepsHc || !HealthConnectHelper.available(ctx)) return
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return
        }
        if (PERMISSION !in granted) return
        val db = (ctx.applicationContext as LifeApp).db
        val now = System.currentTimeMillis()
        val from = Steps.hourStart(Fmt.dayStart(LocalDate.now().minusDays(6)))
        val to = Steps.hourStart(now) + 3_600_000L
        try {
            val groups = client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                    timeRangeSlicer = Duration.ofHours(1),
                )
            )
            val buckets = groups.mapNotNull { g ->
                val n = g.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                if (n <= 0) null else StepBucket(Steps.hourStart(g.startTime.toEpochMilli()), StepSource.HC, n)
            }
            db.steps().deleteSourceIn(StepSource.HC, from, to)
            db.steps().upsert(buckets)
        } catch (e: Exception) {
            Log.w(TrackingService.TAG, "Odczyt kroków z Health Connect nie powiódł się", e)
        }
    }
}
