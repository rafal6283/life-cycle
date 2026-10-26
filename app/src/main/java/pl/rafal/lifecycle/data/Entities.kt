package pl.rafal.lifecycle.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Rodzaje kategorii, z których korzysta silnik śledzenia. */
object Kind {
    const val SLEEP = "SLEEP"
    const val HOME = "HOME"
    const val WORK = "WORK"
    const val TRAVEL = "TRAVEL"
    const val WALK = "WALK"
    const val RUN = "RUN"
    const val BIKE = "BIKE"
    const val WORKOUT = "WORKOUT"
    const val COMMUTE = "COMMUTE"
    /** Praca w terenie: wizyta u klienta. */
    const val FIELD = "FIELD"
    /** Szkoła / przedszkole — miejsce; krótkie postoje tam to odwożenie i odbieranie dzieci. */
    const val SCHOOL = "SCHOOL"
    /** Krótki postój w trasie w godzinach pracy w terenie (sklep, stacja) — nie wizyta. */
    const val STOP = "STOP"
    const val UNKNOWN = "UNKNOWN"
    const val CUSTOM = "CUSTOM"

    /** Kategorie wymagane przez silnik — nie można ich usunąć. */
    val required = setOf(SLEEP, TRAVEL, WALK, RUN, BIKE, WORKOUT, COMMUTE, UNKNOWN)
}

object Source {
    const val AUTO_STAY = "AUTO_STAY"
    const val AUTO_MOVE = "AUTO_MOVE"
    const val SLEEP_HC = "SLEEP_HC"
    const val SLEEP_AUTO = "SLEEP_AUTO"
    const val WORKOUT_HC = "WORKOUT_HC"
    const val MANUAL = "MANUAL"
    /** „Teraz robię…” — trwa, dopóki użytkownik go nie zakończy albo nie zmieni miejsca. */
    const val OVERRIDE = "OVERRIDE"

    fun label(source: String): String? = when (source) {
        SLEEP_HC -> "Health Connect"
        SLEEP_AUTO -> "sen wykryty automatycznie"
        WORKOUT_HC -> "trening z Health Connect"
        MANUAL -> "ręcznie"
        OVERRIDE -> "przełączone ręcznie"
        else -> null
    }
}

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val icon: String,
    val kind: String = Kind.CUSTOM,
    val sortOrder: Int = 0,
)

@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double = 100.0,
    val categoryId: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "entries",
    indices = [Index("startAt"), Index("endAt"), Index("placeId")],
)
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long?,
    val categoryId: Long,
    val placeId: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val source: String,
    val note: String? = null,
    /** Dystans w metrach (przejazdy i treningi). */
    val distanceM: Double? = null,
)

@Entity(tableName = "locations", indices = [Index("time")])
data class LocationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
)

@Entity(tableName = "screen_off", indices = [Index("startAt")])
data class ScreenOff(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
)

object DefaultCategories {
    private fun c(name: String, color: Long, icon: String, kind: String, order: Int) =
        Category(name = name, color = color.toInt(), icon = icon, kind = kind, sortOrder = order)

    val list = listOf(
        c("Sen", 0xFF5E5CE6, "bed", Kind.SLEEP, 0),
        c("Dom", 0xFF30B0C7, "home", Kind.HOME, 1),
        c("Praca", 0xFFFF9F0A, "work", Kind.WORK, 2),
        c("Podróż", 0xFFAC8E68, "car", Kind.TRAVEL, 3),
        c("Chodzenie", 0xFF34C759, "walk", Kind.WALK, 4),
        c("Bieganie", 0xFFFF3B30, "run", Kind.RUN, 5),
        c("Rower", 0xFFFFCC00, "bike", Kind.BIKE, 6),
        c("Siłownia", 0xFFFF2D55, "gym", Kind.WORKOUT, 7),
        c("Jedzenie", 0xFFFF6B22, "food", Kind.CUSTOM, 8),
        c("Zakupy", 0xFFBF5AF2, "shop", Kind.CUSTOM, 9),
        c("Znajomi", 0xFFFF8AC4, "friends", Kind.CUSTOM, 10),
        c("Rozrywka", 0xFF8E44AD, "fun", Kind.CUSTOM, 11),
        c("Nauka", 0xFF007AFF, "study", Kind.CUSTOM, 12),
        c("Dojazd", 0xFF7A8794, "commute", Kind.COMMUTE, 13),
        c("Serwis u klienta", 0xFF0A84FF, "handyman", Kind.FIELD, 14),
        Category(name = "Szkoła / przedszkole", color = 0xFFFFB300.toInt(), icon = "emoji:🎒", kind = Kind.SCHOOL, sortOrder = 15),
        c("Postój w trasie", 0xFF8E9AAF, "local_parking", Kind.STOP, 16),
        c("Nieznane", 0xFFA0A0A8, "unknown", Kind.UNKNOWN, 99),
    )
}

