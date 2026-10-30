package pl.rafal.lifecycle.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.domain.FieldWork
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.domain.Trip
import pl.rafal.lifecycle.domain.Trips
import pl.rafal.lifecycle.domain.TripsResult
import pl.rafal.lifecycle.domain.Visited
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

/** Mapa osmdroid z obsługą cyklu życia; [setup] wywoływane przy każdej zmianie [key]. */
@Composable
fun OsmMap(key: Any?, modifier: Modifier, setup: (MapView) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(52.2297, 21.0122))
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    var applied by remember { mutableStateOf<Any?>(Unit) }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        if (applied != key) {
            applied = key
            m.overlays.clear()
            setup(m)
            m.invalidate()
        }
    })
}

/** Dopasowanie widoku do punktów (po ułożeniu mapy). */
fun MapView.fit(points: List<GeoPoint>, maxZoom: Double = 15.0) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        controller.setZoom(maxZoom); controller.setCenter(points[0]); return
    }
    val box = BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.25f)
    post {
        try {
            zoomToBoundingBox(box, false, 40)
            if (zoomLevelDouble > maxZoom) controller.setZoom(maxZoom)
        } catch (_: Exception) {
            controller.setCenter(box.centerWithDateLine)
        }
    }
}

// ---------------------------------------------------------------------------------------------- wyjazdy

