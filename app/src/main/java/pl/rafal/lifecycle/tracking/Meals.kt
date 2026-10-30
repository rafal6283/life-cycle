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

        val minLen = prefs.mealMinutes * 60_000L
        val groups = mutableListOf<MutableList<NutritionRecord>>()
        for (r in records.sortedBy { it.startTime }) {
            val last = groups.lastOrNull()
            if (last != null && r.startTime.toEpochMilli() - last.maxOf { it.endTime.toEpochMilli() } < 30 * 60_000L) last += r
            else groups += mutableListOf(r)
        }
        val cat = foodCategory(ctx)
        app.db.entries().deleteSourceIn(Source.MEAL_HC, from, now + DAY)
        for (g in groups) {
            val start = g.minOf { it.startTime.toEpochMilli() }
            val end = maxOf(g.maxOf { it.endTime.toEpochMilli() }, start + minLen)
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