object GoalType {
    const val MIN = "MIN"
    const val MAX = "MAX"
    /** Liczba dni w tygodniu z co najmniej [Goal.minutes] minut danej kategorii. */
    const val COUNT = "COUNT"
}

object GoalPeriod {
    const val DAY = "DAY"
    const val WEEK = "WEEK"
}

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val type: String,
    val period: String,
    val minutes: Int,
    val count: Int,
    val notify: Boolean,
)

object StepSource {
    const val PHONE = "PHONE"
    const val HC = "HC"
}

/** Kroki w jednej godzinie z jednego źródła; liczy się wyższa wartość z godziny. */
@Entity(tableName = "steps", primaryKeys = ["hourStart", "source"])
data class StepBucket(
    val hourStart: Long,
    val source: String,
    val steps: Long,
)

/**
 * Pomiar zdrowotny z Health Connect. Dla tętna (agregat godzinowy) value = średnia, v2 = min, v3 = max;
 * dla ciśnienia value = skurczowe, v2 = rozkurczowe; dla sum dziennych time = początek dnia.
 */
@Entity(tableName = "health", primaryKeys = ["metric", "time"])
data class HealthSample(
    val metric: String,
    val time: Long,
    val value: Double,
    val v2: Double? = null,
    val v3: Double? = null,
)

/** Zdarzenie z innej aplikacji (plik „wymiana-zdarzen”): wiatry, kliknięcia Kreski, epizody AF. */
@Entity(tableName = "ext_events", indices = [Index("source"), Index("start")])
data class ExtEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    /** Np. nazwa licznika w Kresce. */
    val label: String,
    val type: String,
    val start: Long,
    val end: Long? = null,
    val value: Double = 1.0,
)

/** Czas przed ekranem z jednego dnia (statystyki użycia Androida — te same co w Cyfrowej równowadze). */
@Entity(tableName = "screen_days")
data class ScreenDay(
    @PrimaryKey val day: Long,
    val screenMs: Long,
    val unlocks: Int,
    val screenOns: Int,
    val notifs: Int,
    val firstUse: Long?,
    /** „pakiet=ms;pakiet=ms” — najczęściej używane aplikacje. */
    val apps: String,
    /** 24 wartości ms oddzielone przecinkami. */
    val hours: String,
    val updatedAt: Long,
    /** Dokładne odcinki używania: „początek-koniec;…” w sekundach od północy. */
    val sessions: String = "",
) {
    /** Odcinki używania jako (start, koniec) w ms od początku dnia. */
    fun sessionList(): List<Pair<Long, Long>> =
        if (sessions.isEmpty()) emptyList() else sessions.split(";").mapNotNull { p ->
            val i = p.indexOf('-')
            if (i <= 0) null else {
                val a = p.substring(0, i).toLongOrNull() ?: return@mapNotNull null
                val b = p.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
                a * 1000 to b * 1000
            }
        }

    fun appList(): List<Pair<String, Long>> =
        apps.split(";").mapNotNull { p ->
            val i = p.lastIndexOf('=')
            if (i <= 0) null else p.substring(0, i) to (p.substring(i + 1).toLongOrNull() ?: 0L)
        }

    fun hourList(): List<Long> = hours.split(",").map { it.toLongOrNull() ?: 0L }.let { if (it.size == 24) it else List(24) { 0L } }
}
