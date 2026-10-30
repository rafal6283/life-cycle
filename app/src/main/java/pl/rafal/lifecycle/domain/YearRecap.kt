package pl.rafal.lifecycle.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.tracking.ScreenTime
import pl.rafal.lifecycle.tracking.Steps
import pl.rafal.lifecycle.tracking.WeatherSync
import pl.rafal.lifecycle.util.Fmt
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Jedna karta „Twojego roku”. */
data class RecapSlide(val emoji: String, val title: String, val big: String, val sub: String, val color: Long)

object YearRecap {
    private val palette = listOf(0xFF5E5CE6, 0xFF0A84FF, 0xFF30B0C7, 0xFF34C759, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFF6B22, 0xFF8E44AD)

    private fun hours(ms: Long): String = String.format(Fmt.PL, "%,d h", ms / 3_600_000)

    suspend fun build(ctx: Context, year: Int): List<RecapSlide> = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val db = app.db
        val start = LocalDate.of(year, 1, 1)
        val end = minOf(start.plusYears(1), LocalDate.now().plusDays(1))
        val from = Fmt.dayStart(start)
        val to = Fmt.dayStart(end)
        val now = System.currentTimeMillis()
        val entries = app.repo.getRange(from, to)
        val cats = db.categories().getAll().associateBy { it.id }
        val places = db.places().getAll().associateBy { it.id }
        val out = mutableListOf<Pair<String, (Long) -> RecapSlide>>()
        fun add(emoji: String, title: String, big: String, sub: String) {
            out += "" to { c -> RecapSlide(emoji, title, big, sub, c) }
        }
        val segs = Timeline.resolve(entries, from, to, now)
        val totals = Timeline.totals(segs)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }
            .count { d -> segs.any { it.start < Fmt.dayStart(d.plusDays(1)) && it.end > Fmt.dayStart(d) } }
        if (days == 0) return@withContext emptyList()
        add("📅", "Twój rok $year w Dobie", "$days dni", "zapisanych dzień po dniu — oto, co z nich wynika")

        // Sen
        val sleepCat = cats.values.firstOrNull { it.kind == Kind.SLEEP }?.id
        if (sleepCat != null) {
            val nights = Trends.nights(entries, sleepCat, start, end, now)
            if (nights.size >= 5) {
                val total = nights.sumOf { it.end - it.start }
                val longest = nights.maxBy { it.end - it.start }
                add(
                    "🌙", "Przespałeś", hours(total),
                    "średnio ${Fmt.durationShort(total / nights.size)} na noc · najdłużej ${Fmt.durationShort(longest.end - longest.start)} " +
                        "(${longest.day.format(DateTimeFormatter.ofPattern("d MMMM", Fmt.PL))})",
                )
                SleepRhythm.compute(nights)?.let { r ->
                    add("⏰", "Regularność snu", "${r.score}/100", "zasypianie ±${r.bedSdMin} min, pobudka ±${r.wakeSdMin} min")
                }
            }
        }

        // Ruch i dystans
        val moves = entries.filter { it.distanceM != null && it.startAt >= from }
        val km = moves.sumOf { it.distanceM ?: 0.0 } / 1000
        if (km >= 1) {
            val byCat = moves.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.distanceM ?: 0.0 } / 1000 }
                .toList().sortedByDescending { it.second }.take(3)
                .joinToString(" · ") { (id, v) -> "${cats[id]?.name ?: "inne"} ${v.roundToInt()} km" }
            val earth = km / 40_075 * 100
            add(
                "🧭", "Przemierzyłeś", String.format(Fmt.PL, "%,d km", km.roundToInt()),
                byCat + if (earth >= 1) String.format(Fmt.PL, " · to %.0f%% obwodu Ziemi", earth) else "",
            )
        }
        val steps = Steps.perDay(db.steps().getRange(from, to))
        if (steps.isNotEmpty()) {
            val total = steps.values.sum()
            val best = steps.maxBy { it.value }
            add(
                "👟", "Zrobiłeś", "${Fmt.steps(total)} kroków",
                "średnio ${Fmt.steps(total / steps.size)} dziennie · rekord ${Fmt.steps(best.value)} " +
                    "(${best.key.format(DateTimeFormatter.ofPattern("d MMMM", Fmt.PL))})",
            )
        }
        val activeKinds = setOf(Kind.WALK, Kind.RUN, Kind.BIKE, Kind.WORKOUT)
        val active = totals.filterKeys { cats[it]?.kind in activeKinds }.values.sum()
        if (active > 3_600_000L) {
            val workouts = entries.count { it.source == Source.WORKOUT_HC || cats[it.categoryId]?.kind == Kind.WORKOUT }
            add("🔥", "W ruchu", hours(active), (if (workouts > 0) "$workouts treningów · " else "") + "spacery, bieganie, rower i ćwiczenia")
        }

        // Miejsca
        val byPlace = entries.filter { it.placeId != null }.groupBy { it.placeId!! }
            .mapValues { (_, l) -> l.sumOf { (minOf(it.endAt ?: now, to) - maxOf(it.startAt, from)).coerceAtLeast(0) } }
            .toList().sortedByDescending { it.second }
        val home = byPlace.firstOrNull { cats[places[it.first]?.categoryId]?.kind == Kind.HOME }
        val other = byPlace.filter { cats[places[it.first]?.categoryId]?.kind !in setOf(Kind.HOME, Kind.WORK) }.take(3)
        if (home != null) {
            add(
                "🏠", "W domu", hours(home.second),
                if (other.isNotEmpty()) "najczęściej poza domem i pracą: " + other.joinToString(", ") { places[it.first]?.name ?: "" }
                else "",
            )
        }
        val work = totals.filterKeys { cats[it]?.kind == Kind.WORK || cats[it]?.kind == Kind.FIELD }.values.sum()
        if (work > 10 * 3_600_000L) add("💼", "W pracy", hours(work), "to średnio ${Fmt.durationShort(work / days)} na każdy zapisany dzień")

        val sp = ctx.getSharedPreferences("geocode", Context.MODE_PRIVATE)
        val towns = entries.filter { it.source == Source.AUTO_STAY && it.lat != null }.mapNotNull { s ->
            sp.getString("loc:" + String.format(Locale.US, "%.2f,%.2f", s.lat, s.lng), null)?.takeIf { it.isNotBlank() }
        }.toSet()
        val trips = Trips.detect(ctx).trips.filter { Fmt.toLocalDate(it.start).year == year }
        if (trips.isNotEmpty() || towns.size >= 3) {
            add(
                "🧳", "Wyjazdy i miejsca",
                if (trips.isNotEmpty()) "${trips.size} ${if (trips.size == 1) "wyjazd" else if (trips.size in 2..4) "wyjazdy" else "wyjazdów"}" else "${towns.size} miejscowości",
                listOfNotNull(
                    trips.take(4).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                    if (towns.size >= 3 && trips.isNotEmpty()) "${towns.size} odwiedzonych miejscowości" else null,
                ).joinToString(" · "),
            )
        }

        // Telefon
        val screens = db.screen().range(start.toEpochDay(), end.toEpochDay())
        if (screens.size >= 7) {
            val total = screens.sumOf { it.screenMs }
            val app1 = screens.flatMap { it.appList() }.groupBy { it.first }.mapValues { (_, l) -> l.sumOf { it.second } }
                .maxByOrNull { it.value }?.key
            add(
                "📱", "Przed ekranem", hours(total),
                "średnio ${Fmt.durationShort(total / screens.size)} dziennie · ${screens.sumOf { it.unlocks } / screens.size} odblokowań" +
                    (app1?.let { " · najczęściej ${ScreenTime.appLabel(ctx, it)}" } ?: ""),
            )
        }

        // Nastrój i pogoda
        val moods = db.mood().range(start.toEpochDay(), end.toEpochDay())
        if (moods.size >= 7) {
            val avg = moods.map { it.value }.average()
            val bestMonth = moods.groupBy { LocalDate.ofEpochDay(it.day).monthValue }.filterValues { it.size >= 3 }
                .maxByOrNull { (_, l) -> l.map { it.value }.average() }?.key
            add(
                Mood.face(avg), "Nastrój", String.format(Fmt.PL, "%.1f / 5", avg),
                "${moods.size} ocenionych dni" + (bestMonth?.let {
                    " · najlepszy miesiąc: " + LocalDate.of(year, it, 1).format(DateTimeFormatter.ofPattern("LLLL", Fmt.PL))
                } ?: ""),
            )
        }
        val wx = db.weather().range(start.toEpochDay(), end.toEpochDay())
        if (wx.size >= 30) {
            val hot = wx.maxBy { it.tMax }
            add(
                WeatherSync.emoji(hot.code), "Pogoda Twojego roku", "${wx.count { it.precip >= 1.0 }} dni z deszczem",
                "${wx.sumOf { it.sunH }.roundToInt()} h słońca · najgoręcej ${hot.tMax.roundToInt()}° " +
                    "(${LocalDate.ofEpochDay(hot.day).format(DateTimeFormatter.ofPattern("d MMMM", Fmt.PL))})",
            )
        }
        val top = totals.filterKeys { cats[it]?.kind !in setOf(Kind.SLEEP, Kind.UNKNOWN) }.maxByOrNull { it.value }
        if (top != null) {
            add("🏆", "Najwięcej czasu poza snem", cats[top.key]?.name ?: "—", "${hours(top.value)} w całym roku")
        }
        out.mapIndexed { i, (_, f) -> f(palette[i % palette.size]) }
    }

    /** Obraz do udostępnienia (1080×1920) z najważniejszymi liczbami. */
    suspend fun shareImage(ctx: Context, year: Int, slides: List<RecapSlide>): File = withContext(Dispatchers.Default) {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2C2A7A.toInt(), 0xFF0A84FF.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 38f }
        white.textSize = 96f; white.isFakeBoldText = true
        c.drawText("Mój rok $year", 80f, 220f, white)
        dim.textSize = 44f
        c.drawText("w aplikacji Doba", 84f, 290f, dim)
        var y = 420f
        val rows = slides.drop(1).take(9)
        for (s in rows) {
            white.textSize = 54f; white.isFakeBoldText = false
            c.drawText(s.emoji, 80f, y, white)
            white.isFakeBoldText = true; white.textSize = 58f
            c.drawText(s.big, 180f, y, white)
            dim.textSize = 36f
            c.drawText(s.title, 182f, y + 50f, dim)
            y += 150f
        }
        dim.textSize = 32f
        c.drawText("Doba — automatyczny dziennik czasu", 80f, h - 80f, dim)
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "doba-rok-$year.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        f
    }
}
