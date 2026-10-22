package pl.rafal.lifecycle.domain

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Goal
import pl.rafal.lifecycle.data.GoalPeriod
import pl.rafal.lifecycle.data.GoalType
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.data.StepBucket
import pl.rafal.lifecycle.tracking.Steps
import pl.rafal.lifecycle.tracking.Notifications
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Cele
// ---------------------------------------------------------------------------

data class GoalProgress(
    val goal: Goal,
    /** Dla MIN/MAX milisekundy, dla COUNT liczba dni. */
    val value: Long,
    val target: Long,
    val met: Boolean,
    val exceeded: Boolean,
) {
    val fraction: Float get() = if (target <= 0) 0f else (value.toFloat() / target).coerceIn(0f, 1.5f)
}

object Goals {
    fun describe(g: Goal): String = when (g.type) {
        GoalType.MIN -> "co najmniej ${Fmt.duration(g.minutes * 60_000L)} " + per(g)
        GoalType.MAX -> "najwyżej ${Fmt.duration(g.minutes * 60_000L)} " + per(g)
        else -> "${g.count}× w tygodniu (min. ${g.minutes} min)"
    }

    private fun per(g: Goal) = if (g.period == GoalPeriod.DAY) "dziennie" else "w tygodniu"

    /** Zakres potrzebnych danych: od poniedziałku do końca dnia [today]. */
    fun weekRange(today: LocalDate): Pair<Long, Long> =
        Fmt.dayStart(today.with(DayOfWeek.MONDAY)) to Fmt.dayStart(today.plusDays(1))

    fun progress(g: Goal, entries: List<Entry>, today: LocalDate, now: Long): GoalProgress {
        val monday = today.with(DayOfWeek.MONDAY)
        val week = generateSequence(monday) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        return when {
            g.type == GoalType.COUNT -> {
                val days = week.count { d ->
                    (Timeline.dayTotals(entries, d, now)[g.categoryId] ?: 0L) >= g.minutes * 60_000L
                }.toLong()
                GoalProgress(g, days, g.count.toLong(), days >= g.count, false)
            }
            else -> {
                val v = if (g.period == GoalPeriod.DAY) {
                    Timeline.dayTotals(entries, today, now)[g.categoryId] ?: 0L
                } else {
                    week.sumOf { Timeline.dayTotals(entries, it, now)[g.categoryId] ?: 0L }
                }
                val target = g.minutes * 60_000L
                if (g.type == GoalType.MIN) GoalProgress(g, v, target, v >= target, false)
                else GoalProgress(g, v, target, v <= target, v > target)
            }
        }
    }

    fun progressText(p: GoalProgress): String = when (p.goal.type) {
        GoalType.COUNT -> "${p.value} z ${p.target} dni"
        else -> "${Fmt.durationShort(p.value)} z ${Fmt.durationShort(p.target)}"
    }
}

