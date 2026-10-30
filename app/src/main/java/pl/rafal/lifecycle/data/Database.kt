package pl.rafal.lifecycle.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface StepDao {
    @Query("SELECT * FROM steps WHERE hourStart >= :from AND hourStart < :to")
    fun observeRange(from: Long, to: Long): Flow<List<StepBucket>>

    @Query("SELECT * FROM steps WHERE hourStart >= :from AND hourStart < :to")
    suspend fun getRange(from: Long, to: Long): List<StepBucket>

    @Query("INSERT OR IGNORE INTO steps (hourStart, source, steps) VALUES (:hour, :source, 0)")
    suspend fun ensure(hour: Long, source: String)

    @Query("UPDATE steps SET steps = steps + :delta WHERE hourStart = :hour AND source = :source")
    suspend fun add(hour: Long, source: String, delta: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<StepBucket>)

    @Query("DELETE FROM steps WHERE source = :source AND hourStart >= :from AND hourStart < :to")
    suspend fun deleteSourceIn(source: String, from: Long, to: Long)

    @Query("SELECT * FROM steps ORDER BY hourStart")
    suspend fun all(): List<StepBucket>

    @Query("DELETE FROM steps")
    suspend fun deleteAll()
}

@Dao
interface HealthDao {
    @Query("SELECT * FROM health WHERE metric = :metric AND time >= :from AND time < :to ORDER BY time")
    fun observe(metric: String, from: Long, to: Long): Flow<List<HealthSample>>

    @Query("SELECT * FROM health WHERE time >= :from AND time < :to ORDER BY time")
    fun observeAll(from: Long, to: Long): Flow<List<HealthSample>>

    @Query("SELECT * FROM health WHERE time >= :from AND time < :to ORDER BY time")
    suspend fun getRange(from: Long, to: Long): List<HealthSample>

    @Query("SELECT * FROM health WHERE metric = :metric AND time >= :from AND time < :to ORDER BY time")
    suspend fun range(metric: String, from: Long, to: Long): List<HealthSample>

    @Query("SELECT * FROM health WHERE metric = :metric ORDER BY time DESC LIMIT 1")
    suspend fun latest(metric: String): HealthSample?

    @Query("DELETE FROM health WHERE metric = :metric AND time >= :from AND time < :to")
    suspend fun deleteRange(metric: String, from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<HealthSample>)
}

@Dao
interface ExtDao {
    @Query("SELECT * FROM ext_events WHERE start >= :from AND start < :to ORDER BY start")
    fun observe(from: Long, to: Long): Flow<List<ExtEvent>>

    @Query("SELECT * FROM ext_events ORDER BY start")
    suspend fun all(): List<ExtEvent>

    @Query("SELECT source, label, COUNT(*) AS n, MAX(start) AS last FROM ext_events GROUP BY source, label")
    fun observeSources(): Flow<List<ExtSourceInfo>>

    @Query("DELETE FROM ext_events WHERE source = :source AND label = :label")
    suspend fun deleteSource(source: String, label: String)

    @Query("DELETE FROM ext_events")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(list: List<ExtEvent>)
}

data class ExtSourceInfo(val source: String, val label: String, val n: Int, val last: Long)

@Dao
interface ScreenDao {
    @Query("SELECT * FROM screen_days WHERE day >= :from AND day < :to ORDER BY day")
    fun observe(from: Long, to: Long): Flow<List<ScreenDay>>

    @Query("SELECT * FROM screen_days WHERE day >= :from AND day < :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<ScreenDay>

    @Query("SELECT * FROM screen_days WHERE day = :day")
    suspend fun get(day: Long): ScreenDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: ScreenDay)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY id")
    fun observeAll(): Flow<List<Goal>>

    @Query("SELECT * FROM goals ORDER BY id")
    suspend fun getAll(): List<Goal>

    @Query("DELETE FROM goals WHERE categoryId = :cat")
    suspend fun deleteForCategory(cat: Long)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(g: Goal): Long

    @Update
    suspend fun update(g: Goal)

    @Delete
    suspend fun delete(g: Goal)
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

    @Query("DELETE FROM places")
    suspend fun deleteAll()
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

    @Query(
        "SELECT * FROM entries WHERE placeId IS NULL AND source = 'AUTO_STAY' AND lat IS NOT NULL " +
            "AND categoryId IN (SELECT id FROM categories WHERE kind = 'UNKNOWN')"
    )
    fun observeUnknownStays(): Flow<List<Entry>>

