package pl.rafal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.domain.RoutePoint
import pl.rafal.lifecycle.domain.Routes

class RoutesTest {
    private val t0 = 1_763_600_000_000L
    private val degPerKm = 1.0 / 111.19493 // 1 km na południku (haversine, R = 6371 km)

    /** Prosto na północ: punkt co [stepKm] km co [stepS] s. */
    private fun line(n: Int, stepKm: Double, stepS: Long) =
        (0 until n).map { RoutePoint(52.0 + it * stepKm * degPerKm, 21.0, t0 + it * stepS * 1000) }

    private fun entry(pts: List<RoutePoint>) = Entry(startAt = pts.first().t, endAt = pts.last().t, categoryId = 1, source = "test")

    @Test
    fun dystansIMiedzyczasyZGestychPunktow() {
        val pts = line(5, 0.5025, 60) // 2,01 km w 4 min ≈ 30 km/h (końcówka poniżej pełnego km nie jest międzyczasem)
        val s = Routes.stats(entry(pts), pts, Kind.BIKE)
        assertEquals(2010.0, s.distanceM, 1.0)
        assertEquals(30.15, s.avgKmh, 0.1)
        assertEquals(2, s.splits.size)
        s.splits.forEach { assertEquals(119_400.0, it.toDouble(), 500.0) }
        assertNull(s.paceSecPerKm) // tempo tylko dla chodu i biegu
    }

    @Test
    fun rzadkiePunktyGpsDajaRozsadneMiedzyczasy() {
        // dwa punkty ~3 km od siebie w 6 min: każdy kilometr po ~2 min (wcześniej wychodziło 250 km/h)
        val pts = line(2, 3.03, 360)
        val s = Routes.stats(entry(pts), pts, Kind.TRAVEL)
        assertEquals(3, s.splits.size)
        s.splits.forEach { assertEquals(118_800.0, it.toDouble(), 500.0) }
    }

    @Test
    fun tempoSpaceru() {
        val pts = line(11, 0.1, 72) // 1 km w 12 min
        val s = Routes.stats(entry(pts), pts, Kind.WALK)
        assertEquals(720.0, s.paceSecPerKm!!, 2.0)
        assertEquals(0, s.stops)
    }

    @Test
    fun zapisIUproszczenieTrasy() {
        val pts = line(20, 0.05, 10)
        val back = Routes.decode(Routes.encode(pts, t0), t0)
        assertEquals(pts.size, back.size)
        assertEquals(pts.last().lat, back.last().lat, 1e-5)
        assertEquals(pts.last().t, back.last().t)
        // prosta linia — zostają tylko końce
        assertEquals(2, Routes.simplify(pts).size)
        // zakręt zostaje
        val bent = pts + RoutePoint(pts.last().lat, 21.01, pts.last().t + 10_000)
        assertTrue(Routes.simplify(bent).size >= 3)
    }
}
