package pl.rafal.lifecycle.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun getAll(): List<Category>

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY id LIMIT 1")
    suspend fun byKind(kind: String): Category?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT MAX(sortOrder) FROM categories WHERE kind != 'UNKNOWN'")
    suspend fun maxOrder(): Int?

    @Insert
    suspend fun insert(c: Category): Long

    @Update
    suspend fun update(c: Category)

    @Delete
    suspend fun delete(c: Category)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places")
    suspend fun getAll(): List<Place>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: Long): Place?

    @Query("UPDATE places SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveCategory(from: Long, to: Long)

    @Insert
    suspend fun insert(p: Place): Long

    @Update
    suspend fun update(p: Place)

    @Delete
    suspend fun delete(p: Place)
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE startAt < :to AND (endAt IS NULL OR endAt > :from) ORDER BY startAt")
    fun observeRange(from: Long, to: Long): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE startAt < :to AND (endAt IS NULL OR endAt > :from) ORDER BY startAt")
    suspend fun getRange(from: Long, to: Long): List<Entry>

    @Query("SELECT * FROM entries WHERE endAt IS NULL AND source IN ('AUTO_STAY','AUTO_MOVE') ORDER BY startAt DESC LIMIT 1")
    suspend fun openAuto(): Entry?

    @Query("SELECT * FROM entries WHERE endAt IS NULL AND source IN ('AUTO_STAY','AUTO_MOVE') ORDER BY startAt DESC LIMIT 1")
    fun observeOpenAuto(): Flow<Entry?>

    @Query("SELECT * FROM entries WHERE source IN ('AUTO_STAY','AUTO_MOVE') ORDER BY startAt DESC LIMIT :n")
    suspend fun lastAuto(n: Int): List<Entry>

    @Query("SELECT * FROM entries WHERE placeId IS NULL AND source = 'AUTO_STAY' AND lat IS NOT NULL")
    fun observeUnknownStays(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE placeId IS NULL AND source = 'AUTO_STAY' AND lat IS NOT NULL")
    suspend fun getUnknownStays(): List<Entry>

    @Query("UPDATE entries SET categoryId = :cat WHERE placeId = :placeId AND source = 'AUTO_STAY'")
    suspend fun setCategoryForPlace(placeId: Long, cat: Long)

    @Query("UPDATE entries SET categoryId = CASE WHEN source = 'AUTO_STAY' THEN :unknownCat ELSE categoryId END, placeId = NULL WHERE placeId = :placeId")
    suspend fun detachPlace(placeId: Long, unknownCat: Long)

    @Query("UPDATE entries SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveCategory(from: Long, to: Long)

    @Query("DELETE FROM entries WHERE source = :source AND startAt >= :from AND startAt < :to")
    suspend fun deleteSourceIn(source: String, from: Long, to: Long)

    @Query("SELECT COUNT(*) FROM entries WHERE categoryId = :cat AND source = :source AND startAt < :to AND (endAt IS NULL OR endAt > :from)")
    suspend fun countCatSourceIn(cat: Long, source: String, from: Long, to: Long): Int

    @Query("SELECT * FROM entries ORDER BY startAt")
    suspend fun all(): List<Entry>

    @Insert
    suspend fun insert(e: Entry): Long

    @Update
    suspend fun update(e: Entry)

    @Delete
    suspend fun delete(e: Entry)
}

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(l: LocationSample)

    @Query("SELECT * FROM locations ORDER BY time DESC LIMIT 1")
    suspend fun last(): LocationSample?

    @Query("DELETE FROM locations WHERE time < :before")
    suspend fun prune(before: Long)
}

@Dao
interface ScreenOffDao {
    @Insert
    suspend fun insert(s: ScreenOff)

    @Query("SELECT * FROM screen_off WHERE startAt < :to AND endAt > :from ORDER BY startAt")
    suspend fun range(from: Long, to: Long): List<ScreenOff>

    @Query("DELETE FROM screen_off WHERE endAt < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [Category::class, Place::class, Entry::class, LocationSample::class, ScreenOff::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun places(): PlaceDao
    abstract fun entries(): EntryDao
    abstract fun locations(): LocationDao
    abstract fun screenOff(): ScreenOffDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "lifecycle.db").build()
    }
}
