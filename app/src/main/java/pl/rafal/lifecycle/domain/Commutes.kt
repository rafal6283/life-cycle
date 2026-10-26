package pl.rafal.lifecycle.domain

import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Dojazdy: przejazdy między miejscem „Dom” a „Praca” (w obie strony), także z krótkimi
 * przystankami po drodze. Liczone z istniejących wpisów — działa też wstecz na całej historii.
 */
object Commutes {
    /** Najdłuższy przystanek po drodze, który nie przerywa dojazdu. */
    const val MAX_STOP_MS = 20 * 60_000L
    const val MAX_TRIP_MS = 3 * 3_600_000L

    data class Trip(
        val start: Long,
        val end: Long,
        val toWork: Boolean,
        val distanceM: Double?,
        /** Kategoria przejazdu, który trwał najdłużej (samochód, chodzenie, rower…). */
        val mainMotionCat: Long?,
        val entryIds: List<Long>,
        val stops: Int,
    ) {
        val duration: Long get() = end - start
    }

    data class Info(val originalCategoryId: Long, val toWork: Boolean, val mainMotionCat: Long?)

    private val infoCache = ConcurrentHashMap<Long, Info>()

    /** Oryginalna kategoria i kierunek wpisu oznaczonego jako dojazd (do ikon i opisów). */
    fun info(entryId: Long): Info? = infoCache[entryId]

    fun enabled(): Boolean = try {
        Prefs(LifeApp.instance).commutes
    } catch (e: Exception) {
        true
    }

    fun detect(entries: List<Entry>, cats: Map<Long, Category>): List<Trip> {
        val homeIds = cats.values.filter { it.kind == Kind.HOME }.map { it.id }.toSet()
        val workIds = cats.values.filter { it.kind == Kind.WORK }.map { it.id }.toSet()
        if (homeIds.isEmpty() || workIds.isEmpty()) return emptyList()
        val anchors = homeIds + workIds
        val auto = entries.filter { it.source == Source.AUTO_STAY || it.source == Source.AUTO_MOVE }
            .sortedBy { it.startAt }
        val trips = mutableListOf<Trip>()
        var i = 0
        while (i < auto.size) {
            val a = auto[i]
            val aEnd = a.endAt
            if (a.source != Source.AUTO_STAY || a.categoryId !in anchors || aEnd == null) {
                i++
                continue
            }
            val middle = mutableListOf<Entry>()
            var j = i + 1
            var found: Entry? = null
            while (j < auto.size) {
                val e = auto[j]
                if (e.startAt - aEnd > MAX_TRIP_MS) break
                if (e.source == Source.AUTO_STAY && e.categoryId in anchors) {
                    found = e
                    break
                }
                if (e.source == Source.AUTO_STAY) {
                    val end = e.endAt ?: break
                    if (end - e.startAt > MAX_STOP_MS) break
                }
                middle += e
                j++
            }
            val b = found
            val fromHome = a.categoryId in homeIds
            val toWorkPlace = b != null && b.categoryId in workIds
            val toHomePlace = b != null && b.categoryId in homeIds
            if (b != null && middle.any { it.source == Source.AUTO_MOVE } &&
                ((fromHome && toWorkPlace) || (!fromHome && toHomePlace)) &&
                b.startAt - aEnd in 60_000L..MAX_TRIP_MS
            ) {
                val moves = middle.filter { it.source == Source.AUTO_MOVE }
                val dist = moves.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
                val main = moves.maxByOrNull { (it.endAt ?: it.startAt) - it.startAt }?.categoryId
                trips += Trip(
                    start = aEnd, end = b.startAt, toWork = toWorkPlace, distanceM = dist,
                    mainMotionCat = main, entryIds = middle.map { it.id },
                    stops = middle.count { it.source == Source.AUTO_STAY },
                )
            }
            // Następny dojazd może zaczynać się od miejsca docelowego.
            i = if (b != null) j else i + 1
        }
        return trips
    }

    /** Oznacza przejazdy (i krótkie przystanki) w dojazdach kategorią „Dojazd”. */
    fun apply(entries: List<Entry>, categories: List<Category>): List<Entry> {
        if (!enabled()) return entries
        val commute = categories.firstOrNull { it.kind == Kind.COMMUTE } ?: return entries
        val cats = categories.associateBy { it.id }
        val trips = detect(entries, cats)
        if (trips.isEmpty()) return entries
        val byEntry = HashMap<Long, Trip>()
        trips.forEach { t -> t.entryIds.forEach { byEntry[it] = t } }
        return entries.map { e ->
            val t = byEntry[e.id]
            if (t == null || e.categoryId == commute.id) {
                e
            } else {
                infoCache[e.id] = Info(e.categoryId, t.toWork, t.mainMotionCat)
                e.copy(categoryId = commute.id)
            }
        }
    }

