package pl.rafal.lifecycle.tracking

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.HealthSample
import pl.rafal.lifecycle.domain.Metric
import pl.rafal.lifecycle.util.Fmt
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import kotlin.reflect.KClass

/** Pobiera pomiary zdrowotne z Health Connect do lokalnej bazy (pierwszy raz rok wstecz, potem przyrostowo). */
object HealthSync {
    private const val DAY = 24 * 3_600_000L
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    val recordTypes: Map<Metric, KClass<out Record>> = mapOf(
        Metric.HR to HeartRateRecord::class,
        Metric.RESTING_HR to RestingHeartRateRecord::class,
        Metric.HRV to HeartRateVariabilityRmssdRecord::class,
        Metric.BP to BloodPressureRecord::class,
        Metric.WEIGHT to WeightRecord::class,
        Metric.BODY_FAT to BodyFatRecord::class,
        Metric.LEAN_MASS to LeanBodyMassRecord::class,
        Metric.WATER_MASS to BodyWaterMassRecord::class,
        Metric.BONE_MASS to BoneMassRecord::class,
        Metric.BMR to BasalMetabolicRateRecord::class,
        Metric.HEIGHT to HeightRecord::class,
        Metric.SPO2 to OxygenSaturationRecord::class,
        Metric.RESP to RespiratoryRateRecord::class,
        Metric.TEMP to BodyTemperatureRecord::class,
        Metric.GLUCOSE to BloodGlucoseRecord::class,
        Metric.VO2 to Vo2MaxRecord::class,
        Metric.ACTIVE_KCAL to ActiveCaloriesBurnedRecord::class,
        Metric.TOTAL_KCAL to TotalCaloriesBurnedRecord::class,
        Metric.FLOORS to FloorsClimbedRecord::class,
        Metric.WATER to HydrationRecord::class,
    )

    val PERMISSIONS: Set<String> = recordTypes.values.map { HealthPermission.getReadPermission(it) }.toSet() + HISTORY

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("health_sync", Context.MODE_PRIVATE)

    suspend fun run(ctx: Context, full: Boolean = false): Int {
        if (!HealthConnectHelper.available(ctx)) return 0
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = HealthConnectHelper.grantedSet(ctx)
        val db = (ctx.applicationContext as LifeApp).db
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        var total = 0
        for ((metric, type) in recordTypes) {
            if (HealthPermission.getReadPermission(type) !in granted) continue
            val last = p.getLong(metric.key, 0L)
            val fromDay = if (full || last == 0L) LocalDate.now().minusDays(365)
            else Fmt.toLocalDate(last).minusDays(3)
            val from = Fmt.dayStart(fromDay)
            try {
                val list = read(client, metric, from, now)
                db.health().deleteRange(metric.key, from, now + DAY)
                db.health().upsert(list)
                total += list.size
                p.edit().putLong(metric.key, now).apply()
            } catch (e: Exception) {
                Log.w(TrackingService.TAG, "Health Connect: ${metric.key}", e)
            }
        }
        try {
            deriveBody(ctx)
        } catch (e: Exception) {
            Log.w(TrackingService.TAG, "Health Connect: wyliczenia składu ciała", e)
        }
        return total
    }

    /**
     * BMI, masa tłuszczowa i % wody — liczone przy każdym pomiarze wagi z pomiarów z tej samej chwili
     * (waga Fitdays i podobne zapisują je razem) albo z tego samego dnia. Wzrost: z Health Connect lub z ustawień.
     */
    suspend fun deriveBody(ctx: Context) {
        val db = (ctx.applicationContext as LifeApp).db
        val now = System.currentTimeMillis()
        val from = now - 400 * DAY
        val weights = db.health().range(Metric.WEIGHT.key, from, now + DAY)
        if (weights.isEmpty()) return
        val fats = db.health().range(Metric.BODY_FAT.key, from, now + DAY)
        val waters = db.health().range(Metric.WATER_MASS.key, from, now + DAY)
        val heights = db.health().range(Metric.HEIGHT.key, 0L, now + DAY)
        val prefHeight = pl.rafal.lifecycle.util.Prefs(ctx).heightCm
        fun near(list: List<HealthSample>, t: Long): HealthSample? {
            val best = list.minByOrNull { kotlin.math.abs(it.time - t) } ?: return null
            return if (kotlin.math.abs(best.time - t) <= 10 * 60_000L || Fmt.toLocalDate(best.time) == Fmt.toLocalDate(t)) best else null
        }
        val out = ArrayList<HealthSample>()
        for (w in weights) {
            val h = heights.filter { it.time <= w.time + DAY }.maxByOrNull { it.time }?.value
                ?: heights.lastOrNull()?.value ?: prefHeight.takeIf { it > 0 }?.toDouble()
            if (h != null && h > 50) out += HealthSample(Metric.BMI.key, w.time, w.value / ((h / 100) * (h / 100)))
            near(fats, w.time)?.let { out += HealthSample(Metric.FAT_MASS.key, w.time, w.value * it.value / 100) }
            near(waters, w.time)?.let { out += HealthSample(Metric.WATER_PCT.key, w.time, it.value / w.value * 100) }
        }
        for (m in listOf(Metric.BMI, Metric.FAT_MASS, Metric.WATER_PCT)) db.health().deleteRange(m.key, from, now + DAY)
        db.health().upsert(out)
    }

