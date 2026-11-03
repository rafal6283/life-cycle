package pl.rafal.lifecycle.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.util.Geo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Miejsce publiczne z OpenStreetMap w pobliżu pobytu (sklep, apteka, cmentarz…). */
data class Poi(
    val name: String,
    /** Rodzaj po polsku, np. „apteka”. */
    val type: String,
    val key: String,
    val value: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Double,
    /** Pobyt leży na terenie tego obszaru (cmentarz, park, centrum handlowe). */
    val inside: Boolean,
    /** Identyfikator obiektu OSM — ten sam obszar dla kilku nieznanych miejsc = jedno miejsce. */
    val osmId: String,
) {
    /** Nazwa do podpowiedzi: własna albo rodzaj z wielkiej litery. */
    val label: String get() = name.ifBlank { type.replaceFirstChar { it.uppercase() } }
}

object PoiHints {
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val TTL = 60L * 86_400_000L
    private val mutex = Mutex()

    private val types = mapOf(
        "supermarket" to "supermarket", "convenience" to "sklep spożywczy", "bakery" to "piekarnia",
        "butcher" to "mięsny", "greengrocer" to "warzywniak", "mall" to "centrum handlowe", "department_store" to "dom towarowy",
        "clothes" to "sklep odzieżowy", "shoes" to "obuwniczy", "hardware" to "sklep budowlany", "doityourself" to "market budowlany",
        "electronics" to "elektronika", "florist" to "kwiaciarnia", "kiosk" to "kiosk", "chemist" to "drogeria",
        "pharmacy" to "apteka", "hospital" to "szpital", "clinic" to "przychodnia", "doctors" to "lekarz", "dentist" to "dentysta",
        "veterinary" to "weterynarz", "cemetery" to "cmentarz", "grave_yard" to "cmentarz", "place_of_worship" to "kościół",
        "fuel" to "stacja paliw", "car_repair" to "warsztat", "car_wash" to "myjnia", "restaurant" to "restauracja",
        "cafe" to "kawiarnia", "fast_food" to "fast food", "pub" to "pub", "bar" to "bar", "ice_cream" to "lodziarnia",
        "bank" to "bank", "post_office" to "poczta", "school" to "szkoła", "kindergarten" to "przedszkole",
        "university" to "uczelnia", "library" to "biblioteka", "townhall" to "urząd", "police" to "policja",
        "park" to "park", "garden" to "ogród", "playground" to "plac zabaw", "fitness_centre" to "siłownia",
        "sports_centre" to "centrum sportowe", "stadium" to "stadion", "swimming_pool" to "basen", "pitch" to "boisko",
        "hairdresser" to "fryzjer", "beauty" to "salon urody", "cinema" to "kino", "theatre" to "teatr",
        "hotel" to "hotel", "marketplace" to "targowisko", "retail" to "teren handlowy", "parcel_locker" to "paczkomat",
    )
    private val ignored = setOf(
        "parking", "parking_entrance", "parking_space", "bench", "waste_basket", "waste_disposal", "bicycle_parking",
        "recycling", "toilets", "telephone", "vending_machine", "post_box", "atm", "drinking_water", "shelter", "grave",
    )

    private fun typeOf(k: String, v: String): String = types[v] ?: when (k) {
        "shop" -> "sklep"
        "office" -> "biuro"
        "craft" -> "usługi"
        "healthcare" -> "zdrowie"
        else -> v.replace('_', ' ')
    }

    private fun cacheKey(lat: Double, lng: Double) = String.format(Locale.US, "%.4f,%.4f", lat, lng)

