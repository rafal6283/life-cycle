package pl.rafal.lifecycle.domain

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.ExtEvent
import pl.rafal.lifecycle.data.HealthSample
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate
import java.util.Locale

/** Jak łączyć kilka pomiarów z jednego dnia. */
enum class DayAgg { AVG, LAST, SUM, MINMAX }

enum class Metric(
    val key: String,
    val label: String,
    val unit: String,
    val icon: String,
    val color: Long,
    val decimals: Int,
    val agg: DayAgg,
) {
    HR("hr", "Tętno", "bpm", "heart", 0xFFFF3B30, 0, DayAgg.MINMAX),
    RESTING_HR("rhr", "Tętno spoczynkowe", "bpm", "heart", 0xFFE8573F, 0, DayAgg.AVG),
    HRV("hrv", "HRV (RMSSD)", "ms", "heart", 0xFFBF5AF2, 0, DayAgg.AVG),
    BP("bp", "Ciśnienie", "mmHg", "health", 0xFFFF2D55, 0, DayAgg.AVG),
    WEIGHT("weight", "Waga", "kg", "weight", 0xFF30B0C7, 1, DayAgg.LAST),
    BODY_FAT("fat", "Tkanka tłuszczowa", "%", "weight", 0xFFFF9F0A, 1, DayAgg.LAST),
    SPO2("spo2", "Saturacja (SpO₂)", "%", "lungs", 0xFF007AFF, 0, DayAgg.AVG),
    RESP("resp", "Oddechy", "/min", "lungs", 0xFF64D2FF, 1, DayAgg.AVG),
    TEMP("temp", "Temperatura ciała", "°C", "temp", 0xFFFF6B22, 1, DayAgg.AVG),
    GLUCOSE("glucose", "Glukoza", "mg/dl", "drop", 0xFFAF52DE, 0, DayAgg.AVG),
    VO2("vo2", "VO₂max", "ml/kg/min", "run", 0xFF34C759, 1, DayAgg.LAST),
    ACTIVE_KCAL("akcal", "Kalorie aktywne", "kcal", "fire", 0xFFFF9500, 0, DayAgg.SUM),
    TOTAL_KCAL("tkcal", "Kalorie łącznie", "kcal", "fire", 0xFFFFCC00, 0, DayAgg.SUM),
    FLOORS("floors", "Piętra", "", "stairs", 0xFF8E8E93, 0, DayAgg.SUM),
    WATER("water", "Woda", "l", "drop", 0xFF5AC8FA, 1, DayAgg.SUM);

    fun format(v: Double): String = String.format(Fmt.PL, "%.${decimals}f", v)

    fun formatSample(s: HealthSample): String = when (this) {
        BP -> "${format(s.value)}/${format(s.v2 ?: 0.0)}"
        else -> format(s.value)
    } + if (unit.isNotEmpty()) " $unit" else ""

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Wartość z jednego dnia: główna (średnia/ostatnia/suma) + opcjonalnie druga (rozkurczowe) i zakres min–max. */
data class DayValue(val day: LocalDate, val value: Double, val v2: Double?, val min: Double?, val max: Double?)

object HealthAgg {
    fun daily(m: Metric, samples: List<HealthSample>): List<DayValue> =
        samples.groupBy { Fmt.toLocalDate(it.time) }.toSortedMap().map { (d, l) ->
            when (m.agg) {
                DayAgg.SUM -> DayValue(d, l.sumOf { it.value }, null, null, null)
                DayAgg.LAST -> l.maxBy { it.time }.let { DayValue(d, it.value, it.v2, null, null) }
                DayAgg.MINMAX -> DayValue(
                    d, l.map { it.value }.average(), null,
                    l.mapNotNull { it.v2 }.minOrNull() ?: l.minOf { it.value },
                    l.mapNotNull { it.v3 }.maxOrNull() ?: l.maxOf { it.value },
                )
                DayAgg.AVG -> DayValue(
                    d, l.map { it.value }.average(),
                    l.mapNotNull { it.v2 }.takeIf { it.isNotEmpty() }?.average(),
                    l.minOf { it.value }, l.maxOf { it.value },
                )
            }
        }
}

// ---------------------------------------------------------------------------
// Import plików „wymiana-zdarzen” (Nocny licznik, Kreska, Polar EKG)
// ---------------------------------------------------------------------------

object ExchangeImport {
    const val SRC_NIGHT = "nocny-licznik"
    const val SRC_KRESKA = "kreska"
    const val SRC_ECG = "polar-ekg"

    fun sourceLabel(source: String, label: String): String = when (source) {
        SRC_NIGHT -> "Nocny licznik"
        SRC_KRESKA -> "Kreska" + if (label.isNotBlank()) ": $label" else ""
        SRC_ECG -> "Polar EKG"
        else -> source
    }

    /** Zastępuje dane z tego samego źródła (i licznika). Zwraca komunikat dla użytkownika. */
    suspend fun importUri(ctx: Context, uri: Uri): String = try {
        val text = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: throw IllegalArgumentException("Nie udało się otworzyć pliku")
        importText(text)
    } catch (e: IllegalArgumentException) {
        e.message ?: "Niepoprawny plik"
    } catch (e: Exception) {
        "Nie udało się zaimportować pliku"
    }

    suspend fun importText(text: String): String {
        val j = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("To nie jest plik eksportu (niepoprawny JSON)")
        }
        if (j.optString("format") == "doba-backup") {
            throw IllegalArgumentException("To kopia zapasowa Doby — przywróć ją w Ustawieniach → Kopia zapasowa")
        }
        if (j.optString("format") != "wymiana-zdarzen") {
            throw IllegalArgumentException("To nie jest plik z Nocnego licznika, Kreski ani Polar EKG")
        }
        val source = j.optString("source")
        if (source !in setOf(SRC_NIGHT, SRC_KRESKA, SRC_ECG)) {
            throw IllegalArgumentException(if (source == "doba") "To eksport z Doby" else "Nieznane źródło: $source")
        }
        val label = if (source == SRC_KRESKA) j.optString("counter", "") else ""
        val out = ArrayList<ExtEvent>()
        val windows = j.optJSONArray("windows")
        if (windows != null) for (i in 0 until windows.length()) {
            val w = windows.getJSONObject(i)
            out += ExtEvent(source = source, label = label, type = "window", start = w.getLong("start"), end = w.getLong("end"))
        }
        val events = j.optJSONArray("events")
        if (events != null) for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.has("verified") && !e.isNull("verified") && !e.getBoolean("verified")) continue
            out += ExtEvent(
                source = source, label = label, type = e.getString("type"), start = e.getLong("start"),
                end = if (e.has("end") && !e.isNull("end")) e.getLong("end") else null,
            )
        }
        // Kreska: sumy dni zawierają też ręczne korekty bez godziny.
        val totals = j.optJSONArray("day_totals")
        if (totals != null) for (i in 0 until totals.length()) {
            val t = totals.getJSONObject(i)
            val d = LocalDate.parse(t.getString("date"))
            out += ExtEvent(
                source = source, label = label, type = "day_total", start = Fmt.dayStart(d),
                value = t.getDouble("total"),
            )
        }
        val db = LifeApp.instance.db
        db.ext().deleteSource(source, label)
        db.ext().insertAll(out)
        val n = out.count { it.type != "window" && it.type != "day_total" }
        return "Zaimportowano z ${sourceLabel(source, label)}: " + when (source) {
            SRC_NIGHT -> "$n wiatrów, ${out.count { it.type == "window" }} nocy"
            SRC_ECG -> "$n epizodów AF"
            else -> "$n kliknięć, ${out.count { it.type == "day_total" }} dni"
        }
    }

    /** Liczba zdarzeń dziennie dla danego źródła/licznika. Dla Kreski — sumy dni (z korektami). */
    fun dailyCounts(events: List<ExtEvent>, source: String, label: String): Map<LocalDate, Int> {
        val l = events.filter { it.source == source && it.label == label }
        return if (source == SRC_KRESKA) {
            l.filter { it.type == "day_total" }.associate { Fmt.toLocalDate(it.start) to it.value.toInt() }
        } else if (source == SRC_NIGHT) {
            // Noc przypisana do dnia pobudki (zdarzenie − 12 h → data wieczoru, +1 dzień).
            l.filter { it.type == "fart" }.groupingBy { Fmt.toLocalDate(it.start - 12 * 3_600_000L).plusDays(1) }
                .eachCount()
        } else {
            l.filter { it.type != "window" && it.type != "day_total" }.groupingBy { Fmt.toLocalDate(it.start) }.eachCount()
        }
    }
}

fun Double.fmt1(): String = String.format(Locale.forLanguageTag("pl-PL"), "%.1f", this)
