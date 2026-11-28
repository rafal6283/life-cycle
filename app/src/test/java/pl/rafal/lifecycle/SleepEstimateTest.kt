package pl.rafal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rafal.lifecycle.domain.SleepEstimate
import java.time.LocalDate
import java.time.ZoneId

class SleepEstimateTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val d = LocalDate.of(2026, 11, 24)
    private fun ms(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val n1 = d.plusDays(1)
    private val def = SleepEstimate.norm(emptyList(), emptyList(), -1, zone)

    @Test
    fun telefon_odlozony_o_18_sen_od_zwyklej_pory() {
        val r = SleepEstimate.night(ms(d, 18, 10), ms(n1, 6, 50), def, emptyMap(), emptyList(), zone)
        assertEquals(ms(d, 22, 30), r.start)
        assertEquals(ms(n1, 6, 50), r.end)
        assertTrue(r.estimatedStart)
    }

    @Test
    fun ladowarka_przed_zwykla_pora() {
        val r = SleepEstimate.night(ms(d, 18, 10), ms(n1, 6, 50), def, emptyMap(), listOf(ms(d, 19, 0), ms(d, 21, 50)), zone)
        assertEquals(ms(d, 21, 50), r.start)
    }

    @Test
    fun zwykla_noc_bez_zmian() {
        val r = SleepEstimate.night(ms(d, 23, 15), ms(n1, 6, 40), def, emptyMap(), emptyList(), zone)
        assertEquals(ms(d, 23, 15), r.start)
        assertEquals(ms(n1, 6, 40), r.end)
        assertFalse(r.estimatedStart)
        assertFalse(r.estimatedEnd)
    }

    @Test
    fun kroki_wieczorem_i_rano() {
        val steps = mapOf(ms(d, 22, 0) to 300L, ms(n1, 3, 0) to 20L, ms(n1, 6, 0) to 150L)
        val r = SleepEstimate.night(ms(d, 18, 0), ms(n1, 8, 30), def, steps, emptyList(), zone)
        assertEquals(ms(d, 22, 45), r.start)
        assertEquals(ms(n1, 6, 30), r.end)
        assertTrue(r.estimatedEnd)
    }

    @Test
    fun pora_z_nawykow_i_ustawiona() {
        val days = (1..5).map { d.minusDays(it.toLong()) }
        val trusted = days.take(3).map { ms(it, 23, 30) to ms(it.plusDays(1), 7, 0) }
        val earlyAuto = days.map { ms(it, 19, 0) to ms(it.plusDays(1), 7, 0) }
        val n = SleepEstimate.norm(trusted, earlyAuto, -1, zone)
        assertEquals(23 * 60 + 30, n.bedMin)
        assertEquals(7 * 60, n.wakeMin)
        assertEquals(21 * 60, SleepEstimate.norm(trusted, earlyAuto, 21 * 60, zone).bedMin)
        val r = SleepEstimate.night(ms(d, 18, 0), ms(n1, 7, 0), n, emptyMap(), emptyList(), zone)
        assertEquals(ms(d, 23, 30), r.start)
    }

    @Test
    fun polozenie_sie_wczesniej_niz_zwykle_nie_jest_przesuwane() {
        val late = SleepEstimate.Norm(0, 7 * 60, 10) // zwykle zasypia o północy
        val r = SleepEstimate.night(ms(d, 23, 0), ms(n1, 6, 45), late, emptyMap(), emptyList(), zone)
        assertEquals(ms(d, 23, 0), r.start)
        assertFalse(r.estimatedStart)
        val r2 = SleepEstimate.night(ms(d, 21, 30), ms(n1, 6, 45), late, emptyMap(), emptyList(), zone)
        assertEquals(ms(d, 21, 30), r2.start)
    }
}
