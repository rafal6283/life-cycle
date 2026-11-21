package pl.rafal.lifecycle

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.domain.PoiHints

/** Podpowiedzi znanych miejsc z OpenStreetMap (odpowiedzi Overpass jak z serwera). */
class PoiHintsTest {
    private val lat = 52.0631
    private val lng = 21.3586

    private val body = """
    {"version":0.6,"elements":[
      {"type":"node","id":1,"lat":52.06315,"lon":21.35865,"tags":{"shop":"supermarket","name":"Biedronka"}},
      {"type":"way","id":22,"center":{"lat":52.0640,"lon":21.3590},"tags":{"amenity":"pharmacy","name":"Apteka Pod Lipą"}},
      {"type":"node","id":3,"lat":52.0632,"lon":21.3587,"tags":{"amenity":"parking","name":"Parking"}},
      {"type":"node","id":4,"lat":52.0632,"lon":21.3587,"tags":{"shop":"convenience"}},
      {"type":"area","id":2400000555,"tags":{"landuse":"cemetery"}},
      {"type":"way","id":555,"center":{"lat":52.0633,"lon":21.3589},"tags":{"landuse":"cemetery"}},
      {"type":"area","id":3600000777,"tags":{"leisure":"park","name":"Park Miejski"}}
    ]}
    """.trimIndent()

    @Test
    fun parsuje_i_sortuje() {
        val l = PoiHints.parse(PoiHints.elementsOf(body), lat, lng)
        val names = l.map { it.label }
        // bez parkingu i bez sklepu bez nazwy
        assertFalse(names.any { it == "Parking" })
        assertEquals(4, l.size)
        // cmentarz: obszar „na którym stoisz” (is_in) i ten sam obiekt z „around” → jedna pozycja, inside
        val cem = l.single { it.value == "cemetery" }
        assertTrue(cem.inside)
        assertEquals("w555", cem.osmId)
        assertEquals("Cmentarz", cem.label)
        // najbliższy punkt pierwszy; obszary liczone jak 25 m
        assertEquals("Biedronka", l.first().name)
        assertTrue(l.indexOfFirst { it.name == "Park Miejski" } < l.indexOfFirst { it.name == "Apteka Pod Lipą" })
        assertEquals("r777", l.single { it.name == "Park Miejski" }.osmId)
    }

    @Test
    fun blad_serwera_to_nie_brak_miejsc() {
        try {
            PoiHints.elementsOf("""{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 21 seconds."}""")
            fail("remark z błędem powinien być błędem")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, PoiHints.elementsOf("""{"elements":[]}""").length())
        assertEquals(JSONArray().length(), PoiHints.elementsOf("""{"version":0.6}""").length())
    }

    @Test
    fun kategoria_po_rodzaju() {
        val cats = listOf(
            Category(id = 1, name = "Zakupy", icon = "shop", color = 0),
            Category(id = 2, name = "Zdrowie", icon = "health", color = 0),
            Category(id = 3, name = "Cmentarz", icon = "🪦", color = 0),
        )
        val l = PoiHints.parse(PoiHints.elementsOf(body), lat, lng)
        assertEquals(1L, PoiHints.suggestCategory(l.single { it.name == "Biedronka" }, cats)?.id)
        assertEquals(2L, PoiHints.suggestCategory(l.single { it.name == "Apteka Pod Lipą" }, cats)?.id)
        assertEquals(3L, PoiHints.suggestCategory(l.single { it.value == "cemetery" }, cats)?.id)
    }
}