    /** Podpowiedzi dla punktu (z pamięci podręcznej albo z Overpass). Null = brak internetu / błąd. */
    suspend fun near(ctx: Context, lat: Double, lng: Double, radius: Int = 80): List<Poi>? = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("poi", Context.MODE_PRIVATE)
        val key = cacheKey(lat, lng)
        sp.getString(key, null)?.let { s ->
            try {
                val j = JSONObject(s)
                if (System.currentTimeMillis() - j.getLong("t") < TTL) return@withContext parse(j.getJSONArray("e"), lat, lng)
            } catch (_: Exception) {
            }
        }
        mutex.withLock {
            try {
                val f = { v: Double -> String.format(Locale.US, "%.6f", v) }
                val p = "${f(lat)},${f(lng)}"
                val q = "[out:json][timeout:20];" +
                    "(nwr(around:$radius,$p)[name][~\"^(shop|amenity|leisure|tourism|office|craft|healthcare|sport)$\"~\".\"];" +
                    "nwr(around:$radius,$p)[landuse=cemetery];nwr(around:$radius,$p)[amenity=grave_yard];)->.near;" +
                    "is_in($p)->.a;(area.a[landuse~\"^(cemetery|retail)$\"];" +
                    "area.a[amenity~\"^(grave_yard|hospital|university|school|kindergarten|place_of_worship|marketplace|clinic)$\"];" +
                    "area.a[leisure~\"^(park|sports_centre|stadium|garden)$\"];area.a[shop];)->.inside;" +
                    ".near out center tags;.inside out tags;"
                val c = URL(OVERPASS).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "Doba/1.0 (Android)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val els = try {
                    c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("elements") ?: JSONArray()
                } finally {
                    c.disconnect()
                }
                sp.edit().putString(key, JSONObject().put("t", System.currentTimeMillis()).put("e", els).toString()).apply()
                parse(els, lat, lng)
            } catch (e: Exception) {
                Log.w("Doba", "Podpowiedzi OSM: ${e.message}")
                null
            }
        }
    }

    private fun parse(els: JSONArray, lat: Double, lng: Double): List<Poi> {
        val out = LinkedHashMap<String, Poi>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val type = e.optString("type")
            val inside = type == "area"
            val kv = listOf("amenity", "shop", "landuse", "leisure", "healthcare", "tourism", "office", "craft", "sport")
                .firstNotNullOfOrNull { k -> tags.optString(k).takeIf { it.isNotBlank() }?.let { k to it } } ?: continue
            if (kv.second in ignored) continue
            val name = tags.optString("name").trim()
            val isCemetery = kv.second == "cemetery" || kv.second == "grave_yard"
            if (name.isEmpty() && !isCemetery) continue
            val pLat = e.optJSONObject("center")?.optDouble("lat") ?: e.optDouble("lat", lat)
            val pLng = e.optJSONObject("center")?.optDouble("lon") ?: e.optDouble("lon", lng)
            // Obszar z is_in ma id 24…/36… + id drogi/relacji; sprowadzamy do wspólnego klucza.
            val rawId = e.optLong("id")
            val osmId = when {
                inside && rawId >= 3_600_000_000L -> "r${rawId - 3_600_000_000L}"
                inside && rawId >= 2_400_000_000L -> "w${rawId - 2_400_000_000L}"
                type == "way" -> "w$rawId"
                type == "relation" -> "r$rawId"
                else -> "n$rawId"
            }
            val d = if (inside) 0.0 else Geo.distance(lat, lng, pLat, pLng)
            val poi = Poi(name, typeOf(kv.first, kv.second), kv.first, kv.second, pLat, pLng, d, inside, osmId)
            val old = out[osmId]
            if (old == null || (inside && !old.inside)) out[osmId] = poi
        }
        // Obszary, na których stoisz, liczymy jak bardzo bliskie; dokładne punkty (sklep w galerii) i tak wygrywają.
        return out.values.sortedBy { if (it.inside) 25.0 else it.distanceM }.take(8)
    }

    /** Pasująca kategoria użytkownika (po rodzaju, ikonie albo nazwie) albo null. */
    fun suggestCategory(p: Poi, cats: List<Category>): Category? {
        fun byIcon(vararg icons: String) = cats.firstOrNull { it.icon in icons }
        fun byName(vararg parts: String) = cats.firstOrNull { c -> parts.any { c.name.contains(it, ignoreCase = true) } }
        fun byKind(k: String) = cats.firstOrNull { it.kind == k }
        val v = p.value
        return when {
            v == "cemetery" || v == "grave_yard" -> byName("cmentarz", "grob", "grób") ?: cats.firstOrNull { it.icon.contains("🪦") || it.icon.contains("⚰") }
            v in setOf("restaurant", "cafe", "fast_food", "pub", "bar", "ice_cream", "bakery") ->
                byName("jedzenie", "restaur", "kawiar") ?: byIcon("food", "coffee", "local_pizza", "fastfood")
            v in setOf("pharmacy", "hospital", "clinic", "doctors", "dentist") || p.key == "healthcare" ->
                byName("zdrow", "lekarz", "przychod", "aptek") ?: byIcon("health", "local_pharmacy", "medical_services", "local_hospital")
            v == "place_of_worship" -> byName("kości", "msza", "cerkiew") ?: byIcon("church")
            v == "school" || v == "kindergarten" -> byKind(Kind.SCHOOL) ?: byName("szkoł", "przedszk")
            v == "university" || v == "library" -> byName("nauka", "uczel") ?: byIcon("study")
            v in setOf("fitness_centre", "sports_centre", "swimming_pool", "stadium") -> byKind(Kind.WORKOUT) ?: byName("siłown", "sport")
            v in setOf("park", "garden", "playground") -> byName("park", "spacer") ?: byIcon("park")
            v in setOf("cinema", "theatre") -> byName("rozryw", "kino") ?: byIcon("fun", "theaters")
            v in setOf("fuel", "car_repair", "car_wash") -> byName("samoch", "auto", "stacja") ?: byIcon("local_gas_station", "car_repair")
            v == "hairdresser" || v == "beauty" -> byName("fryzj", "uroda") ?: byIcon("hair")
            p.key == "shop" || v in setOf("marketplace", "retail", "mall") -> byName("zakup") ?: byIcon("shop", "bag", "storefront", "local_grocery_store")
            else -> null
        }
    }
}