@Composable
fun TripsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val res by produceState<TripsResult?>(null) { value = Trips.detect(ctx) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Wyjazdy", onBack = onBack) }
        val r = res
        when {
            r == null -> item { Text("Szukam wyjazdów…", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            r.noHome -> item {
                Text(
                    "Zapisz swój dom w zakładce Miejsca (kategoria „Dom”) — wyjazdem będzie każdy pobyt " +
                        "co najmniej ${pl.rafal.lifecycle.util.Prefs(ctx).tripKm} km od domu trwający ponad dobę.",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            r.trips.isEmpty() -> item {
                Text(
                    "Nie było jeszcze wyjazdów — Doba zapisze je sama, gdy spędzisz ponad dobę " +
                        "dalej niż ${pl.rafal.lifecycle.util.Prefs(ctx).tripKm} km od domu.",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                item {
                    TrendCard {
                        Row {
                            TStat("${r.trips.size}", "wyjazdów", Modifier.weight(1f))
                            TStat("${r.trips.sumOf { it.nights }}", "nocy poza domem", Modifier.weight(1f))
                            TStat(Fmt.distance(r.trips.sumOf { it.km }), "w trasie", Modifier.weight(1f))
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                items(r.trips, key = { it.start }) { t -> TripRow(t) { onOpen("trip:${t.start}") } }
            }
        }
    }
}

@Composable
private fun TStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF5AC8FA)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Luggage, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                Trips.dates(t) + " · ${t.nights} ${Trips.nightsWord(t.nights)}" + (if (t.ongoing) " · trwa" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (t.km > 0) Text(Fmt.distance(t.km), fontWeight = FontWeight.SemiBold)
            Text("${t.maxFromHomeKm.toInt()} km od domu", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TripDetailScreen(start: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = LifeApp.instance
    data class Detail(
        val trip: Trip, val route: List<GeoPoint>, val totals: List<Pair<Long, Long>>,
        val places: List<Triple<String, Long, Int>>, val steps: Long,
    )
    val d by produceState<Detail?>(null, start) {
        val t = Trips.detect(ctx).trips.firstOrNull { it.start == start } ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val route = app.db.locations().range(t.start, t.end).filter { it.accuracy <= 100f }.map { GeoPoint(it.lat, it.lng) }
            val entries = app.repo.getRange(t.start, t.end)
            val now = System.currentTimeMillis()
            val totals = Timeline.totals(Timeline.resolve(entries, t.start, t.end, now)).toList().sortedByDescending { it.second }
            val places = t.stays.groupBy { "%.3f,%.3f".format(java.util.Locale.US, it.lat, it.lng) }.map { (_, l) ->
                val f = l.first()
                val name = f.placeId?.let { app.db.places().get(it)?.name } ?: FieldWork.address(ctx, f.lat!!, f.lng!!) ?: "Miejsce"
                Triple(name, l.sumOf { (it.endAt ?: now) - it.startAt }, l.size)
            }.sortedByDescending { it.second }
            val steps = pl.rafal.lifecycle.tracking.Steps.total(app.db.steps().getRange(t.start, t.end))
            Detail(t, route, totals, places, steps)
        }
    }
    val categories by app.repo.categories.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader(d?.trip?.name ?: "Wyjazd", onBack = onBack) }
        val x = d ?: return@LazyColumn
        val t = x.trip
        item {
            Text(
                Trips.dates(t) + " · ${t.nights} ${Trips.nightsWord(t.nights)} · ${Fmt.duration(t.durationMs)}",
                Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OsmMap(
                key = t.start,
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp)),
            ) { m ->
                val pts = x.route.ifEmpty { t.stays.map { GeoPoint(it.lat!!, it.lng!!) } }
                if (pts.size > 1) {
                    m.overlays.add(Polyline(m).apply {
                        setPoints(pts)
                        outlinePaint.color = 0xFF0A84FF.toInt()
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    })
                }
                t.stays.forEach { s ->
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(s.lat!!, s.lng!!)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = Fmt.dateTime(s.startAt)
                    })
                }
                m.fit(pts + t.stays.map { GeoPoint(it.lat!!, it.lng!!) }, 14.0)
            }
        }
        item {
            TrendCard {
                Row {
                    TStat(if (t.km > 0) Fmt.distance(t.km) else "—", "przejechane", Modifier.weight(1f))
                    TStat("${t.maxFromHomeKm.toInt()} km", "najdalej od domu", Modifier.weight(1f))
                    TStat(if (x.steps > 0) Fmt.steps(x.steps) else "—", "kroków", Modifier.weight(1f))
                }
            }
        }
        if (x.totals.isNotEmpty()) {
            item { SectionTitle("Na co poszedł czas") }
            item {
                val sum = x.totals.sumOf { it.second }.coerceAtLeast(1)
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
                    x.totals.take(8).forEach { (id, ms) -> SummaryRowSimple(catMap[id], ms, sum) }
                }
            }
        }
        if (x.places.isNotEmpty()) {
            item { SectionTitle("Odwiedzone miejsca") }
            items(x.places) { (name, ms, n) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(name, Modifier.weight(1f), maxLines = 1)
                    Text("${Fmt.durationShort(ms)}" + if (n > 1) " · ${n}×" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SummaryRowSimple(cat: pl.rafal.lifecycle.data.Category?, ms: Long, total: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(cat, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cat?.name ?: "—")
            ShareBar(ms.toFloat() / total, catColor(cat))
        }
        Spacer(Modifier.width(10.dp))
        Text(Fmt.durationShort(ms), fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------------------------- mapa cieplna

/** Warstwa „ciepła”: rozmyte punkty sumujące się w jaśniejsze plamy. */
private class HeatOverlay(private val points: List<Visited.Point>) : Overlay() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val r = (map.context.resources.displayMetrics.density * (10 + map.zoomLevelDouble.coerceIn(4.0, 18.0) * 1.2)).toFloat()
        val maxW = points.maxOfOrNull { it.weight }?.coerceAtLeast(0.5) ?: 1.0
        for (pt in points) {
            proj.toPixels(GeoPoint(pt.lat, pt.lng), p)
            if (p.x < -r || p.y < -r || p.x > c.width + r || p.y > c.height + r) continue
            val w = (kotlin.math.sqrt(pt.weight / maxW)).coerceIn(0.06, 1.0)
            val a = (40 + 170 * w).toInt()
            val color = when {
                w > 0.6 -> 0xFF3B30
                w > 0.25 -> 0xFF9F0A
                w > 0.1 -> 0xFFD60A
                else -> 0x0A84FF
            }
            val rr = r * (0.6f + w.toFloat())
            paint.shader = RadialGradient(
                p.x.toFloat(), p.y.toFloat(), rr,
                intArrayOf((a shl 24) or color, color), null, Shader.TileMode.CLAMP,
            )
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rr, paint)
        }
    }
}

@Composable
fun HeatMapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var range by rememberSaveable { mutableIntStateOf(1) }
    val ranges = listOf(30 to "30 dni", 365 to "Rok", null to "Wszystko")
    var resolveMore by remember { mutableIntStateOf(0) }
    val res by produceState<Visited.Result?>(null, range, resolveMore) {
        value = Visited.compute(ctx, ranges[range].first, 60 + resolveMore * 120)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Gdzie bywasz", onBack = onBack) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranges.forEachIndexed { i, (_, l) -> FilterChip(range == i, { range = i }, label = { Text(l) }) }
            }
        }
        val r = res
        item {
            OsmMap(
                key = r?.points?.size to range,
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(360.dp).clip(RoundedCornerShape(20.dp)),
            ) { m ->
                if (r == null) return@OsmMap
                m.overlays.add(HeatOverlay(r.points))
                val heavy = r.points.sortedByDescending { it.weight }.take(200).map { GeoPoint(it.lat, it.lng) }
                m.fit(heavy, 13.0)
            }
        }
        if (r == null) {
            item { Text("Liczę…", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }
        item {
            TrendCard {
                Row {
                    TStat("${r.localities.size}", "miejscowości", Modifier.weight(1f))
                    TStat("${r.newPlacesThisMonth}", "nowych miejsc\nw tym miesiącu", Modifier.weight(1f))
                    val monthStart = LocalDate.now().withDayOfMonth(1)
                    TStat("${r.localities.count { it.first >= monthStart }}", "nowych\nmiejscowości", Modifier.weight(1f))
                }
                if (r.pending > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Jeszcze ${r.pending} okolic bez nazwy — dotknij, by ustalić kolejne (wymaga internetu).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { resolveMore++ },
                    )
                }
            }
        }
        if (r.localities.isNotEmpty()) {
            item { SectionTitle("Miejscowości") }
            items(r.localities.take(60)) { l ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, maxLines = 1)
                        Text(
                            "pierwszy raz ${Fmt.dayTitle(l.first)} · ${l.visits} ${if (l.visits == 1) "pobyt" else "pobytów"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(Fmt.durationShort(l.ms), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
