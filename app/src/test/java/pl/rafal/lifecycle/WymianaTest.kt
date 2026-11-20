package pl.rafal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.rafal.lifecycle.cloud.DobaCloud
import pl.rafal.lifecycle.domain.AppFactors
import pl.rafal.lifecycle.domain.ExchangeImport
import pl.rafal.lifecycle.util.Fmt
import java.io.File
import java.time.LocalDate

/**
 * Kontrakt wymiany (WYMIANA.md w repozytorium Rytmu Snu): każdy plik, który Doba pobiera, musi się
 * wczytać bez wyjątku. Przykłady z folderu `wymiana` (pliki .json) pobiera CI do src/test/resources/wymiana.
 */
class WymianaTest {
    private val dir = File("src/test/resources/wymiana")

    private fun samples(): Map<String, String> {
        assumeTrue("brak przykładów wymiany (pobiera je CI)", dir.isDirectory)
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.associate { it.name.removeSuffix(".json") to it.readText() }
    }

    private val sources = DobaCloud.exchange.sources.map { it.first }
    private fun pulls(n: String) = sources.any { p -> n == p || n.startsWith("$p-") }

    @Test
    fun kazdyPobieranyPlikSieWczytuje() {
        val s = samples()
        val pulled = s.keys.filter(::pulls)
        // wszystko poza nocami minutowymi z Polar EKG (te są dla Rytmu Snu)
        assertEquals(s.keys - setOf("polar-noce"), pulled.toSet())
        for (n in pulled) {
            val txt = s.getValue(n)
            if (n.startsWith("czynniki-")) assertNotNull(n, AppFactors.parse(txt)) else ExchangeImport.parse(txt)
        }
    }

    @Test
    fun rytmZOpaskiIDawnyPlikNocy() {
        val r = ExchangeImport.parse(samples().getValue("rytm-snu-rytm"))
        assertEquals(ExchangeImport.SRC_RHYTHM, r.source)
        assertEquals(1, r.rows.count { it.type == "af" })
        val n = ExchangeImport.parse(samples().getValue("rytm-snu"))
        assertEquals(ExchangeImport.SRC_NIGHT, n.source)
        assertEquals(2, n.rows.count { it.type == "fart" })
        // starszy Rytm Snu wysyłał AF w pliku nocy — trafia do źródła rytmu
        val legacy = """{"format":"wymiana-zdarzen","version":1,"source":"rytm-snu","windows":[],
            "events":[{"type":"af","start":1000,"end":5000},{"type":"fart","start":2000,"verified":false}]}"""
        val l = ExchangeImport.parse(legacy)
        assertEquals(1, l.legacyAf.size)
        assertTrue(l.rows.none { it.type == "fart" }) // odrzucone ✗ pomijane
    }

    @Test
    fun kreskaLicznikIZdarzenie() {
        val c = ExchangeImport.parse(samples().getValue("kreska-ff"))
        assertEquals("FF", c.label)
        assertEquals(2, c.rows.count { it.type == "tap" })
        val counts = ExchangeImport.dailyCounts(c.rows, ExchangeImport.SRC_KRESKA, "FF")
        assertEquals(mapOf(LocalDate.parse("2025-11-18") to 1, LocalDate.parse("2025-11-19") to 1), counts)
        val t = ExchangeImport.parse(samples().getValue("kreska-z-afib"))
        assertEquals("AFIB", t.label)
        assertEquals(1, t.rows.count { it.type == "session" })
    }

    @Test
    fun czynnikiDnia() {
        val (src, days) = AppFactors.parse(samples().getValue("czynniki-sen"))!!
        assertEquals("rytm-snu", src)
        assertEquals(412.0, days[LocalDate.parse("2025-11-19")]!!["sleep_min"]!!, 0.0)
        assertEquals(null, AppFactors.parse(samples().getValue("polar-ekg"))) // inny format — pomijany
    }

    @Test
    fun nocLiczonaDoDniaPobudki() {
        val evening = Fmt.dayStart(LocalDate.parse("2025-11-18")) + 23 * 3_600_000L
        val morning = Fmt.dayStart(LocalDate.parse("2025-11-19")) + 3 * 3_600_000L
        val rows = listOf(evening, morning).map {
            pl.rafal.lifecycle.data.ExtEvent(source = ExchangeImport.SRC_NIGHT, label = "", type = "fart", start = it)
        }
        assertEquals(mapOf(LocalDate.parse("2025-11-19") to 2), ExchangeImport.dailyCounts(rows, ExchangeImport.SRC_NIGHT, ""))
    }

    @Test
    fun nieobslugiwanyPlikToCzytelnyBlad() {
        val e = runCatching { ExchangeImport.parse("""{"format":"doba-backup"}""") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("kopia"))
    }
}
