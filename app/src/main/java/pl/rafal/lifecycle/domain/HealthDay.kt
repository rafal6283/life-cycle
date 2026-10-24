package pl.rafal.lifecycle.domain

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.HealthSample
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.tracking.HealthConnectHelper
import pl.rafal.lifecycle.util.Fmt
import java.time.Duration
import java.time.Instant

/** Pojedynczy odczyt na osi czasu dnia. Dla sum (kalorie…) — wartość z godziny, time = początek godziny. */
data class Pt(val time: Long, val value: Double, val v2: Double? = null)

/** Aktywność (z osi czasu Doby) w danym przedziale — do paska pod wykresem i średnich wg aktywności. */
data class ActSpan(val start: Long, val end: Long, val cat: Category?)

data class DayDetail(
    val points: List<Pt>,
    val spans: List<ActSpan>,
    /** true = dane godzinowe (słupki), false = odczyty punktowe / ciągłe. */
    val hourly: Boolean,
    val fromHc: Boolean,
)

data class Zone(val label: String, val from: Double, val to: Double, val color: Long)

object HealthDay {
    private const val HOUR = 3_600_000L

    val hrZones = listOf(
        Zone("< 60", 0.0, 60.0, 0xFF5E5CE6),
        Zone("60–99", 60.0, 100.0, 0xFF34C759),
        Zone("100–119", 100.0, 120.0, 0xFFFFCC00),
        Zone("120–149", 120.0, 150.0, 0xFFFF9500),
        Zone("≥ 150", 150.0, 999.0, 0xFFFF3B30),
    )

