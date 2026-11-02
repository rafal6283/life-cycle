package pl.rafal.lifecycle.tracking

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Prefs
import java.time.Instant

/**
 * Posiłki z Health Connect (Dziennik talerza, Samsung Health, MyFitnessPal…) jako „Jedzenie” na pierścieniu.
 * Wpisy zapisane w odstępie do 30 min łączą się w jeden posiłek.
 */
object MealImporter {
    val PERMISSION = HealthPermission.getReadPermission(NutritionRecord::class)
    private const val DAY = 24 * 3_600_000L

    private fun mealName(type: Int) = when (type) {
        MealType.MEAL_TYPE_BREAKFAST -> "Śniadanie"
        MealType.MEAL_TYPE_LUNCH -> "Obiad"
        MealType.MEAL_TYPE_DINNER -> "Kolacja"
        MealType.MEAL_TYPE_SNACK -> "Przekąska"
        else -> "Posiłek"
    }

    private fun appName(pkg: String) = when {
        pkg.contains("dziennik") -> "Dziennik talerza"
        pkg.contains("samsung") -> "Samsung Health"
        pkg.contains("myfitnesspal") -> "MyFitnessPal"
        pkg.contains("fitatu") -> "Fitatu"
        pkg.contains("yazio") -> "Yazio"
        else -> null
    }

    /**
     * Czas jedzenia: zapisany w Health Connect, jeśli dłuższy niż 2 min (aplikacja znała prawdziwy czas);
     * inaczej obiad 10–20 min zależnie od wielkości, pozostałe posiłki 3–5 min.
     */
    fun mealLength(r: NutritionRecord): Long {
        val recorded = r.endTime.toEpochMilli() - r.startTime.toEpochMilli()
        if (recorded > 2 * 60_000L) return minOf(recorded, 90 * 60_000L)
        val kcal = r.energy?.inKilocalories ?: 0.0
        val min = when {
            r.mealType == MealType.MEAL_TYPE_LUNCH -> when {
                kcal in 0.1..500.0 -> 10
                kcal < 900 || kcal <= 0.0 -> 15
                else -> 20
            }
            kcal in 0.1..150.0 -> 3
            else -> 5
        }
        return min * 60_000L
    }

    suspend fun foodCategory(ctx: Context): Category {
        val app = ctx.applicationContext as LifeApp
        val all = app.db.categories().getAll()
        all.firstOrNull { it.icon == "food" && it.kind == Kind.CUSTOM }?.let { return it }
        all.firstOrNull { it.name.equals("Jedzenie", true) }?.let { return it }
        val c = Category(name = "Jedzenie", color = 0xFFFF6B22.toInt(), icon = "food")
        return c.copy(id = app.repo.saveCategory(c))
    }

    suspend fun run(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.mealsHc || !HealthConnectHelper.available(ctx)) return
        if (PERMISSION !in HealthConnectHelper.grantedSet(ctx)) return
        val app = ctx.applicationContext as LifeApp
        val client = HealthConnectClient.getOrCreate(ctx)
        val now = System.currentTimeMillis()
        val from = now - 30 * DAY
        val records = mutableListOf<NutritionRecord>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(
                    NutritionRecord::class,
                    TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(now + DAY)),
                    pageToken = token,
                )
            )
            records += res.records
            token = res.pageToken
        } while (token != null && records.size < 5000)

        // Dziennik talerza i inne aplikacje zapisują zwykle tylko chwilę posiłku (1 min) – czas jedzenia szacujemy.
        fun endOf(r: NutritionRecord) = r.startTime.toEpochMilli() + mealLength(r)
        val groups = mutableListOf<MutableList<NutritionRecord>>()
        for (r in records.sortedBy { it.startTime }) {
            val last = groups.lastOrNull()
            // kolejne pozycje tego samego posiłku (np. zupa i drugie danie) – do 15 min po końcu poprzedniej
            if (last != null && r.startTime.toEpochMilli() - last.maxOf { endOf(it) } < 15 * 60_000L) last += r
            else groups += mutableListOf(r)
        }
        val cat = foodCategory(ctx)
        app.db.entries().deleteSourceIn(Source.MEAL_HC, from, now + DAY)
        for (g in groups) {
            val start = g.minOf { it.startTime.toEpochMilli() }
            val end = g.maxOf { endOf(it) }
            val kcal = g.sumOf { it.energy?.inKilocalories ?: 0.0 }
            val type = g.map { it.mealType }.firstOrNull { it != MealType.MEAL_TYPE_UNKNOWN } ?: MealType.MEAL_TYPE_UNKNOWN
            val names = g.mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct()
            val origin = appName(g.first().metadata.dataOrigin.packageName)
            val note = buildString {
                append(mealName(type))
                if (kcal > 0) append(" · ${kcal.toInt()} kcal")
                if (names.isNotEmpty()) append(" · ").append(names.take(4).joinToString(", "))
                if (origin != null) append(" (z $origin)")
            }
            app.db.entries().insert(
                Entry(startAt = start, endAt = minOf(end, now.coerceAtLeast(start + 60_000L)), categoryId = cat.id, source = Source.MEAL_HC, note = note)
            )
        }
    }
}
