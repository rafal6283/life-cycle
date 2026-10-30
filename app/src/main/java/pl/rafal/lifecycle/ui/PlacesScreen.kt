package pl.rafal.lifecycle.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import pl.rafal.lifecycle.domain.FieldWork
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import pl.rafal.lifecycle.domain.Cluster
import pl.rafal.lifecycle.domain.Clusters
import pl.rafal.lifecycle.util.Fmt

data class PlaceDraft(val place: Place?, val lat: Double, val lng: Double, val suggestedName: String? = null)

/** Polecenie przesunięcia mapy; [n] rośnie, by to samo miejsce dało się pokazać ponownie. */
data class MapFocus(val point: GeoPoint, val n: Int)

private fun clusterKey(c: Cluster) = "u:${c.lat},${c.lng}"
private fun placeKey(p: Place) = "p:${p.id}"

private val SelectColor = 0xFFFF9F0A.toInt()

@Composable
fun PlacesScreen(onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = LifeApp.instance.repo
    val categories by repo.categories.collectAsState(initial = emptyList())
    val places by repo.places.collectAsState(initial = emptyList())
    val unknown by repo.unknownStays.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val clusters = remember(unknown) { Clusters.build(unknown, System.currentTimeMillis()) }
    val lastFix by produceState<GeoPoint?>(null) {
        value = LifeApp.instance.db.locations().last()?.let { GeoPoint(it.lat, it.lng) }
    }
    val center = lastFix ?: places.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
    var editing by remember { mutableStateOf<PlaceDraft?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun show(lat: Double, lng: Double) { focus = MapFocus(GeoPoint(lat, lng), (focus?.n ?: 0) + 1) }
    fun clusterIndex(i: Int) = 1 + i
    fun placeIndex(j: Int) = (if (clusters.isNotEmpty()) clusters.size + 2 else 1) + j

    fun staysOf(c: Cluster) = unknown.filter { e ->
        val lat = e.lat ?: return@filter false
        val lng = e.lng ?: return@filter false
        pl.rafal.lifecycle.util.Geo.distance(lat, lng, c.lat, c.lng) < 150
    }

    suspend fun nameDraft(c: Cluster): PlaceDraft {
        val hint = pl.rafal.lifecycle.domain.CalendarHints.bestFor(ctx, staysOf(c))
        val addr = FieldWork.address(ctx, c.lat, c.lng)
        return PlaceDraft(null, c.lat, c.lng, hint?.title ?: addr?.substringBefore(','))
    }

    val selCluster = clusters.indexOfFirst { clusterKey(it) == selected }
    val selPlace = places.firstOrNull { placeKey(it) == selected }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Miejsca") {
            IconButton(onClick = { onOpen("trips") }) { Icon(Icons.Filled.Luggage, "Wyjazdy") }
            IconButton(onClick = { onOpen("heatmap") }) { Icon(Icons.Filled.Whatshot, "Gdzie bywasz") }
        }
        PlacesMap(
            places = places, clusters = clusters, catMap = catMap, center = center,
            selected = selected, focus = focus,
            onLongPress = { editing = PlaceDraft(null, it.latitude, it.longitude) },
            onPlaceClick = { pl ->
                if (selected == placeKey(pl)) editing = PlaceDraft(pl, pl.lat, pl.lng)
                else {
                    selected = placeKey(pl)
                    val j = places.indexOf(pl)
                    if (j >= 0) scope.launch { listState.animateScrollToItem(placeIndex(j)) }
                }
            },
            onClusterClick = { c ->
                if (selected == clusterKey(c)) scope.launch { editing = nameDraft(c) }
                else {
                    selected = clusterKey(c)
                    val i = clusters.indexOf(c)
                    if (i >= 0) scope.launch { listState.animateScrollToItem(clusterIndex(i)) }
                }
            },
            onEmptyTap = { selected = null },
            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
        Text(
            when {
                selCluster >= 0 -> "Nieznane miejsce ${selCluster + 1} · dotknij znacznika jeszcze raz albo „Nazwij”, aby je zapisać."
                selPlace != null -> "${selPlace.name} · dotknij jeszcze raz, aby edytować."
                else -> "Dotknij miejsca na liście, aby zobaczyć je na mapie, albo znacznika — by znaleźć je na liście. Przytrzymaj palec na mapie, aby dodać miejsce."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (clusters.isNotEmpty()) {
                item(key = "h-unknown") { SectionTitle("Nieznane miejsca · nazwij je") }
                itemsIndexed(clusters, key = { _, c -> clusterKey(c) }) { i, c ->
                    UnknownRow(
                        c, i + 1, selected == clusterKey(c), staysOf(c),
                        onClick = { selected = clusterKey(c); show(c.lat, c.lng) },
                        onName = { scope.launch { editing = nameDraft(c) } },
                    )
                }
            }
            item(key = "h-places") { SectionTitle("Twoje miejsca") }
            if (places.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Brak zapisanych miejsc. Gdy gdzieś pobędziesz dłużej, pojawi się tu jako nieznane miejsce do nazwania.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(places, key = { it.id }) { p ->
                PlaceRow(
                    p, catMap[p.categoryId], selected == placeKey(p),
                    onClick = { selected = placeKey(p); show(p.lat, p.lng) },
                    onEdit = { editing = PlaceDraft(p, p.lat, p.lng) },
                )
            }
        }
    }

    editing?.let { d ->
        PlaceEditorDialog(d, categories, onDismiss = { editing = null; selected = null })
    }
}

@Composable
private fun NumberBadge(n: Int, color: Color, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text("$n", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun UnknownRow(c: Cluster, number: Int, selected: Boolean, stays: List<pl.rafal.lifecycle.data.Entry>, onClick: () -> Unit, onName: () -> Unit) {
    val ctx = LocalContext.current
    val address by produceState<String?>(null, c.lat, c.lng) { value = FieldWork.address(ctx, c.lat, c.lng) }
    val hint by produceState<pl.rafal.lifecycle.domain.CalendarHint?>(null, c.lat, c.lng, stays.size) {
        value = pl.rafal.lifecycle.domain.CalendarHints.bestFor(ctx, stays)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(SelectColor).copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberBadge(number, if (selected) Color(SelectColor) else UnknownGray)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                address ?: "Nieznane miejsce",
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${c.visits} ${if (c.visits == 1) "wizyta" else "wizyt"} · ${Fmt.duration(c.totalMs)} · ostatnio ${Fmt.dateTime(c.lastAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Text(
                    "📅 ${it.title}" + (it.location?.let { l -> " · $l" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FilledTonalButton(onClick = onName) { Text("Nazwij") }
    }
}

@Composable
private fun PlaceRow(p: Place, cat: Category?, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(SelectColor).copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(cat, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${cat?.name ?: "—"} · promień ${p.radius.toInt()} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edytuj") }
    }
}

private fun dot(ctx: Context, color: Int, sizeDp: Int = 20, stroke: Int = android.graphics.Color.WHITE): Drawable {
    val d = ctx.resources.displayMetrics.density
    val px = (sizeDp * d).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((3 * d).toInt(), stroke)
        setSize(px, px)
    }
}

/** Kółko z numerem — ten sam numer co na liście nieznanych miejsc. */
private fun numberDot(ctx: Context, n: Int, color: Int, sizeDp: Int): Drawable {
    val d = ctx.resources.displayMetrics.density
    val px = (sizeDp * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    cv.drawCircle(px / 2f, px / 2f, px / 2f, paint)
    paint.color = color
    cv.drawCircle(px / 2f, px / 2f, px / 2f - 2.5f * d, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.isFakeBoldText = true
    paint.textSize = px * (if (n >= 10) 0.42f else 0.5f)
    val y = px / 2f - (paint.descent() + paint.ascent()) / 2f
    cv.drawText("$n", px / 2f, y, paint)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}

@Composable
fun PlacesMap(
    places: List<Place>,
    clusters: List<Cluster>,
    catMap: Map<Long, Category>,
    center: GeoPoint?,
    onLongPress: (GeoPoint) -> Unit,
    onPlaceClick: (Place) -> Unit,
    onClusterClick: (Cluster) -> Unit,
    modifier: Modifier = Modifier,
    selected: String? = null,
    focus: MapFocus? = null,
    onEmptyTap: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(52.2297, 21.0122))
        }
    }
    var centered by remember { mutableStateOf(false) }

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

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        centered = true
        map.controller.animateTo(f.point, maxOf(map.zoomLevelDouble, 17.0), 600L)
    }

    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                onEmptyTap()
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let(onLongPress)
                return true
            }
        }))
        places.forEach { pl ->
            val color = catMap[pl.categoryId]?.color ?: 0xFFA0A0A8.toInt()
            val sel = selected == placeKey(pl)
            val circle = Polygon(m).apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(pl.lat, pl.lng), pl.radius))
                fillPaint.color = (color and 0x00FFFFFF) or (if (sel) 0x55000000 else 0x33000000)
                outlinePaint.color = if (sel) SelectColor else color
                outlinePaint.strokeWidth = if (sel) 7f else 3f
                setOnClickListener { _, _, _ -> onPlaceClick(pl); true }
            }
            m.overlays.add(circle)
            val marker = Marker(m).apply {
                position = GeoPoint(pl.lat, pl.lng)
                icon = if (sel) dot(ctx, color, 28, SelectColor) else dot(ctx, color)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> onPlaceClick(pl); true }
            }
            m.overlays.add(marker)
        }
        var selMarker: Marker? = null
        clusters.forEachIndexed { i, c ->
            val sel = selected == clusterKey(c)
            val marker = Marker(m).apply {
                position = GeoPoint(c.lat, c.lng)
                icon = numberDot(ctx, i + 1, if (sel) SelectColor else 0xFF8E8E93.toInt(), if (sel) 34 else 24)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> onClusterClick(c); true }
            }
            if (sel) selMarker = marker else m.overlays.add(marker)
        }
        selMarker?.let { m.overlays.add(it) } // wybrany na wierzchu
        if (!centered && center != null) {
            m.controller.setCenter(center)
            centered = true
        }
        m.invalidate()
    })
}

@Composable
fun PlaceEditorDialog(draft: PlaceDraft, categories: List<Category>, onDismiss: () -> Unit) {
    val repo = LifeApp.instance.repo
    val scope = rememberCoroutineScope()
    val existing = draft.place
    var name by remember { mutableStateOf(existing?.name ?: draft.suggestedName ?: "") }
    var catId by remember {
        mutableStateOf(existing?.categoryId ?: categories.firstOrNull { it.kind == Kind.HOME }?.id)
    }
    var radius by remember { mutableFloatStateOf((existing?.radius ?: 100.0).toFloat()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && catId != null

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
                Spacer(Modifier.height(16.dp))
                Text("Co tu zwykle robisz?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                CategoryPicker(categories.filter { it.kind != Kind.UNKNOWN }, catId) { catId = it }
                Spacer(Modifier.height(16.dp))
                Text("Promień: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..500f, steps = 8)
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
                            repo.savePlace(
                                Place(
                                    id = existing?.id ?: 0L,
                                    name = name.trim(),
                                    lat = draft.lat,
                                    lng = draft.lng,
                                    radius = radius.toDouble(),
                                    categoryId = cid,
                                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                                )
                            )
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
