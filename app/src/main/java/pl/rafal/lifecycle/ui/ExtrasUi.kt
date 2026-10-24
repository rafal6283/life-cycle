package pl.rafal.lifecycle.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Goal
import pl.rafal.lifecycle.data.GoalPeriod
import pl.rafal.lifecycle.data.GoalType
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.LocationSample
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.domain.CommuteStats
import pl.rafal.lifecycle.domain.GoalProgress
import pl.rafal.lifecycle.domain.Goals
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

// ---------------------------------------------------------------------------
// „Teraz robię…”
// ---------------------------------------------------------------------------

@Composable
fun QuickSwitchDialog(onDismiss: () -> Unit) {
    val repo = LifeApp.instance.repo
    val scope = rememberCoroutineScope()
    val categories by repo.categories.collectAsState(initial = emptyList())
    val current by repo.override.collectAsState(initial = null)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Teraz robię…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Nadpisuje automat do czasu, aż zmienisz miejsce albo zakończysz ręcznie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                CategoryPicker(categories.filter { it.kind != Kind.UNKNOWN }, current?.categoryId) { id ->
                    scope.launch {
                        repo.startOverride(id)
                        onDismiss()
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) {
                        TextButton(onClick = {
                            scope.launch {
                                repo.stopOverride()
                                onDismiss()
                            }
                        }) { Text("Zakończ bieżące", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Zamknij") }
                }
            }
        }
    }
}

