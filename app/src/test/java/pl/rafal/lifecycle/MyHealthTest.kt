package pl.rafal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rafal.lifecycle.data.ExtEvent
import pl.rafal.lifecycle.domain.ExchangeImport
import pl.rafal.lifecycle.domain.MyHealth
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

class MyHealthTest {
    private val today = LocalDate.of(2026, 11, 20)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Fmt.dayStart(d) + h * 3_600_000L + m * 60_000L

    @Test
    fun dzienZeWszystkichAplikacji() {
        val d = today.minusDays(1)
        val factors = mapOf(
            "rytm-snu" to mapOf(d to mapOf("sleep_min" to 412.0, "score" to 78.0, "apnea_idx" to 4.2, "cpap_ahi" to 2.1)),
            "polar-ekg" to mapOf(d to mapOf("hrv" to 38.4)),
            "teczka" to mapOf(d to mapOf("doses" to 2.0, "taken" to 1.0)),
            "dziennik-talerza" to mapOf(d to mapOf("kcal" to 2150.0, "alcohol" to 1.0, "coffee" to 2.0)),
            "forma" to mapOf(d to mapOf("train_min" to 32.0)),
        )
        // ten sam napad widziany przez EKG i opaskę — liczy się raz; drugi osobno
        val ext = listOf(
            ExtEvent(source = ExchangeImport.SRC_ECG, label = "", type = "af", start = at(d, 22, 0), end = at(d, 22, 4)),
            ExtEvent(source = ExchangeImport.SRC_RHYTHM, label = "", type = "af", start = at(d, 22, 2), end = at(d, 22, 6)),
            ExtEvent(source = ExchangeImport.SRC_ECG, label = "", type = "af", start = at(d, 23, 0), end = at(d, 23, 1)),
            ExtEvent(source = ExchangeImport.SRC_ECG, label = "", type = "window", start = at(d, 20), end = at(d, 23, 30)),
        )
        val days = MyHealth.days(factors, ext, today.minusDays(13), today)
        assertEquals(14, days.size)
        assertEquals(today, days.first().day)
        assertFalse(days.first().hasData)
        val y = days[1]
        assertEquals(2, y.afEpisodes)
        assertEquals(7.0, y.afMin, 0.01)
        assertEquals(2.1, y.cpapAhi!!, 0.0)
        assertEquals(32.0, y.trainMin!!, 0.0)
        assertTrue(y.hasData)
    }

    @Test
    fun napadPrzezPolnocDzielonyNaDni() {
        val d = today.minusDays(1)
        val ext = listOf(ExtEvent(source = ExchangeImport.SRC_ECG, label = "", type = "af", start = at(d, 23, 50), end = at(today, 0, 20)))
        val days = MyHealth.days(emptyMap(), ext, d, today)
        assertEquals(20.0, days[0].afMin, 0.01)
        assertEquals(10.0, days[1].afMin, 0.01)
    }

    @Test
    fun tydzienWobecPoprzedniego() {
        val factors = mapOf(
            "rytm-snu" to (0L..13L).associate { today.minusDays(it) to mapOf("sleep_min" to if (it < 7) 420.0 else 360.0) },
            "teczka" to (0L..13L).associate { today.minusDays(it) to mapOf("doses" to 2.0, "taken" to if (it < 7) 2.0 else 1.0) },
        )
        val t = MyHealth.trends(MyHealth.days(factors, emptyList(), today.minusDays(13), today)).associateBy { it.label }
        assertEquals(7.0, t.getValue("Sen").now, 0.001)
        assertEquals(6.0, t.getValue("Sen").before!!, 0.001)
        assertEquals(100.0, t.getValue("Leki").now, 0.001)
        assertEquals(50.0, t.getValue("Leki").before!!, 0.001)
        assertNull(t["Napady AF"]) // bez napadów — bez kafelka
        assertNull(t["Trening"])
    }
}