    private fun range(from: Long, to: Long) =
        TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))

    /** Dane z [from, to): tętno i sumy prosto z Health Connect (pełna rozdzielczość), reszta z bazy. */
    suspend fun load(ctx: Context, m: Metric, from: Long, to: Long): DayDetail = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as LifeApp
        val cats = app.db.categories().getAll().associateBy { it.id }
        val now = System.currentTimeMillis()
        val spans = Timeline.resolve(app.repo.getRange(from, to), from, to, now)
            .map { ActSpan(it.start, it.end, cats[it.entry.categoryId]) }
        val hc = HealthConnectHelper.available(ctx)
        val client = if (hc) HealthConnectClient.getOrCreate(ctx) else null
        var fromHc = false
        val hourly = m.agg == DayAgg.SUM
        val points: List<Pt> = try {
            when {
                client != null && m == Metric.HR -> {
                    val out = ArrayList<Pt>()
                    var token: String? = null
                    var pages = 0
                    do {
                        val r = client.readRecords(
                            ReadRecordsRequest(HeartRateRecord::class, range(from, to), pageSize = 1000, pageToken = token)
                        )
                        r.records.forEach { rec ->
                            rec.samples.forEach { s ->
                                val t = s.time.toEpochMilli()
                                if (t in from until to) out += Pt(t, s.beatsPerMinute.toDouble())
                            }
                        }
                        token = r.pageToken
                        pages++
                    } while (!token.isNullOrEmpty() && pages < 30)
                    fromHc = true
                    downsample(out.sortedBy { it.time }.distinctBy { it.time })
                }
                client != null && hourly -> {
                    val metric = when (m) {
                        Metric.ACTIVE_KCAL -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                        Metric.TOTAL_KCAL -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
                        Metric.FLOORS -> FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
                        else -> HydrationRecord.VOLUME_TOTAL
                    }
                    fromHc = true
                    client.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(setOf(metric), range(from, to), Duration.ofHours(1))
                    ).mapNotNull { g ->
                        val v: Double = when (m) {
                            Metric.ACTIVE_KCAL -> g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                            Metric.TOTAL_KCAL -> g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                            Metric.FLOORS -> g.result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
                            else -> g.result[HydrationRecord.VOLUME_TOTAL]?.inLiters
                        } ?: return@mapNotNull null
                        if (v > 0) Pt(g.startTime.toEpochMilli(), v) else null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: fromDb(app, m, from, to)
        DayDetail(points, spans, hourly, fromHc)
    }

    private suspend fun fromDb(app: LifeApp, m: Metric, from: Long, to: Long): List<Pt> =
        app.db.health().range(m.key, from, to).map { Pt(it.time, it.value, it.v2) }

    /** Przy bardzo gęstych danych (np. trening) — średnie minutowe. */
    private fun downsample(l: List<Pt>): List<Pt> {
        if (l.size <= 1500) return l
        return l.groupBy { it.time / 60_000L }.map { (k, g) -> Pt(k * 60_000L, g.map { it.value }.average()) }
    }

    /** Czas w strefach tętna (każdy odczyt „trwa” do następnego, maks. 5 min). */
    fun zones(points: List<Pt>): List<Pair<Zone, Long>> {
        val acc = LongArray(hrZones.size)
        points.forEachIndexed { i, p ->
            val next = points.getOrNull(i + 1)?.time ?: (p.time + 60_000L)
            val dur = (next - p.time).coerceIn(0L, 5 * 60_000L)
            val z = hrZones.indexOfFirst { p.value >= it.from && p.value < it.to }
            if (z >= 0) acc[z] += dur
        }
        return hrZones.mapIndexed { i, z -> z to acc[i] }
    }

    /** Średnia wartość wg aktywności z osi czasu (np. Sen 54, Praca 78, Dojazd 86). */
    fun byActivity(points: List<Pt>, spans: List<ActSpan>): List<Triple<Category?, Double, Int>> {
        val groups = HashMap<Long?, MutableList<Double>>()
        val catOf = HashMap<Long?, Category?>()
        for (p in points) {
            val s = spans.firstOrNull { p.time >= it.start && p.time < it.end } ?: continue
            val k = s.cat?.id
            groups.getOrPut(k) { ArrayList() } += p.value
            catOf[k] = s.cat
        }
        return groups.map { (k, v) -> Triple(catOf[k], v.average(), v.size) }.sortedByDescending { it.third }
    }

    fun activityAt(spans: List<ActSpan>, t: Long): Category? = spans.firstOrNull { t >= it.start && t < it.end }?.cat

    fun isSleep(c: Category?) = c?.kind == Kind.SLEEP

    /** Profil doby: dla każdej godziny 0–23 średnia, typowe minimum i maksimum (z wielu dni). */
    data class HourStat(val hour: Int, val avg: Double, val lo: Double, val hi: Double, val n: Int)

    fun hourProfile(m: Metric, samples: List<HealthSample>, days: Int): List<HourStat> {
        val byHour = samples.groupBy { Instant.ofEpochMilli(it.time).atZone(Fmt.zone).hour }
        return (0 until 24).mapNotNull { h ->
            val l = byHour[h] ?: return@mapNotNull null
            if (m.agg == DayAgg.SUM) {
                val total = l.sumOf { it.value }
                HourStat(h, total / days.coerceAtLeast(1), 0.0, 0.0, l.size)
            } else {
                val vals = l.map { it.value }.sorted()
                val lo = l.mapNotNull { it.v2 }.takeIf { m == Metric.HR && it.isNotEmpty() }?.average()
                    ?: vals[(vals.size * 0.1).toInt().coerceAtMost(vals.size - 1)]
                val hi = l.mapNotNull { it.v3 }.takeIf { m == Metric.HR && it.isNotEmpty() }?.average()
                    ?: vals[(vals.size * 0.9).toInt().coerceAtMost(vals.size - 1)]
                HourStat(h, vals.average(), lo, hi, l.size)
            }
        }
    }

    /** Godzinowe sumy z wielu dni (do profilu doby kalorii/pięter/wody) — prosto z Health Connect. */
    suspend fun hourlySums(ctx: Context, m: Metric, from: Long, to: Long): List<HealthSample> = withContext(Dispatchers.IO) {
        if (!HealthConnectHelper.available(ctx)) return@withContext emptyList()
        val client = HealthConnectClient.getOrCreate(ctx)
        val metric = when (m) {
            Metric.ACTIVE_KCAL -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
            Metric.TOTAL_KCAL -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
            Metric.FLOORS -> FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
            Metric.WATER -> HydrationRecord.VOLUME_TOTAL
            else -> return@withContext emptyList()
        }
        try {
            client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(metric), range(from, to), Duration.ofHours(1)))
                .mapNotNull { g ->
                    val v: Double = when (m) {
                        Metric.ACTIVE_KCAL -> g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                        Metric.TOTAL_KCAL -> g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                        Metric.FLOORS -> g.result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
                        else -> g.result[HydrationRecord.VOLUME_TOTAL]?.inLiters
                    } ?: return@mapNotNull null
                    if (v > 0) HealthSample(m.key, g.startTime.toEpochMilli(), v) else null
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
