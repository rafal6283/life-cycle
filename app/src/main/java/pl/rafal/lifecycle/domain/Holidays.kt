package pl.rafal.lifecycle.domain

import java.time.LocalDate

/** Dni ustawowo wolne od pracy w Polsce. */
object Holidays {
    private val cache = HashMap<Int, Map<LocalDate, String>>()

    /** Niedziela Wielkanocna (algorytm Meeusa/Jonesa/Butchera). */
    fun easter(y: Int): LocalDate {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }

    fun year(y: Int): Map<LocalDate, String> = cache.getOrPut(y) {
        val e = easter(y)
        val m = linkedMapOf(
            LocalDate.of(y, 1, 1) to "Nowy Rok",
            LocalDate.of(y, 1, 6) to "Trzech Króli",
            e to "Wielkanoc",
            e.plusDays(1) to "Poniedziałek Wielkanocny",
            LocalDate.of(y, 5, 1) to "Święto Pracy",
            LocalDate.of(y, 5, 3) to "Święto Konstytucji 3 Maja",
            e.plusDays(49) to "Zielone Świątki",
            e.plusDays(60) to "Boże Ciało",
            LocalDate.of(y, 8, 15) to "Wniebowzięcie NMP",
            LocalDate.of(y, 11, 1) to "Wszystkich Świętych",
            LocalDate.of(y, 11, 11) to "Święto Niepodległości",
            LocalDate.of(y, 12, 25) to "Boże Narodzenie",
            LocalDate.of(y, 12, 26) to "Drugi dzień Świąt",
        )
        // Wigilia wolna od 2025 r.
        if (y >= 2025) m[LocalDate.of(y, 12, 24)] = "Wigilia"
        m
    }

    fun name(d: LocalDate): String? = year(d.year)[d]
}
