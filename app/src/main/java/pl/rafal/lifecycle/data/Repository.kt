package pl.rafal.lifecycle.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import pl.rafal.lifecycle.domain.Commutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.rafal.lifecycle.util.Geo

class Repository(val db: AppDatabase) {
    val categories: Flow<List<Category>> = db.categories().observeAll()
    val places: Flow<List<Place>> = db.places().observeAll()
    val unknownStays: Flow<List<Entry>> = db.entries().observeUnknownStays()
    val goals: Flow<List<Goal>> = db.goals().observeAll()
    val override: Flow<Entry?> = db.entries().observeOverride()

    /** Wpisy z zakresu z rozpoznanymi dojazdami (zakres poszerzony, by złapać dom/pracę na brzegach). */
    fun entriesIn(from: Long, to: Long): Flow<List<Entry>> =
        combine(db.entries().observeRange(from - MARGIN, to + MARGIN), categories) { e, c ->
            Commutes.apply(e, c).filter { it.startAt < to && (it.endAt ?: Long.MAX_VALUE) > from }
        }

    suspend fun getRange(from: Long, to: Long): List<Entry> =
        Commutes.apply(db.entries().getRange(from - MARGIN, to + MARGIN), db.categories().getAll())
            .filter { it.startAt < to && (it.endAt ?: Long.MAX_VALUE) > from }

    suspend fun allWithCommutes(): List<Entry> = Commutes.apply(db.entries().all(), db.categories().getAll())

    private val defaultsLock = Mutex()

    companion object {
        private const val MARGIN = 4 * 3_600_000L
    }

    suspend fun ensureDefaults() = defaultsLock.withLock {
        if (db.categories().count() == 0) {
            DefaultCategories.list.forEach { db.categories().insert(it) }
        }
    }

    suspend fun categoryByKind(kind: String): Category {
        db.categories().byKind(kind)?.let { return it }
        ensureDefaults()
        db.categories().byKind(kind)?.let { return it }
        // Kategoria systemowa została usunięta — odtwórz ją.
        val def = DefaultCategories.list.first { it.kind == kind }
        val id = db.categories().insert(def)
        return def.copy(id = id)
    }

    /** Zapisuje miejsce i przypisuje do niego wcześniejsze nieznane pobyty w jego promieniu. */
    suspend fun savePlace(p: Place): Long {
        val id = if (p.id == 0L) {
            db.places().insert(p)
        } else {
            val old = db.places().get(p.id)
            db.places().update(p)
            if (old != null && old.categoryId != p.categoryId) {
                db.entries().setCategoryForPlace(p.id, p.categoryId)
            }
            p.id
        }
        for (e in db.entries().getUnknownStays()) {
            val lat = e.lat ?: continue
            val lng = e.lng ?: continue
            if (Geo.distance(lat, lng, p.lat, p.lng) <= p.radius + 30) {
                db.entries().update(e.copy(placeId = id, categoryId = p.categoryId))
            }
        }
        return id
    }

    suspend fun deletePlace(p: Place) {
        val unknown = categoryByKind(Kind.UNKNOWN)
        db.entries().detachPlace(p.id, unknown.id)
        db.places().delete(p)
    }

    suspend fun saveEntry(e: Entry): Long =
        if (e.id == 0L) db.entries().insert(e) else { db.entries().update(e); e.id }

    suspend fun deleteEntry(e: Entry) = db.entries().delete(e)

    suspend fun saveCategory(c: Category): Long =
        if (c.id == 0L) {
            val order = (db.categories().maxOrder() ?: 0) + 1
            db.categories().insert(c.copy(sortOrder = order))
        } else {
            db.categories().update(c); c.id
        }

    suspend fun deleteCategory(c: Category) {
        if (c.kind in Kind.required) return
        val unknown = categoryByKind(Kind.UNKNOWN)
        db.entries().moveCategory(c.id, unknown.id)
        db.places().moveCategory(c.id, unknown.id)
        db.goals().deleteForCategory(c.id)
        db.categories().delete(c)
    }

    suspend fun saveGoal(g: Goal): Long =
        if (g.id == 0L) db.goals().insert(g) else { db.goals().update(g); g.id }

    suspend fun deleteGoal(g: Goal) = db.goals().delete(g)

    /** „Teraz robię…”: kończy poprzednie nadpisanie i zaczyna nowe. */
    suspend fun startOverride(categoryId: Long, note: String? = null) {
        val now = System.currentTimeMillis()
        db.entries().closeOverrides(now)
        db.entries().insert(
            Entry(startAt = now, endAt = null, categoryId = categoryId, source = Source.OVERRIDE, note = note)
        )
    }

    suspend fun stopOverride() = db.entries().closeOverrides(System.currentTimeMillis())
}
