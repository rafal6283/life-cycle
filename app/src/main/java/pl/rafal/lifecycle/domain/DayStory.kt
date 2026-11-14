package pl.rafal.lifecycle.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.tracking.Steps
import pl.rafal.lifecycle.tracking.WeatherSync
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate
import kotlin.math.roundToInt

/** Krótki opis dnia pisany z danych — pamiętnik bez pisania. */
object DayStory {
    private var tripsCache: Pair<Long, List<Trip>>? = null

    private suspend fun trips(ctx: Context): List<Trip> {
        val now = System.currentTimeMillis()
        tripsCache?.let { if (now - it.first < 120_000L) return it.second }
        return Trips.detect(ctx).trips.also { tripsCache = now to it }
    }
    suspend fun build(ctx: Context, day: LocalDate): String? = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val from = Fmt.dayStart(day)
        val to = Fmt.dayStart(day.plusDays(1))
        val now = System.currentTimeMillis()
        if (from > now) return@withContext null
        val entries = app.repo.getRange(from - 12 * 3_600_000L, to)
        val cats = db.categories().getAll().associateBy { it.id }
        val places = db.places().getAll().associateBy { it.id }
        val segs = Timeline.resolve(entries.filter { it.startAt < to && (it.endAt ?: now) > from }, from, to, now)
        if (segs.isEmpty()) return@withContext null
        val totals = Timeline.totals(segs)
        val parts = mutableListOf<String>()
        fun kindTime(vararg k: String) = totals.filterKeys { cats[it]?.kind in k }.values.sum()

        // Pobudka i sen
        val sleepCat = cats.values.firstOrNull { it.kind == Kind.SLEEP }?.id
        val night = sleepCat?.let { Trends.nights(entries, it, day, day.plusDays(1), now).firstOrNull() }
        if (night != null) {
            parts += "Wstałeś o ${Fmt.time(night.end)} po ${Fmt.durationShort(night.end - night.start)} snu"
        }

        // Praca
        val wd = WorkTime.day(ctx, entries, cats, day, now)
        if (wd != null) {
            parts += "w pracy od ${Fmt.time(wd.start)} do ${Fmt.time(wd.end)} (${Fmt.durationShort(wd.workedMs)})"
        }
        val visits = entries.count { cats[it.categoryId]?.kind == Kind.FIELD && it.startAt in from until to }
        if (visits > 0) parts += "$visits ${if (visits == 1) "wizyta" else if (visits in 2..4) "wizyty" else "wizyt"} u klientów"

        // Ruch
        val km = entries.filter { it.source == Source.AUTO_MOVE && it.startAt in from until to }.sumOf { it.distanceM ?: 0.0 } / 1000
        val walk = kindTime(Kind.WALK)
        val run = kindTime(Kind.RUN)
        val bike = kindTime(Kind.BIKE)
        val gym = kindTime(Kind.WORKOUT)
        val moveBits = listOfNotNull(
            walk.takeIf { it >= 10 * 60_000L }?.let { "spacer ${Fmt.durationShort(it)}" },
            run.takeIf { it >= 5 * 60_000L }?.let { "bieg ${Fmt.durationShort(it)}" },
            bike.takeIf { it >= 5 * 60_000L }?.let { "rower ${Fmt.durationShort(it)}" },
            gym.takeIf { it >= 10 * 60_000L }?.let { "trening ${Fmt.durationShort(it)}" },
        )
        if (moveBits.isNotEmpty()) parts += moveBits.joinToString(", ")
        if (km >= 1) parts += "w drodze ${km.roundToInt()} km"
        val steps = Steps.total(db.steps().getRange(from, to))
        if (steps >= 500) parts += "${Fmt.steps(steps)} kroków"

        // Miejsca poza domem i pracą
        val visited = segs.mapNotNull { s -> s.entry.placeId?.let { places[it] } }
            .filter { cats[it.categoryId]?.kind !in setOf(Kind.HOME, Kind.WORK, Kind.SLEEP) }
            .map { it.name }.distinct()
        if (visited.isNotEmpty()) parts += "byłeś: " + visited.take(4).joinToString(", ")
        trips(ctx).firstOrNull { it.start < to && it.end > from }?.let { parts += "na wyjeździe (${it.name})" }

        // Telefon, pogoda, nastrój
        db.screen().range(day.toEpochDay(), day.toEpochDay() + 1).firstOrNull()?.let {
            if (it.screenMs >= 15 * 60_000L) parts += "${Fmt.durationShort(it.screenMs)} przed ekranem"
        }
        db.weather().range(day.toEpochDay(), day.toEpochDay() + 1).firstOrNull()?.let { w ->
            parts += "${WeatherSync.label(w.code).ifBlank { "pogoda" }}, ${w.tMax.roundToInt()}°" +
                if (w.precip >= 1) " i ${w.precip.roundToInt()} mm deszczu" else ""
        }
        db.mood().get(day.toEpochDay())?.let { m ->
            parts += "nastrój ${Mood.labels[m.value - 1].lowercase()} ${Mood.faces[m.value - 1]}" + (m.note?.let { " — „$it”" } ?: "")
        }
        if (parts.isEmpty()) return@withContext null
        val text = parts.joinToString(", ")
        text.replaceFirstChar { it.uppercase() } + "."
    }
}