    fun stats(entries: List<Entry>, cats: Map<Long, Category>): CommuteStats? {
        val trips = detect(entries, cats)
        if (trips.isEmpty()) return null
        val toWork = trips.filter { it.toWork }
        val toHome = trips.filter { !it.toWork }
        fun avg(l: List<Trip>) = if (l.isEmpty()) null else l.sumOf { it.duration } / l.size
        fun avgD(l: List<Trip>) = l.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.average()
        return CommuteStats(avg(toWork), toWork.size, avgD(toWork), avg(toHome), toHome.size, avgD(toHome))
    }
}

/**
 * Odwożenie i odbieranie dzieci: krótki postój (do 30 min) w miejscu z kategorią „Szkoła / przedszkole”
 * razem z przejazdem do niego (i powrotem prosto do domu) dostaje tę kategorię. Przed 12:00 — zawiezienie,
 * po 12:00 — odbiór. Dłuższy pobyt w szkole (zebranie, praca) zostaje zwykłym pobytem.
 */
object SchoolRuns {
    const val MAX_STOP_MS = 30 * 60_000L

    data class Info(val dropOff: Boolean, val originalCategoryId: Long)

    private val infoCache = java.util.concurrent.ConcurrentHashMap<Long, Info>()

    fun info(entryId: Long): Info? = infoCache[entryId]

    fun enabled(): Boolean = try {
        Prefs(LifeApp.instance).schoolRuns
    } catch (e: Exception) {
        true
    }

    data class Run(val start: Long, val end: Long, val dropOff: Boolean, val distanceM: Double?, val stopId: Long)

    /** Kursy szkolne w liście wpisów (posortowanej lub nie). */
    fun detect(entries: List<Entry>, cats: Map<Long, Category>): Pair<List<Run>, Map<Long, Run>> {
        val school = cats.values.filter { it.kind == Kind.SCHOOL }.map { it.id }.toSet()
        if (school.isEmpty()) return emptyList<Run>() to emptyMap()
        val home = cats.values.filter { it.kind == Kind.HOME }.map { it.id }.toSet()
        val auto = entries.filter { it.source == Source.AUTO_STAY || it.source == Source.AUTO_MOVE }.sortedBy { it.startAt }
        val runs = mutableListOf<Run>()
        val byEntry = HashMap<Long, Run>()
        for ((i, e) in auto.withIndex()) {
            if (e.source != Source.AUTO_STAY) continue
            val origCat = Commutes.info(e.id)?.originalCategoryId ?: e.categoryId
            if (origCat !in school) continue
            val end = e.endAt ?: continue
            if (end - e.startAt > MAX_STOP_MS) continue
            val members = mutableListOf(e)
            // Dojazd do szkoły: przejazdy tuż przed postojem.
            var j = i - 1
            while (j >= 0 && auto[j].source == Source.AUTO_MOVE) { members += auto[j]; j-- }
            // Powrót: przejazdy po postoju, jeśli kończą się w domu.
            var k = i + 1
            val after = mutableListOf<Entry>()
            while (k < auto.size && auto[k].source == Source.AUTO_MOVE) { after += auto[k]; k++ }
            val next = auto.getOrNull(k)
            if (after.isNotEmpty() && next != null && next.categoryId in home) members += after
            val start = members.minOf { it.startAt }
            val stop = members.maxOf { it.endAt ?: it.startAt }
            val dropOff = java.time.Instant.ofEpochMilli(e.startAt).atZone(pl.rafal.lifecycle.util.Fmt.zone).hour < 12
            val dist = members.filter { it.source == Source.AUTO_MOVE }.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
            val run = Run(start, stop, dropOff, dist, e.id)
            runs += run
            members.forEach { byEntry[it.id] = run }
        }
        return runs to byEntry
    }

    fun apply(entries: List<Entry>, categories: List<Category>): List<Entry> {
        if (!enabled()) return entries
        val cat = categories.firstOrNull { it.kind == Kind.SCHOOL } ?: return entries
        val (_, byEntry) = detect(entries, categories.associateBy { it.id })
        if (byEntry.isEmpty()) return entries
        return entries.map { e ->
            val r = byEntry[e.id] ?: return@map e
            infoCache[e.id] = Info(r.dropOff, Commutes.info(e.id)?.originalCategoryId ?: e.categoryId)
            e.copy(categoryId = cat.id)
        }
    }
}
