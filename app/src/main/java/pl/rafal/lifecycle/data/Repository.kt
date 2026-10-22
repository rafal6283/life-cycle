package pl.rafal.lifecycle.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.rafal.lifecycle.util.Geo

class Repository(val db: AppDatabase) {
    val categories: Flow<List<Category>> = db.categories().observeAll()
    val places: Flow<List<Place>> = db.places().observeAll()
    val unknownStays: Flow<List<Entry>> = db.entries().observeUnknownStays()

    fun entriesIn(from: Long, to: Long): Flow<List<Entry>> = db.entries().observeRange(from, to)

    private val defaultsLock = Mutex()

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
        db.categories().delete(c)
    }
}
