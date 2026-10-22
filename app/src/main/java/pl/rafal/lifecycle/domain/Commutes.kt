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
