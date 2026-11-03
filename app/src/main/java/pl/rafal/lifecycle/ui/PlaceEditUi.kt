package pl.rafal.lifecycle.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.domain.Clusters
import pl.rafal.lifecycle.domain.Poi
import pl.rafal.lifecycle.domain.PoiHints
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Geo
import kotlin.math.roundToInt

/**
 * Mała mapa z pinezką do ręcznej korekty położenia: przeciągnij pinezkę albo dotknij mapy.
 * [others] — sąsiednie miejsca (szare kółka), żeby było widać, co można połączyć.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PinPicker(
    lat: Double,
    lng: Double,
    radius: Double,
    modifier: Modifier = Modifier,
    others: List<Triple<Double, Double, Double>> = emptyList(),
    highlighted: List<Triple<Double, Double, Double>> = emptyList(),
    onMove: (Double, Double) -> Unit,
) {
    val ctx = LocalContext.current
    val move by rememberUpdatedState(onMove)
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(lat, lng))
            // Przewijane okno nie przejmuje gestów mapy.
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { move(it.latitude, it.longitude) }
                return true
            }
            override fun longPressHelper(p: GeoPoint?) = false
        }))
        for ((la, ln, r) in others) {
            m.overlays.add(Polygon(m).apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(la, ln), r))
                fillPaint.color = 0x228E8E93
                outlinePaint.color = 0xFF8E8E93.toInt()
                outlinePaint.strokeWidth = 2f
            })
        }
        for ((la, ln, r) in highlighted) {
            m.overlays.add(Polygon(m).apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(la, ln), r))
                fillPaint.color = 0x33FF9F0A
                outlinePaint.color = 0xFFFF9F0A.toInt()
                outlinePaint.strokeWidth = 3f
            })
        }
        m.overlays.add(Polygon(m).apply {
            setPoints(Polygon.pointsAsCircle(GeoPoint(lat, lng), radius))
            fillPaint.color = 0x330A84FF
            outlinePaint.color = 0xFF0A84FF.toInt()
            outlinePaint.strokeWidth = 4f
        })
        m.overlays.add(Marker(m).apply {
            position = GeoPoint(lat, lng)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            setInfoWindow(null)
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) = Unit
                override fun onMarkerDragStart(marker: Marker) = Unit
                override fun onMarkerDragEnd(marker: Marker) = move(marker.position.latitude, marker.position.longitude)
            })
        })
        m.invalidate()
    })
}

/** Podpowiedzi nazw z OpenStreetMap jako przyciski. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoiChips(pois: List<Poi>?, onPick: (Poi) -> Unit) {
    when {
        pois == null -> Text(
            "Szukam miejsc w pobliżu…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pois.isEmpty() -> {}
        else -> {
            Text("W pobliżu (OpenStreetMap)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pois.take(6).forEach { p ->
                    AssistChip(
                        onClick = { onPick(p) },
                        label = {
                            Text(
                                if (p.name.isBlank()) p.label
                                else "${p.name} · ${p.type}" + if (!p.inside && p.distanceM >= 15) " · ${p.distanceM.roundToInt()} m" else "",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceEditorDialog(draft: PlaceDraft, categories: List<Category>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = LifeApp.instance.repo
    val scope = rememberCoroutineScope()
    val existing = draft.place
    var name by remember { mutableStateOf(existing?.name ?: draft.suggestedName ?: "") }
    var catId by remember {
        mutableStateOf(existing?.categoryId ?: draft.suggestedCat ?: categories.firstOrNull { it.kind == Kind.HOME }?.id)
    }
    var lat by remember { mutableDoubleStateOf(draft.lat) }
    var lng by remember { mutableDoubleStateOf(draft.lng) }
    var radius by remember { mutableFloatStateOf((existing?.radius ?: draft.suggestedRadius ?: 100.0).toFloat().coerceIn(30f, 1000f)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && catId != null

    val places by repo.places.collectAsState(initial = emptyList())
    val unknown by repo.unknownStays.collectAsState(initial = emptyList())
    val clusters = remember(unknown) { Clusters.build(unknown, System.currentTimeMillis()) }
    // Sąsiedzi do połączenia (do 800 m od pierwotnego punktu).
    val nearPlaces = remember(places) {
        places.filter { it.id != existing?.id }
            .map { it to Geo.distance(draft.lat, draft.lng, it.lat, it.lng) }
            .filter { it.second <= 800 }.sortedBy { it.second }
    }
    val nearUnknown = remember(clusters) {
        clusters.map { it to Geo.distance(draft.lat, draft.lng, it.lat, it.lng) }
            .filter { it.second in 30.0..800.0 }.sortedBy { it.second }
    }
    var mergePlaces by remember { mutableStateOf(setOf<Long>()) }
    var mergeUnknown by remember {
        mutableStateOf(
            nearUnknown.map { it.first }.filter { c -> draft.mergeUnknown.any { Geo.distance(it.first, it.second, c.lat, c.lng) < 40 } }.toSet()
        )
    }
    val pois by produceState<List<Poi>?>(null, (lat * 3000).roundToInt(), (lng * 3000).roundToInt()) {
        value = null
        value = PoiHints.near(ctx, lat, lng) ?: emptyList()
    }

    fun cover(la: Double, ln: Double, r: Double) {
        val need = (Geo.distance(lat, lng, la, ln) + r).toFloat()
        if (need > radius) radius = need.coerceAtMost(1000f)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(
                    if (existing == null) "Nowe miejsce" else "Edytuj miejsce",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nazwa") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                PoiChips(pois) { p ->
                    name = p.label
                    PoiHints.suggestCategory(p, categories)?.let { catId = it.id }
                    // Duży teren (cmentarz, park): od razu większy promień.
                    if (p.inside) radius = radius.coerceAtLeast(200f)
                }
                Spacer(Modifier.height(12.dp))
                Text("Co tu zwykle robisz?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                CategoryPicker(categories.filter { it.kind != Kind.UNKNOWN }, catId) { catId = it }

                Spacer(Modifier.height(16.dp))
                Text("Położenie", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Przeciągnij pinezkę albo dotknij mapy, by poprawić środek miejsca.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                PinPicker(
                    lat, lng, radius.toDouble(),
                    Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)),
                    others = nearPlaces.filter { it.first.id !in mergePlaces }.map { Triple(it.first.lat, it.first.lng, it.first.radius) } +
                        nearUnknown.filter { it.first !in mergeUnknown }.map { Triple(it.first.lat, it.first.lng, 40.0) },
                    highlighted = nearPlaces.filter { it.first.id in mergePlaces }.map { Triple(it.first.lat, it.first.lng, it.first.radius) } +
                        mergeUnknown.map { Triple(it.lat, it.lng, 40.0) },
                ) { la, ln -> lat = la; lng = ln }
                if (Geo.distance(lat, lng, draft.lat, draft.lng) >= 5) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Przesunięto o ${Geo.distance(lat, lng, draft.lat, draft.lng).roundToInt()} m",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { lat = draft.lat; lng = draft.lng }) { Text("Cofnij") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Promień: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 30f..1000f)

                if (nearPlaces.isNotEmpty() || nearUnknown.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Połącz z miejscami obok", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Np. duży cmentarz albo zakład wykryty jako kilka miejsc — zaznaczone staną się jednym.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    nearPlaces.forEach { (p, d) ->
                        MergeRow(p.name, "${d.roundToInt()} m", p.id in mergePlaces) { on ->
                            mergePlaces = if (on) mergePlaces + p.id else mergePlaces - p.id
                            if (on) cover(p.lat, p.lng, p.radius)
                        }
                    }
                    nearUnknown.forEachIndexed { i, (c, d) ->
                        MergeRow(
                            "Nieznane miejsce ${i + 1}",
                            "${c.visits} ${if (c.visits == 1) "wizyta" else "wizyt"} · ${d.roundToInt()} m · ${Fmt.duration(c.totalMs)}",
                            c in mergeUnknown,
                        ) { on ->
                            mergeUnknown = if (on) mergeUnknown + c else mergeUnknown - c
                            if (on) cover(c.lat, c.lng, 50.0)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (existing != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Usuń", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Anuluj") }
                    Button(enabled = valid, onClick = {
                        val cid = catId ?: return@Button
                        scope.launch {
                            val base = Place(
                                id = existing?.id ?: 0L,
                                name = name.trim(),
                                lat = lat,
                                lng = lng,
                                radius = radius.toDouble(),
                                categoryId = cid,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            )
                            val saved = base.copy(id = repo.savePlace(base))
                            nearPlaces.filter { it.first.id in mergePlaces }.forEach { repo.mergePlace(it.first, saved) }
                            mergeUnknown.forEach { repo.attachUnknownNear(it.lat, it.lng, 160.0, saved) }
                            onDismiss()
                        }
                    }) { Text("Zapisz") }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć miejsce?") },
            text = { Text("Pobyty w tym miejscu staną się „nieznane”. Historia czasu zostanie zachowana.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deletePlace(existing)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun MergeRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