/** Powiadomienia o celach: przekroczony limit, brakujące minuty wieczorem, osiągnięty cel. */
object GoalChecker {
    suspend fun run(ctx: Context) {
        val app = ctx.applicationContext as LifeApp
        checkSteps(ctx, app)
        val goals = app.db.goals().getAll().filter { it.notify }
        if (goals.isEmpty()) return
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val (from, to) = Goals.weekRange(today)
        val entries = app.db.entries().getRange(from, to)
        val cats = app.db.categories().getAll().associateBy { it.id }
        val hour = LocalDateTime.now().hour
        val weekKey = today.with(DayOfWeek.MONDAY).toEpochDay()

        for (g in goals) {
            val p = Goals.progress(g, entries, today, now)
            val name = cats[g.categoryId]?.name ?: continue
            val periodKey = if (g.period == GoalPeriod.DAY && g.type != GoalType.COUNT) today.toEpochDay() else weekKey
            when (g.type) {
                GoalType.MAX -> if (p.exceeded) {
                    val key = "${g.id}_max_$periodKey"
                    if (!prefs.goalNotified(key)) {
                        prefs.setGoalNotified(key)
                        Notifications.simple(
                            ctx, 3000 + g.id.toInt(), Notifications.CH_GOALS,
                            "Przekroczony limit: $name",
                            "Już ${Fmt.durationShort(p.value)} przy limicie ${Fmt.durationShort(p.target)} " +
                                (if (g.period == GoalPeriod.DAY) "na dziś." else "na ten tydzień."),
                            null,
                        )
                    }
                }
                GoalType.MIN -> {
                    if (p.met) {
                        val key = "${g.id}_met_$periodKey"
                        if (!prefs.goalNotified(key)) {
                            prefs.setGoalNotified(key)
                            Notifications.simple(
                                ctx, 3000 + g.id.toInt(), Notifications.CH_GOALS,
                                "Cel osiągnięty: $name 🎉", Goals.describe(g).replaceFirstChar { it.uppercase() }, null,
                            )
                        }
                    } else if (g.period == GoalPeriod.DAY && hour >= 20) {
                        val key = "${g.id}_left_$periodKey"
                        if (!prefs.goalNotified(key)) {
                            prefs.setGoalNotified(key)
                            Notifications.simple(
                                ctx, 3000 + g.id.toInt(), Notifications.CH_GOALS,
                                "Jeszcze ${Fmt.durationShort(p.target - p.value)}: $name",
                                "Do dzisiejszego celu brakuje trochę czasu.", null,
                            )
                        }
                    }
                }
                GoalType.COUNT -> if (p.met) {
                    val key = "${g.id}_count_$weekKey"
                    if (!prefs.goalNotified(key)) {
                        prefs.setGoalNotified(key)
                        Notifications.simple(
                            ctx, 3000 + g.id.toInt(), Notifications.CH_GOALS,
                            "Cel tygodnia osiągnięty: $name 🎉", "${g.count}× w tym tygodniu.", null,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun checkSteps(ctx: Context, app: LifeApp) {
    val prefs = Prefs(ctx)
    if (!prefs.stepGoalNotify || prefs.stepGoal <= 0) return
    val today = LocalDate.now()
    val key = "steps_${today.toEpochDay()}"
    if (prefs.goalNotified(key)) return
    val steps = Steps.total(app.db.steps().getRange(Fmt.dayStart(today), Fmt.dayStart(today.plusDays(1))))
    if (steps >= prefs.stepGoal) {
        prefs.setGoalNotified(key)
        Notifications.simple(
            ctx, 3999, Notifications.CH_GOALS,
            "Cel kroków osiągnięty 🎉", "${Fmt.steps(steps)} kroków dzisiaj (cel ${Fmt.steps(prefs.stepGoal.toLong())}).", null,
        )
    }
}

// ---------------------------------------------------------------------------
// Podsumowanie tygodnia
// ---------------------------------------------------------------------------

object WeeklySummary {
    private val dayNames = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")

    fun signed(ms: Long): String = (if (ms >= 0) "+" else "−") + Fmt.durationShort(abs(ms))

    /** Tekst podsumowania bieżącego tygodnia (pon–teraz) na tle poprzedniego. */
    suspend fun build(ctx: Context): String? {
        val app = ctx.applicationContext as LifeApp
        val period = Period.current(PeriodType.WEEK)
        val from = Fmt.dayStart(period.prev().start)
        val to = Fmt.dayStart(period.end)
        val entries = app.db.entries().getRange(from, to)
        if (entries.isEmpty()) return null
        val cats = app.db.categories().getAll().associateBy { it.id }
        val s = Stats.compute(entries, period, System.currentTimeMillis())
        if (s.totals.isEmpty()) return null
        val lines = mutableListOf<String>()

        val sleep = cats.values.firstOrNull { it.kind == Kind.SLEEP }
        if (sleep != null && (s.totals[sleep.id] ?: 0L) > 0) {
            val avg = (s.totals[sleep.id] ?: 0L) / s.days
            val prevAvg = (s.prevTotals[sleep.id] ?: 0L) / s.prevDays
            lines += "Sen: średnio ${Fmt.durationShort(avg)}" +
                (if (prevAvg > 0) " (${signed(avg - prevAvg)})" else "")
        }
        s.totals.toList()
            .filter { (id, _) -> cats[id]?.kind !in setOf(Kind.SLEEP, Kind.UNKNOWN) }
            .sortedByDescending { it.second }
            .take(3)
            .forEach { (id, v) ->
                val prev = s.prevTotals[id] ?: 0L
                lines += "${cats[id]?.name}: ${Fmt.durationShort(v)}" + (if (prev > 0) " (${signed(v - prev)})" else "")
            }
        val stepDays = Steps.perDay(app.db.steps().getRange(from, to))
        val curDays = stepDays.filterKeys { it >= period.start && it < period.end }.values
        val prevDays = stepDays.filterKeys { it < period.start }.values
        if (curDays.isNotEmpty()) {
            val avg = curDays.sum() / curDays.size
            val prevAvg = if (prevDays.isEmpty()) 0L else prevDays.sum() / prevDays.size
            val diff = avg - prevAvg
            lines += "Kroki: średnio ${Fmt.steps(avg)} dziennie" +
                (if (prevAvg > 0) " (${if (diff >= 0) "+" else "−"}${Fmt.steps(abs(diff))})" else "")
        }
        val work = cats.values.firstOrNull { it.kind == Kind.WORK }
        if (work != null) {
            val best = s.buckets.withIndex().maxByOrNull { it.value[work.id] ?: 0L }
            val v = best?.value?.get(work.id) ?: 0L
            if (best != null && v > 0) lines += "Najdłuższy dzień pracy: ${dayNames[best.index]} (${Fmt.durationShort(v)})"
        }
        return lines.joinToString("\n")
    }

    suspend fun maybeNotify(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.weeklySummary) return
        val now = LocalDateTime.now()
        if (now.dayOfWeek != DayOfWeek.SUNDAY || now.hour < 19) return
        val weekKey = now.toLocalDate().with(DayOfWeek.MONDAY).toEpochDay()
        if (prefs.lastWeekly == weekKey) return
        val text = build(ctx) ?: return
        prefs.lastWeekly = weekKey
        Notifications.simple(ctx, 4000, Notifications.CH_SUMMARY, "Twój tydzień w Dobie", text, "stats")
    }
}

// ---------------------------------------------------------------------------
// Ciekawostki i dojazdy
// ---------------------------------------------------------------------------

object Insights {
    private val plural = listOf("poniedziałki", "wtorki", "środy", "czwartki", "piątki", "soboty", "niedziele")

    private fun hhmm(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun minuteOfDay(ms: Long): Int {
        val t = Instant.ofEpochMilli(ms).atZone(Fmt.zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    fun compute(entries: List<Entry>, cats: Map<Long, Category>, places: Map<Long, Place>, now: Long): List<String> {
        val out = mutableListOf<String>()
        fun catOf(kind: String) = cats.values.firstOrNull { it.kind == kind }
        val home = catOf(Kind.HOME)
        val work = catOf(Kind.WORK)
        val sleep = catOf(Kind.SLEEP)
        val walk = catOf(Kind.WALK)

        val today = LocalDate.now()
        val days = (1..56).map { today.minusDays(it.toLong()) }
        val totals = days.associateWith { Timeline.dayTotals(entries, it, now) }

        fun byWeekday(catId: Long): Map<Int, Long> =
            totals.entries.filter { (it.value[catId] ?: 0L) > 0 }
                .groupBy { it.key.dayOfWeek.value - 1 }
                .mapValues { (_, list) -> list.sumOf { it.value[catId] ?: 0L } / list.size }

        // Powroty do domu w dni robocze
        if (home != null) {
            val returns = entries
                .filter { it.categoryId == home.id && it.source == Source.AUTO_STAY }
                .filter {
                    val d = Fmt.toLocalDate(it.startAt)
                    d.dayOfWeek.value <= 5 && minuteOfDay(it.startAt) >= 14 * 60
                }
                .groupBy { Fmt.toLocalDate(it.startAt) }
                .map { (_, l) -> minuteOfDay(l.minOf { it.startAt }) }
                .sorted()
            if (returns.size >= 3) out += "W dni robocze wracasz do domu zwykle około ${hhmm(returns[returns.size / 2])}."
        }

        // Pory snu
        if (sleep != null) {
            val nights = entries.filter {
                it.categoryId == sleep.id && it.endAt != null && it.endAt - it.startAt >= 3 * 3_600_000L
            }
            if (nights.size >= 3) {
                val bed = nights.map { val m = minuteOfDay(it.startAt); if (m < 12 * 60) m + 1440 else m }.average().toInt()
                val wake = nights.map { minuteOfDay(it.endAt!!) }.average().toInt()
                out += "Średnio zasypiasz o ${hhmm(bed)}, a wstajesz o ${hhmm(wake)}."
            }
            val bw = byWeekday(sleep.id)
            if (bw.size >= 5) {
                val max = bw.maxBy { it.value }
                val min = bw.minBy { it.value }
                if (max.value - min.value >= 20 * 60_000L) {
                    out += "Najdłużej śpisz w ${plural[max.key]} (${Fmt.durationShort(max.value)}), " +
                        "najkrócej w ${plural[min.key]} (${Fmt.durationShort(min.value)})."
                }
            }
        }

        if (walk != null) {
            val bw = byWeekday(walk.id)
            if (bw.size >= 4) {
                val max = bw.maxBy { it.value }
                out += "Najwięcej chodzisz w ${plural[max.key]} — średnio ${Fmt.durationShort(max.value)}."
            }
        }

        if (work != null) {
            val bw = byWeekday(work.id)
            if (bw.size >= 3) {
                val max = bw.maxBy { it.value }
                out += "Najdłużej pracujesz w ${plural[max.key]} — średnio ${Fmt.durationShort(max.value)}."
            }
        }

        // Ulubione miejsce poza domem i pracą
        val skip = setOfNotNull(home?.id, work?.id)
        val fav = entries
            .filter { it.source == Source.AUTO_STAY && it.placeId != null && it.categoryId !in skip }
            .groupBy { it.placeId!! }
            .maxByOrNull { it.value.size }
        if (fav != null && fav.value.size >= 2) {
            places[fav.key]?.let { out += "Najczęściej odwiedzane miejsce: ${it.name} (${fav.value.size} wizyt w 8 tygodni)." }
        }

        // Rekordowy dzień
        val best = totals.mapNotNull { (d, m) ->
            val active = m.filterKeys { cats[it]?.kind in setOf(Kind.WALK, Kind.RUN, Kind.BIKE, Kind.WORKOUT) }.values.sum()
            if (active > 0) d to active else null
        }.maxByOrNull { it.second }
        if (best != null && best.second >= 30 * 60_000L) {
            out += "Najaktywniejszy dzień: ${Fmt.dayTitle(best.first)} — ${Fmt.durationShort(best.second)} ruchu."
        }
        return out
    }
}

data class CommuteStats(
    val toWorkAvg: Long?, val toWorkN: Int, val toWorkDist: Double?,
    val toHomeAvg: Long?, val toHomeN: Int, val toHomeDist: Double?,
)

object Commute {
    fun compute(entries: List<Entry>, cats: Map<Long, Category>): CommuteStats? {
        val home = cats.values.firstOrNull { it.kind == Kind.HOME }?.id ?: return null
        val work = cats.values.firstOrNull { it.kind == Kind.WORK }?.id ?: return null
        val auto = entries.filter { it.source == Source.AUTO_STAY || it.source == Source.AUTO_MOVE }.sortedBy { it.startAt }
        val toWork = mutableListOf<Pair<Long, Double?>>()
        val toHome = mutableListOf<Pair<Long, Double?>>()
        var lastStay: Entry? = null
        var dist = 0.0
        var hasDist = false
        for (e in auto) {
            if (e.source == Source.AUTO_MOVE) {
                e.distanceM?.let { dist += it; hasDist = true }
                continue
            }
            val prev = lastStay
            val prevEnd = prev?.endAt
            if (prev != null && prevEnd != null) {
                val d = e.startAt - prevEnd
                if (d in 60_000L..3 * 3_600_000L) {
                    val pair = d to (if (hasDist) dist else null)
                    if (prev.categoryId == home && e.categoryId == work) toWork += pair
                    if (prev.categoryId == work && e.categoryId == home) toHome += pair
                }
            }
            lastStay = e
            dist = 0.0
            hasDist = false
        }
        if (toWork.isEmpty() && toHome.isEmpty()) return null
        fun avg(l: List<Pair<Long, Double?>>) = if (l.isEmpty()) null else l.sumOf { it.first } / l.size
        fun avgD(l: List<Pair<Long, Double?>>) = l.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.average()
        return CommuteStats(avg(toWork), toWork.size, avgD(toWork), avg(toHome), toHome.size, avgD(toHome))
    }
}

// ---------------------------------------------------------------------------
// Kopia zapasowa
// ---------------------------------------------------------------------------

object Backup {
    private const val FORMAT = "doba-backup"
    private val dayNames = listOf("pon", "wt", "sr", "czw", "pt", "sob", "nd")

    suspend fun toJson(): String = withContext(Dispatchers.IO) {
        val db = LifeApp.instance.db
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", 1)
        root.put("exported_at", System.currentTimeMillis())
        root.put("categories", JSONArray().apply {
            db.categories().getAll().forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color)
                    .put("icon", c.icon).put("kind", c.kind).put("sortOrder", c.sortOrder))
            }
        })
        root.put("places", JSONArray().apply {
            db.places().getAll().forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lng", p.lng)
                    .put("radius", p.radius).put("categoryId", p.categoryId).put("createdAt", p.createdAt))
            }
        })
        root.put("entries", JSONArray().apply {
            db.entries().all().forEach { e ->
                put(JSONObject().put("id", e.id).put("startAt", e.startAt).put("endAt", e.endAt ?: JSONObject.NULL)
                    .put("categoryId", e.categoryId).put("placeId", e.placeId ?: JSONObject.NULL)
                    .put("lat", e.lat ?: JSONObject.NULL).put("lng", e.lng ?: JSONObject.NULL)
                    .put("source", e.source).put("note", e.note ?: JSONObject.NULL)
                    .put("distanceM", e.distanceM ?: JSONObject.NULL))
            }
        })
        root.put("steps", JSONArray().apply {
            db.steps().all().forEach { b ->
                put(JSONObject().put("h", b.hourStart).put("s", b.source).put("n", b.steps))
            }
        })
        root.put("goals", JSONArray().apply {
            db.goals().getAll().forEach { g ->
                put(JSONObject().put("id", g.id).put("categoryId", g.categoryId).put("type", g.type)
                    .put("period", g.period).put("minutes", g.minutes).put("count", g.count).put("notify", g.notify))
            }
        })
        root.toString()
    }

    private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else getLong(k)
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)
    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)

    /** Zastępuje wszystkie dane zawartością kopii. Zwraca liczbę wpisów. */
    suspend fun restore(json: String): Int = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("To nie jest plik kopii Doby")
        }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("To nie jest plik kopii Doby")
        val db = LifeApp.instance.db
        val cats = root.getJSONArray("categories")
        val places = root.getJSONArray("places")
        val entries = root.getJSONArray("entries")
        val goals = root.optJSONArray("goals") ?: JSONArray()
        val steps = root.optJSONArray("steps") ?: JSONArray()
        db.withTransaction {
            db.entries().deleteAll()
            db.places().deleteAll()
            db.goals().deleteAll()
            db.categories().deleteAll()
            for (i in 0 until cats.length()) {
                val c = cats.getJSONObject(i)
                db.categories().insert(
                    Category(c.getLong("id"), c.getString("name"), c.getInt("color"), c.getString("icon"),
                        c.getString("kind"), c.optInt("sortOrder"))
                )
            }
            for (i in 0 until places.length()) {
                val p = places.getJSONObject(i)
                db.places().insert(
                    Place(p.getLong("id"), p.getString("name"), p.getDouble("lat"), p.getDouble("lng"),
                        p.getDouble("radius"), p.getLong("categoryId"), p.optLong("createdAt"))
                )
            }
            val list = ArrayList<Entry>(entries.length())
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                list += Entry(
                    id = e.getLong("id"), startAt = e.getLong("startAt"), endAt = e.optLongOrNull("endAt"),
                    categoryId = e.getLong("categoryId"), placeId = e.optLongOrNull("placeId"),
                    lat = e.optDoubleOrNull("lat"), lng = e.optDoubleOrNull("lng"),
                    source = e.getString("source"), note = e.optStringOrNull("note"),
                    distanceM = e.optDoubleOrNull("distanceM"),
                )
            }
            db.entries().insertAll(list)
            db.steps().deleteAll()
            db.steps().upsert((0 until steps.length()).map { i ->
                val b = steps.getJSONObject(i)
                StepBucket(b.getLong("h"), b.getString("s"), b.getLong("n"))
            })
            for (i in 0 until goals.length()) {
                val g = goals.getJSONObject(i)
                db.goals().insert(
                    Goal(g.getLong("id"), g.getLong("categoryId"), g.getString("type"), g.getString("period"),
                        g.getInt("minutes"), g.getInt("count"), g.optBoolean("notify", true))
                )
            }
        }
        entries.length()
    }

    suspend fun exportTo(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val data = toJson().toByteArray(Charsets.UTF_8)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
            ?: throw IllegalStateException("Nie można zapisać pliku")
    }

    suspend fun importFrom(ctx: Context, uri: Uri): String = try {
        val text = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: throw IllegalArgumentException("Nie udało się otworzyć pliku")
        val n = restore(text)
        "Przywrócono kopię: $n wpisów"
    } catch (e: IllegalArgumentException) {
        e.message ?: "Niepoprawny plik"
    } catch (e: Exception) {
        "Nie udało się przywrócić kopii"
    }

    /** Zapis do wybranego folderu (7 plików rotowanych po dniach tygodnia). */
    suspend fun autoBackup(ctx: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        val tree = prefs.backupTree?.let { Uri.parse(it) } ?: return@withContext false
        val today = LocalDate.now()
        if (!force && prefs.lastBackupDay == today.toEpochDay()) return@withContext false
        val name = "doba-kopia-${dayNames[today.dayOfWeek.value - 1]}.json"
        val ok = try {
            writeToTree(ctx, tree, name, toJson().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            false
        }
        if (ok) {
            prefs.lastBackupDay = today.toEpochDay()
            prefs.lastBackupAt = System.currentTimeMillis()
        }
        ok
    }

    private fun writeToTree(ctx: Context, treeUri: Uri, name: String, data: ByteArray): Boolean {
        val cr = ctx.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        var target: Uri? = null
        cr.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    target = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    break
                }
            }
        }
        val uri = target ?: DocumentsContract.createDocument(cr, parent, "application/json", name) ?: return false
        cr.openOutputStream(uri, "wt")?.use { it.write(data) } ?: return false
        return true
    }
}

