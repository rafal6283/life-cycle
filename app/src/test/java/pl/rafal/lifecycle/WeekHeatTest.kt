package pl.rafal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.domain.WeekHeat
import java.time.LocalDate
import java.time.ZoneId

class WeekHeatTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun praca_w_dni_robocze() {
        val mon = LocalDate.of(2026, 11, 2) // poniedziałek
        val entries = (0 until 14).map { mon.plusDays(it.toLong()) }.filter { it.dayOfWeek.value <= 5 }
            .map { Entry(startAt = ms(it, 8), endAt = ms(it, 16, 30), categoryId = 7, source = "t") } +
            Entry(startAt = ms(mon, 20), endAt = ms(mon, 21), categoryId = 3, source = "t")
        val heat = WeekHeat.compute(entries, 7, mon, mon.plusDays(14), ms(mon.plusDays(20), 0), zone)
        assertEquals(1f, heat[0][9], 1e-6f)   // pn 9:00 – zawsze praca
        assertEquals(0.5f, heat[2][16], 1e-6f) // śr 16:00–16:30 – połowa godziny
        assertEquals(0f, heat[5][9], 1e-6f)   // sobota – nie
        assertEquals(0f, heat[0][20], 1e-6f)  // inna kategoria
        val p = WeekHeat.peak(heat)!!
        assertEquals(0, p.first)
        assertEquals(8, p.second)
    }

    @Test
    fun przez_polnoc_i_zmiana_czasu() {
        val sat = LocalDate.of(2026, 10, 24) // noc zmiany czasu 24/25.10
        val e = Entry(startAt = ms(sat, 23), endAt = ms(sat.plusDays(1), 7), categoryId = 1, source = "t")
        val heat = WeekHeat.compute(listOf(e), 1, sat, sat.plusDays(2), ms(sat.plusDays(3), 0), zone)
        assertEquals(1f, heat[5][23], 1e-6f) // sobota 23
        assertEquals(1f, heat[6][2], 1e-6f)  // niedziela 2:00 – dwie godziny 2:00 (podwójna) przycięte do 100%
        assertEquals(1f, heat[6][6], 1e-6f)
        assertEquals(0f, heat[6][7], 1e-6f)
    }
}