    @Query(
        "SELECT * FROM entries WHERE placeId IS NULL AND source = 'AUTO_STAY' AND lat IS NOT NULL " +
            "AND startAt >= :from AND categoryId IN (SELECT id FROM categories WHERE kind = 'UNKNOWN')"
    )
    suspend fun unknownStaysSince(from: Long): List<Entry>

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

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): Entry?

    @Query("SELECT * FROM entries WHERE source = 'AUTO_STAY' AND placeId IS NULL AND categoryId = :fieldCat AND startAt >= :since ORDER BY startAt")
    suspend fun fieldStaysSince(fieldCat: Long, since: Long): List<Entry>

    @Query("SELECT * FROM entries WHERE endAt IS NULL AND source = 'OVERRIDE' ORDER BY startAt DESC LIMIT 1")
    fun observeOverride(): Flow<Entry?>

    @Query("UPDATE entries SET endAt = :at WHERE endAt IS NULL AND source = 'OVERRIDE'")
    suspend fun closeOverrides(at: Long)

    @Query("SELECT * FROM entries WHERE endAt IS NULL ORDER BY startAt DESC")
    suspend fun allOpen(): List<Entry>

    @Query("DELETE FROM entries")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(list: List<Entry>)

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

    @Query("SELECT * FROM locations WHERE time >= :from AND time <= :to ORDER BY time")
    suspend fun range(from: Long, to: Long): List<LocationSample>
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

@Dao
interface MoodDao {
    @Query("SELECT * FROM mood_days WHERE day >= :from AND day < :to ORDER BY day")
    fun observe(from: Long, to: Long): Flow<List<MoodDay>>

    @Query("SELECT * FROM mood_days WHERE day >= :from AND day < :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<MoodDay>

    @Query("SELECT * FROM mood_days WHERE day = :day")
    suspend fun get(day: Long): MoodDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: MoodDay)

    @Query("DELETE FROM mood_days WHERE day = :day")
    suspend fun delete(day: Long)

    @Query("SELECT * FROM mood_days ORDER BY day")
    suspend fun all(): List<MoodDay>
}

@Dao
interface WeatherDao {
    @Query("SELECT * FROM weather_days WHERE day >= :from AND day < :to ORDER BY day")
    fun observe(from: Long, to: Long): Flow<List<WeatherDay>>

    @Query("SELECT * FROM weather_days WHERE day >= :from AND day < :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<WeatherDay>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<WeatherDay>)
}

@Database(
    entities = [Category::class, Place::class, Entry::class, LocationSample::class, ScreenOff::class, Goal::class, StepBucket::class,
        HealthSample::class, ExtEvent::class, ScreenDay::class, MoodDay::class, WeatherDay::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun places(): PlaceDao
    abstract fun entries(): EntryDao
    abstract fun locations(): LocationDao
    abstract fun screenOff(): ScreenOffDao
    abstract fun goals(): GoalDao
    abstract fun steps(): StepDao
    abstract fun health(): HealthDao
    abstract fun ext(): ExtDao
    abstract fun screen(): ScreenDao
    abstract fun mood(): MoodDao
    abstract fun weather(): WeatherDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN distanceM REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, `type` TEXT NOT NULL, `period` TEXT NOT NULL, " +
                        "`minutes` INTEGER NOT NULL, `count` INTEGER NOT NULL, `notify` INTEGER NOT NULL)"
                )
                db.execSQL("UPDATE categories SET kind = 'WORKOUT' WHERE kind = 'CUSTOM' AND icon = 'gym'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `steps` (`hourStart` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "`steps` INTEGER NOT NULL, PRIMARY KEY(`hourStart`, `source`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `health` (`metric` TEXT NOT NULL, `time` INTEGER NOT NULL, " +
                        "`value` REAL NOT NULL, `v2` REAL, `v3` REAL, PRIMARY KEY(`metric`, `time`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ext_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`source` TEXT NOT NULL, `label` TEXT NOT NULL, `type` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                        "`end` INTEGER, `value` REAL NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ext_events_source` ON `ext_events` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ext_events_start` ON `ext_events` (`start`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `screen_days` (`day` INTEGER NOT NULL, `screenMs` INTEGER NOT NULL, " +
                        "`unlocks` INTEGER NOT NULL, `screenOns` INTEGER NOT NULL, `notifs` INTEGER NOT NULL, " +
                        "`firstUse` INTEGER, `apps` TEXT NOT NULL, `hours` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`))"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screen_days ADD COLUMN sessions TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mood_days` (`day` INTEGER NOT NULL, `value` INTEGER NOT NULL, " +
                        "`note` TEXT, `at` INTEGER NOT NULL, PRIMARY KEY(`day`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weather_days` (`day` INTEGER NOT NULL, `tMax` REAL NOT NULL, " +
                        "`tMin` REAL NOT NULL, `precip` REAL NOT NULL, `code` INTEGER NOT NULL, `sunH` REAL NOT NULL, " +
                        "`lat` REAL NOT NULL, `lng` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`day`))"
                )
            }
        }

        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "lifecycle.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