    private fun range(from: Long, to: Long) =
        TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))

    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, from: Long, to: Long): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        var pages = 0
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(type, range(from, to), pageSize = 2000, pageToken = token)
            )
            out += resp.records
            token = resp.pageToken
            pages++
        } while (!token.isNullOrEmpty() && pages < 50)
        return out
    }

    private suspend fun read(client: HealthConnectClient, m: Metric, from: Long, to: Long): List<HealthSample> {
        val k = m.key
        return when (m) {
            Metric.HR -> client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                    timeRangeFilter = range(from, to),
                    timeRangeSlicer = Duration.ofHours(1),
                )
            ).mapNotNull { g ->
                val avg = g.result[HeartRateRecord.BPM_AVG] ?: return@mapNotNull null
                HealthSample(
                    k, g.startTime.toEpochMilli(), avg.toDouble(),
                    g.result[HeartRateRecord.BPM_MIN]?.toDouble(), g.result[HeartRateRecord.BPM_MAX]?.toDouble(),
                )
            }
            Metric.ACTIVE_KCAL, Metric.TOTAL_KCAL, Metric.FLOORS, Metric.WATER -> {
                val metric = when (m) {
                    Metric.ACTIVE_KCAL -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                    Metric.TOTAL_KCAL -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
                    Metric.FLOORS -> FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
                    else -> HydrationRecord.VOLUME_TOTAL
                }
                val start = Instant.ofEpochMilli(from).atZone(Fmt.zone).toLocalDateTime()
                val end = Instant.ofEpochMilli(to).atZone(Fmt.zone).toLocalDate().plusDays(1).atStartOfDay()
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(metric),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                ).mapNotNull { g ->
                    val v: Double = when (m) {
                        Metric.ACTIVE_KCAL -> g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                        Metric.TOTAL_KCAL -> g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                        Metric.FLOORS -> g.result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
                        else -> g.result[HydrationRecord.VOLUME_TOTAL]?.inLiters
                    } ?: return@mapNotNull null
                    if (v <= 0.0) null
                    else HealthSample(k, g.startTime.atZone(Fmt.zone).toInstant().toEpochMilli(), v)
                }
            }
            Metric.RESTING_HR -> readAll(client, RestingHeartRateRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) }
            Metric.HRV -> readAll(client, HeartRateVariabilityRmssdRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.heartRateVariabilityMillis) }
            Metric.BP -> readAll(client, BloodPressureRecord::class, from, to)
                .map {
                    HealthSample(
                        k, it.time.toEpochMilli(),
                        it.systolic.inMillimetersOfMercury, it.diastolic.inMillimetersOfMercury,
                    )
                }
            Metric.WEIGHT -> readAll(client, WeightRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.weight.inKilograms) }
            Metric.BODY_FAT -> readAll(client, BodyFatRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.percentage.value) }
            Metric.SPO2 -> readAll(client, OxygenSaturationRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.percentage.value) }
            Metric.RESP -> readAll(client, RespiratoryRateRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.rate) }
            Metric.TEMP -> readAll(client, BodyTemperatureRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.temperature.inCelsius) }
            Metric.GLUCOSE -> readAll(client, BloodGlucoseRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.level.inMilligramsPerDeciliter) }
            Metric.VO2 -> readAll(client, Vo2MaxRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.vo2MillilitersPerMinuteKilogram) }
            Metric.LEAN_MASS -> readAll(client, LeanBodyMassRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.mass.inKilograms) }
            Metric.WATER_MASS -> readAll(client, BodyWaterMassRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.mass.inKilograms) }
            Metric.BONE_MASS -> readAll(client, BoneMassRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.mass.inKilograms) }
            Metric.BMR -> readAll(client, BasalMetabolicRateRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.basalMetabolicRate.inKilocaloriesPerDay) }
            Metric.HEIGHT -> readAll(client, HeightRecord::class, from, to)
                .map { HealthSample(k, it.time.toEpochMilli(), it.height.inMeters * 100) }
            Metric.BMI, Metric.FAT_MASS, Metric.WATER_PCT -> emptyList()
        }.distinctBy { it.time }
    }
}