@Composable
fun OverrideCard(entry: Entry?, cat: Category?, now: Long, onSwitch: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (entry != null) catColor(cat).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onSwitch)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry != null) {
            CategoryBadge(cat, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Teraz: ${cat?.name ?: "—"}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "od ${Fmt.time(entry.startAt)} · ${Fmt.duration(now - entry.startAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onStop) { Text("Zakończ") }
        } else {
            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                "Teraz robię…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "nadpisz automat",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Cele
// ---------------------------------------------------------------------------

@Composable
fun GoalRow(p: GoalProgress, cat: Category?) {
    val color = when {
        p.exceeded -> Color(0xFFFF3B30)
        p.met -> Color(0xFF34C759)
        else -> catColor(cat)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(cat, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cat?.name ?: "—", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    when {
                        p.exceeded -> "przekroczono"
                        p.met && p.goal.type != GoalType.MAX -> "✓ osiągnięty"
                        else -> Goals.progressText(p)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
            Text(
                Goals.describe(p.goal) + if (p.met || p.exceeded) " · " + Goals.progressText(p) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            ShareBar(p.fraction.coerceAtMost(1f), color)
        }
    }
}

@Composable
fun GoalsScreen(onBack: () -> Unit) {
    val repo = LifeApp.instance.repo
    val goals by repo.goals.collectAsState(initial = emptyList())
    val categories by repo.categories.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var adding by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val (from, to) = remember(today) { Goals.weekRange(today) }
    val entries by remember(from, to) { repo.entriesIn(from, to) }.collectAsState(initial = emptyList())
    val now by rememberNow()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Cele", onBack = onBack) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Dodaj cel") }
        }
        if (goals.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ustaw cele, np. „co najmniej 7 h snu dziennie”, „najwyżej 45 h pracy w tygodniu” albo „3× siłownia w tygodniu”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { adding = true }) { Text("Dodaj pierwszy cel") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(goals, key = { it.id }) { g ->
                Box(Modifier.clickable { editing = g }) {
                    GoalRow(Goals.progress(g, entries, today, now), catMap[g.categoryId])
                }
            }
        }
    }

    editing?.let { GoalEditorDialog(it, categories) { editing = null } }
    if (adding) GoalEditorDialog(null, categories) { adding = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditorDialog(goal: Goal?, categories: List<Category>, onDismiss: () -> Unit) {
    val repo = LifeApp.instance.repo
    val scope = rememberCoroutineScope()
    var catId by remember { mutableStateOf(goal?.categoryId ?: categories.firstOrNull { it.kind == Kind.SLEEP }?.id) }
    var type by remember { mutableStateOf(goal?.type ?: GoalType.MIN) }
    var period by remember { mutableStateOf(goal?.period ?: GoalPeriod.DAY) }
    var minutes by remember { mutableFloatStateOf((goal?.minutes ?: 420).toFloat()) }
    var count by remember { mutableFloatStateOf((goal?.count ?: 3).toFloat()) }
    var notify by remember { mutableStateOf(goal?.notify ?: true) }
    var confirmDelete by remember { mutableStateOf(false) }

    val maxMinutes = when {
        type == GoalType.COUNT -> 240f
        period == GoalPeriod.DAY -> 24 * 60f
        else -> 80 * 60f
    }
    val step = if (type == GoalType.COUNT) 5 else if (period == GoalPeriod.DAY) 15 else 60
    val m = minutes.coerceIn(step.toFloat(), maxMinutes)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(
                    if (goal == null) "Nowy cel" else "Edytuj cel",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text("Kategoria", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                CategoryPicker(categories.filter { it.kind != Kind.UNKNOWN }, catId) { catId = it }
                Spacer(Modifier.height(16.dp))
                Text("Rodzaj", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == GoalType.MIN, { type = GoalType.MIN }, { Text("Co najmniej") })
                    FilterChip(type == GoalType.MAX, { type = GoalType.MAX }, { Text("Najwyżej") })
                    FilterChip(type == GoalType.COUNT, { type = GoalType.COUNT; period = GoalPeriod.WEEK; minutes = 30f }, { Text("Ile razy") })
                }
                if (type != GoalType.COUNT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(period == GoalPeriod.DAY, { period = GoalPeriod.DAY }, { Text("Dziennie") })
                        FilterChip(period == GoalPeriod.WEEK, { period = GoalPeriod.WEEK }, { Text("Tygodniowo") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (type == GoalType.COUNT) {
                    Text("${count.toInt()} dni w tygodniu", style = MaterialTheme.typography.titleMedium)
                    Slider(count, { count = it }, valueRange = 1f..7f, steps = 5)
                    Text("Liczy się dzień z co najmniej ${m.toInt()} min", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(Fmt.duration(m.toLong() * 60_000L), style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = m,
                    onValueChange = { minutes = (Math.round(it / step) * step).toFloat() },
                    valueRange = step.toFloat()..maxMinutes,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Powiadomienia", Modifier.weight(1f))
                    Switch(notify, { notify = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (goal != null) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Anuluj") }
                    Button(enabled = catId != null, onClick = {
                        val cid = catId ?: return@Button
                        scope.launch {
                            repo.saveGoal(
                                Goal(
                                    id = goal?.id ?: 0L, categoryId = cid, type = type,
                                    period = if (type == GoalType.COUNT) GoalPeriod.WEEK else period,
                                    minutes = m.toInt(), count = count.toInt(), notify = notify,
                                )
                            )
                            onDismiss()
                        }
                    }) { Text("Zapisz") }
                }
            }
        }
    }

    if (confirmDelete && goal != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć cel?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteGoal(goal)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Mapa dnia (trasy)
// ---------------------------------------------------------------------------

@Composable
fun DayMapDialog(day: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LifeApp.instance
    val from = Fmt.dayStart(day)
    val to = Fmt.dayStart(day.plusDays(1))
    val data by produceState<Triple<List<Entry>, List<LocationSample>, Map<Long, Category>>?>(null, from) {
        value = Triple(
            app.db.entries().getRange(from, to),
            app.db.locations().range(from, to),
            app.db.categories().getAll().associateBy { it.id },
        )
    }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(52.2297, 21.0122))
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    val unknownColor = UnknownGray.toArgb()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Zamknij") }
                    Text(
                        "Trasy · ${Fmt.dayTitle(day)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val d = data
                val now = System.currentTimeMillis()
                val segments = remember(d) { d?.let { Timeline.resolve(it.first, from, to, now) } ?: emptyList() }
                val moves = segments.filter { it.entry.source == Source.AUTO_MOVE || it.entry.distanceM != null }
                AndroidView(
                    factory = { map },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    update = { m ->
                        if (d == null) return@AndroidView
                        m.overlays.clear()
                        val all = mutableListOf<GeoPoint>()
                        for (s in segments) {
                            val color = d.third[s.entry.categoryId]?.color ?: unknownColor
                            if (s.entry.source == Source.AUTO_MOVE) {
                                val pts = d.second.filter { it.time in s.start..s.end && it.accuracy <= 100f }
                                    .map { GeoPoint(it.lat, it.lng) }
                                if (pts.size >= 2) {
                                    m.overlays.add(Polyline(m).apply {
                                        setPoints(pts)
                                        outlinePaint.color = color
                                        outlinePaint.strokeWidth = 10f
                                    })
                                    all += pts
                                }
                            } else {
                                val lat = s.entry.lat
                                val lng = s.entry.lng
                                if (lat != null && lng != null) {
                                    val gp = GeoPoint(lat, lng)
                                    m.overlays.add(Marker(m).apply {
                                        position = gp
                                        icon = android.graphics.drawable.GradientDrawable().apply {
                                            shape = android.graphics.drawable.GradientDrawable.OVAL
                                            setColor(color)
                                            val px = (18 * ctx.resources.displayMetrics.density).toInt()
                                            setStroke(px / 6, android.graphics.Color.WHITE)
                                            setSize(px, px)
                                        }
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        title = (d.third[s.entry.categoryId]?.name ?: "") + " · " + Fmt.time(s.start)
                                    })
                                    all += gp
                                }
                            }
                        }
                        if (all.size == 1) {
                            m.controller.setCenter(all.first())
                        } else if (all.size > 1) {
                            m.post {
                                try {
                                    m.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.3f), false)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        m.invalidate()
                    },
                )
                if (d != null && moves.isEmpty()) {
                    Text(
                        "Brak przejazdów tego dnia.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
                if (moves.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        moves.forEach { s ->
                            val cat = d?.third?.get(s.entry.categoryId)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryBadge(cat, 28.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${Fmt.time(s.start)}–${Fmt.time(s.end)} · ${cat?.name ?: ""}",
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    listOfNotNull(Fmt.duration(s.duration), s.entry.distanceM?.let { Fmt.distance(it) })
                                        .joinToString(" · "),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Statystyki: dojazdy i ciekawostki
// ---------------------------------------------------------------------------

@Composable
fun CommuteCard(c: CommuteStats) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp)
    ) {
        fun line(n: Int, avg: Long?, dist: Double?) =
            if (avg == null) "brak danych"
            else Fmt.durationShort(avg) + (dist?.let { " · " + Fmt.distance(it) } ?: "") + " · $n×"
        CommuteLine("Dom → Praca", line(c.toWorkN, c.toWorkAvg, c.toWorkDist))
        CommuteLine("Praca → Dom", line(c.toHomeN, c.toHomeAvg, c.toHomeDist))
    }
}

@Composable
private fun CommuteLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InsightsCard(lines: List<String>) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp)
    ) {
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(
                    Icons.Filled.Lightbulb, null,
                    tint = Color(0xFFFFCC00),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(l, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun SmallOutlinedButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(text) }
}

// ---------------------------------------------------------------------------
// Kroki
// ---------------------------------------------------------------------------

val StepsGreen = Color(0xFF34C759)

@Composable
fun StepsCard(steps: Long, hours: List<Long>, goal: Int, onClick: () -> Unit) {
    val frac = if (goal > 0) steps.toFloat() / goal else 0f
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(StepsGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.DirectionsWalk, null,
                    tint = Color.White, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${Fmt.steps(steps)} kroków",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (goal > 0) "cel ${Fmt.steps(goal.toLong())} · ${(frac * 100).toInt()}%" + (if (steps >= goal) " ✓" else "")
                    else "bez celu",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (goal in 1..steps) StepsGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (goal > 0) {
            Spacer(Modifier.height(8.dp))
            ShareBar(frac.coerceAtMost(1f), StepsGreen)
        }
        if (hours.any { it > 0 }) {
            Spacer(Modifier.height(10.dp))
            val track = MaterialTheme.colorScheme.surfaceVariant
            val grow = rememberGrow(hours.sum() > 0)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val max = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L)
                val slot = size.width / 24
                val bw = slot * 0.6f
                hours.forEachIndexed { i, v ->
                    val x = i * slot + (slot - bw) / 2
                    drawRect(track, androidx.compose.ui.geometry.Offset(x, size.height - 2f), androidx.compose.ui.geometry.Size(bw, 2f))
                    if (v > 0) {
                        val h = (size.height * v / max).coerceAtLeast(3f) * stagger(grow, i, 24)
                        drawRect(StepsGreen, androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.geometry.Size(bw, h))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("0", "6", "12", "18", "24").forEachIndexed { i, l ->
                    Text(
                        l,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = when (i) {
                            0 -> androidx.compose.ui.text.style.TextAlign.Start
                            4 -> androidx.compose.ui.text.style.TextAlign.End
                            else -> androidx.compose.ui.text.style.TextAlign.Center
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun StepGoalDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { pl.rafal.lifecycle.util.Prefs(ctx) }
    var goal by remember { mutableFloatStateOf(prefs.stepGoal.toFloat()) }
    var notify by remember { mutableStateOf(prefs.stepGoalNotify) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dzienny cel kroków") },
        text = {
            Column {
                Text(Fmt.steps(goal.toLong()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Slider(
                    value = goal,
                    onValueChange = { goal = (Math.round(it / 500f) * 500).toFloat() },
                    valueRange = 2000f..30000f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Powiadom po osiągnięciu", Modifier.weight(1f))
                    Switch(notify, { notify = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.stepGoal = goal.toInt()
                prefs.stepGoalNotify = notify
                onDismiss()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
fun StepsStatsCard(avg: Long, prevAvg: Long, total: Long, bars: List<Long>, labels: List<String>, avgLabel: String) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.steps(avg), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(avgLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (prevAvg > 0) {
                    val diff = avg - prevAvg
                    Text(
                        (if (diff >= 0) "▲ " else "▼ ") + Fmt.steps(kotlin.math.abs(diff)) +
                            " (${if (diff >= 0) "+" else ""}${diff * 100 / prevAvg}%)",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (diff >= 0) StepsGreen else Color(0xFFFF3B30),
                    )
                }
                Text(
                    "razem ${Fmt.steps(total)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        StepBars(bars, labels)
    }
}

/** Słupki kroków: rosną kaskadą, linia celu rysuje się od lewej, dotyk pokazuje liczbę kroków. */
@Composable
private fun StepBars(bars: List<Long>, labels: List<String>) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ctx = LocalContext.current
    val goal = remember { pl.rafal.lifecycle.util.Prefs(ctx).stepGoal }
    val grow = rememberGrow(bars)
    val sel = rememberSelection(bars)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .chartTouch(sel, bars.size) { o, w -> if (bars.isEmpty()) -1 else (o.x / (w / bars.size)).toInt().coerceIn(0, bars.size - 1) }
    ) {
        val labelH = 20.dp.toPx()
        val chartH = size.height - labelH
        val max = maxOf(bars.maxOrNull() ?: 0L, goal.toLong(), 1L)
        val n = bars.size.coerceAtLeast(1)
        val slot = size.width / n
        val bw = slot * 0.62f
        val goalY = chartH - chartH * goal / max
        if (goal > 0) {
            drawLine(
                StepsGreen.copy(alpha = 0.5f),
                androidx.compose.ui.geometry.Offset(0f, goalY),
                androidx.compose.ui.geometry.Offset(size.width * grow, goalY),
                strokeWidth = 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, chartH), androidx.compose.ui.geometry.Offset(size.width, chartH), 1f)
        bars.forEachIndexed { i, v ->
            val h = chartH * v / max * stagger(grow, i, n)
            val x = i * slot + (slot - bw) / 2
            if (h > 0f) {
                val base = if (goal in 1..v) 1f else 0.55f
                drawRoundRect(
                    StepsGreen.copy(alpha = barAlpha(i, sel, base)),
                    androidx.compose.ui.geometry.Offset(x, chartH - h),
                    androidx.compose.ui.geometry.Size(bw, h),
                    androidx.compose.ui.geometry.CornerRadius(minOf(bw / 2, 3.dp.toPx())),
                )
            }
            if ((n <= 12 || i % 5 == 0) && i < labels.size) {
                val t = measurer.measure(labels[i], style)
                drawText(
                    t,
                    topLeft = androidx.compose.ui.geometry.Offset(i * slot + slot / 2 - t.size.width / 2f, chartH + 4.dp.toPx()),
                )
            }
        }
        val s = sel.last
        if (s in bars.indices) {
            val v = bars[s]
            val name = labels.getOrNull(s)?.let { "$it · " } ?: ""
            tooltip(
                measurer, name + Fmt.steps(v) + " kroków" + (if (goal in 1..v) " ✓" else ""),
                s * slot + slot / 2, chartH - chartH * v / max, tipStyle, tipBg, sel.alpha.value, grid, chartH,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Diagnostyka Health Connect
// ---------------------------------------------------------------------------

private fun appLabel(ctx: android.content.Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

@Composable
fun HealthConnectCheckDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val state by produceState<Pair<String, List<String>>?>(null, refresh) {
        val hc = pl.rafal.lifecycle.tracking.HealthConnectHelper
        value = when {
            !hc.available(ctx) -> "Health Connect nie jest dostępny na tym telefonie." to emptyList()
            else -> {
                val g = hc.grantedSet(ctx)
                val lines = mutableListOf<String>()
                fun p(name: String, perm: String) = lines.add((if (perm in g) "✓ " else "✗ ") + name)
                hc.SLEEP_PERMISSIONS.forEach { p("Sen", it) }
                p("Treningi", androidx.health.connect.client.permission.HealthPermission.getReadPermission(
                    androidx.health.connect.client.records.ExerciseSessionRecord::class))
                p("Kroki", pl.rafal.lifecycle.tracking.StepsImporter.PERMISSION)
                p("Odczyt w tle", hc.BACKGROUND_READ)
                val now = System.currentTimeMillis()
                val list = hc.readWorkouts(ctx, now - 14L * 24 * 3_600_000, now)
                val header = lines.joinToString("\n")
                val items = when {
                    list == null -> listOf("Nie udało się odczytać treningów (brak zgody?).")
                    list.isEmpty() -> listOf(
                        "Brak treningów w Health Connect z ostatnich 14 dni. " +
                            "Aplikacja sportowa nie zapisuje ich do Health Connect — sprawdź jej ustawienia integracji."
                    )
                    else -> list.sortedByDescending { it.start }.map { w ->
                        "${Fmt.dateTime(w.start)} · ${Fmt.duration(w.end - w.start)} · " +
                            (w.title?.takeIf { it.isNotBlank() } ?: "trening") + "\nz: ${appLabel(ctx, w.app)}"
                    }
                }
                header to items
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Health Connect") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val s = state
                if (s == null) {
                    Text("Sprawdzam…")
                } else {
                    Text(s.first, style = MaterialTheme.typography.bodyMedium)
                    if (s.second.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Treningi (14 dni):", fontWeight = FontWeight.SemiBold)
                        s.second.forEach {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            pl.rafal.lifecycle.tracking.WorkoutImporter.run(ctx)
                            pl.rafal.lifecycle.tracking.StepsImporter.run(ctx)
                        } catch (_: Exception) {
                        }
                    }
                    refresh++
                    android.widget.Toast.makeText(ctx, "Zaimportowano", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text("Importuj teraz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}