// ---------------------------------------------------------------------------
// Eksport do Kreski (format „wymiana-zdarzen” v1)
// ---------------------------------------------------------------------------

object KreskaExport {
    suspend fun buildFile(ctx: Context): File = withContext(Dispatchers.Default) {
        val db = LifeApp.instance.db
        val cats = db.categories().getAll().associateBy { it.id }
        val places = db.places().getAll().associateBy { it.id }
        val entries = db.entries().all()
        val now = System.currentTimeMillis()
        val events = JSONArray()
        val windows = JSONArray()

        if (entries.isNotEmpty()) {
            var d = Fmt.toLocalDate(entries.minOf { it.startAt })
            val today = LocalDate.now()
            var winStart: Long? = null
            var winEnd = 0L
            while (d <= today) {
                val from = Fmt.dayStart(d)
                val to = Fmt.dayStart(d.plusDays(1))
                val dayEntries = entries.filter { it.startAt < to && (it.endAt ?: now) > from }
                for (s in Timeline.resolve(dayEntries, from, to, now)) {
                    val cat = cats[s.entry.categoryId]
                    events.put(
                        JSONObject()
                            .put("id", "doba-${s.entry.id}-${s.start}")
                            .put("type", cat?.name ?: "Nieznane")
                            .put("start", s.start)
                            .put("end", s.end)
                            .put("category_kind", cat?.kind ?: Kind.UNKNOWN)
                            .put("place", s.entry.placeId?.let { places[it]?.name } ?: JSONObject.NULL)
                    )
                    val ws = winStart
                    if (ws != null && s.start - winEnd <= 60_000L) {
                        winEnd = maxOf(winEnd, s.end)
                    } else {
                        if (ws != null) windows.put(JSONObject().put("start", ws).put("end", winEnd))
                        winStart = s.start
                        winEnd = s.end
                    }
                }
                d = d.plusDays(1)
            }
            winStart?.let { windows.put(JSONObject().put("start", it).put("end", winEnd)) }
        }

        val root = JSONObject()
            .put("format", "wymiana-zdarzen")
            .put("version", 1)
            .put("source", "doba")
            .put("exported_at", now)
            .put("windows", windows)
            .put("events", events)
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "doba-do-kreski.json").apply { writeText(root.toString(), Charsets.UTF_8) }
    }
}
