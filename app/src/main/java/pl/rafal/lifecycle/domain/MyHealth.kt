package pl.rafal.lifecycle.domain

import pl.rafal.lifecycle.data.ExtEvent
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

/**
 * „Moje zdrowie”: jeden dzień ze wszystkich moich aplikacji — sen i oddech (Rytm Snu, CPAP), rytm serca
 * (Polar EKG i opaska), poranne HRV, leki (Teczka), jedzenie (Dziennik talerza) i trening (Forma).
 * Dane przychodzą przez Koofr jako „czynniki-dnia” (AppFactors) i epizody AF (ExtEvent).
 */
data class MyHealthDay(
    val day: LocalDate,
    val sleepMin: Double? = null,
    val sleepScore: Double? = null,
    val apnea: Double? = null, // przerwy w oddechu z mikrofonu, /h
    val cpapAhi: Double? = null, // AHI z aparatu CPAP, /h
    val afEpisodes: Int = 0,
    val afMin: Double = 0.0,
    val hrv: Double? = null,
    val medsTaken: Double? = null,
    val medsPlanned: Double? = null,
    val kcal: Double? = null,
    val alcohol: Double? = null,
    val coffee: Double? = null,
    val trainMin: Double? = null,
) {
    val hasData: Boolean
        get() = sleepMin != null || afEpisodes > 0 || hrv != null || medsPlanned != null || kcal != null || trainMin != null
}

/** Porównanie ostatnich 7 dni z 7 poprzednimi. */
data class MyHealthTrend(val label: String, val now: Double, val before: Double?, val unit: String, val higherBetter: Boolean)

object MyHealth {
    /** Dni od [from] do [to] włącznie (najnowszy pierwszy); AF: Polar EKG i nieregularny rytm z opaski, bez dublowania. */
    fun days(
        factors: Map<String, Map<LocalDate, Map<String, Double>>>,
        ext: List<ExtEvent>,
        from: LocalDate,
        to: LocalDate,
    ): List<MyHealthDay> {
        val af = ext.filter { it.type == "af" && it.source in ExchangeImport.AF_SOURCES && it.end != null }
            .map { it.start to it.end!! }.sortedBy { it.first }
        // epizody nachodzące na siebie (EKG i opaska widziały to samo) liczymy raz
        val merged = ArrayList<Pair<Long, Long>>()
        for (e in af) {
            val last = merged.lastOrNull()
            if (last != null && e.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, e.second)
            else merged += e
        }
        val out = ArrayList<MyHealthDay>()
        var d = to
        while (!d.isBefore(from)) {
            val sleep = factors["rytm-snu"]?.get(d)
            val meals = factors["dziennik-talerza"]?.get(d)
            val meds = factors["teczka"]?.get(d)
            val polar = factors["polar-ekg"]?.get(d)
            val forma = factors["forma"]?.get(d)
            val a = Fmt.dayStart(d)
            val b = Fmt.dayStart(d.plusDays(1))
            val dayAf = merged.filter { it.first < b && it.second > a }
            out += MyHealthDay(
                day = d,
                sleepMin = sleep?.get("sleep_min"),
                sleepScore = sleep?.get("score"),
                apnea = sleep?.get("apnea_idx"),
                cpapAhi = sleep?.get("cpap_ahi"),
                afEpisodes = dayAf.size,
                afMin = dayAf.sumOf { (minOf(it.second, b) - maxOf(it.first, a)) / 60_000.0 },
                hrv = polar?.get("hrv"),
                medsTaken = meds?.get("taken"),
                medsPlanned = meds?.get("doses"),
                kcal = meals?.get("kcal"),
                alcohol = meals?.get("alcohol"),
                coffee = meals?.get("coffee"),
                trainMin = forma?.get("train_min")?.takeIf { it > 0 },
            )
            d = d.minusDays(1)
        }
        return out
    }

    /** Ostatnie 7 dni wobec 7 poprzednich (tylko miary, które mają dane). [days]: 14 dni, najnowszy pierwszy. */
    fun trends(days: List<MyHealthDay>): List<MyHealthTrend> {
        val now = days.take(7)
        val before = days.drop(7).take(7)
        fun avg(l: List<MyHealthDay>, f: (MyHealthDay) -> Double?): Double? = l.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        fun sum(l: List<MyHealthDay>, f: (MyHealthDay) -> Double): Double = l.sumOf(f)
        val out = ArrayList<MyHealthTrend>()
        avg(now) { it.sleepMin }?.let { out += MyHealthTrend("Sen", it / 60, avg(before) { d -> d.sleepMin }?.div(60), " h", true) }
        avg(now) { it.cpapAhi ?: it.apnea }?.let {
            out += MyHealthTrend("Oddech (AHI)", it, avg(before) { d -> d.cpapAhi ?: d.apnea }, "/h", false)
        }
        val anyAf = (now + before).any { it.afEpisodes > 0 }
        if (anyAf) out += MyHealthTrend("Napady AF", sum(now) { it.afEpisodes.toDouble() }, sum(before) { it.afEpisodes.toDouble() }, "", false)
        avg(now) { it.hrv }?.let { out += MyHealthTrend("HRV rano", it, avg(before) { d -> d.hrv }, " ms", true) }
        val planned = sum(now) { it.medsPlanned ?: 0.0 }
        if (planned > 0) {
            val pb = sum(before) { it.medsPlanned ?: 0.0 }
            out += MyHealthTrend("Leki", 100 * sum(now) { it.medsTaken ?: 0.0 } / planned,
                if (pb > 0) 100 * sum(before) { it.medsTaken ?: 0.0 } / pb else null, "%", true)
        }
        if (now.any { it.alcohol != null }) {
            out += MyHealthTrend("Dni z alkoholem", now.count { (it.alcohol ?: 0.0) > 0 }.toDouble(),
                if (before.any { it.alcohol != null }) before.count { (it.alcohol ?: 0.0) > 0 }.toDouble() else null, "", false)
        }
        if (now.any { it.trainMin != null } || before.any { it.trainMin != null }) {
            out += MyHealthTrend("Trening", sum(now) { it.trainMin ?: 0.0 }, sum(before) { it.trainMin ?: 0.0 }, " min", true)
        }
        return out
    }
}
