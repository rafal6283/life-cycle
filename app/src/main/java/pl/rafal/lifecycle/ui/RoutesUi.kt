package pl.rafal.lifecycle.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.domain.RouteInfo
import pl.rafal.lifecycle.domain.RoutePoint
import pl.rafal.lifecycle.domain.Routes
import pl.rafal.lifecycle.util.Fmt
import java.time.format.DateTimeFormatter

private val dayF = DateTimeFormatter.ofPattern("EEE d MMM", Fmt.PL)

private fun pace(sec: Double): String = "%d:%02d min/km".format((sec / 60).toInt(), (sec % 60).toInt())

/** Kolor odcinka wg prędkości względem całej trasy: zielony = szybko, czerwony = wolno / korek. */
private fun speedColor(v: Double, lo: Double, hi: Double): Int {
    val t = if (hi <= lo) 1.0 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    val r = (255 * (1 - t)).toInt().coerceIn(0, 255)
    val g = (200 * t + 55).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or 60
}

/** Rysuje trasę na mapie: kolory wg prędkości albo jednolity kolor. */
private fun drawRoute(m: org.osmdroid.views.MapView, pts: List<RoutePoint>, colored: Boolean, color: Int = 0xFF0A84FF.toInt(), width: Float = 10f) {
    if (pts.size < 2) return
    if (!colored) {
        m.overlays.add(Polyline(m).apply {
            setPoints(pts.map { GeoPoint(it.lat, it.lng) })
            outlinePaint.color = color; outlinePaint.strokeWidth = width; outlinePaint.strokeCap = Paint.Cap.ROUND
        })
        return
    }
    val sp = Routes.speeds(pts)
    val sorted = sp.sorted()
    val lo = sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.size - 1)]
    val hi = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
    // Odcinki po kilka punktów w jednym kolorze.
    var i = 0
    while (i < pts.size - 1) {
        val j = minOf(pts.size - 1, i + 4)
        val v = sp.subList(i, j).average()
        m.overlays.add(Polyline(m).apply {
            setPoints(pts.subList(i, j + 1).map { GeoPoint(it.lat, it.lng) })
            outlinePaint.color = speedColor(v, lo, hi); outlinePaint.strokeWidth = width; outlinePaint.strokeCap = Paint.Cap.ROUND
        })
        i = j
    }
}

/** Podgląd jednej trasy: mapa, prędkość, porównanie z tą samą trasą w inne dni. */
@Composable
fun RouteScreen(entryId: Long, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = LifeApp.instance
    val categories by app.repo.categories.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }
    data class D(val info: RouteInfo?, val pts: List<RoutePoint>, val same: List<RouteInfo>, val steps: Long = 0)
    val d by produceState<D?>(null, entryId) {
        val e = app.db.entries().byId(entryId) ?: run { value = D(null, emptyList(), emptyList()); return@produceState }
        val now = System.currentTimeMillis()
        val info = Routes.describe(ctx, e.startAt - 1, e.startAt + 1).firstOrNull { it.entry.id == e.id }
            ?: RouteInfo(e, "?", "?", null, null)
        val pts = Routes.points(ctx, e)
        val same = info.pairKey?.let { key ->
            Routes.describe(ctx, now - 365L * 86_400_000L, now).filter { it.pairKey == key && it.entry.id != e.id }
                .sortedByDescending { it.entry.startAt }
        } ?: emptyList()
        // Kroki z kubełków godzinowych — proporcjonalnie do czasu trasy w każdej godzinie.
        val end = e.endAt ?: now
        val steps = app.db.steps().getRange(e.startAt - 3_600_000L, end).groupBy { it.hourStart }.entries.sumOf { (h, l) ->
            val v = l.maxOf { it.steps }
            val ov = (minOf(end, h + 3_600_000L) - maxOf(e.startAt, h)).coerceAtLeast(0L)
            v * ov / 3_600_000L
        }
        value = D(info, pts, same, steps)
    }
    var showOthers by remember { mutableStateOf(false) }
    val others by produceState(emptyList<List<RoutePoint>>(), showOthers, d) {
        val dd = d
        value = if (showOthers && dd != null) dd.same.take(10).map { Routes.points(ctx, it.entry) } else emptyList()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        val x = d
        val info = x?.info
        item { ScreenHeader(if (info != null) "${info.from} → ${info.to}" else "Trasa", onBack = onBack) }
        if (x == null) { item { Text("Wczytuję…", Modifier.padding(20.dp)) }; return@LazyColumn }
        if (info == null) { item { Text("Nie ma już tego wpisu.", Modifier.padding(20.dp)) }; return@LazyColumn }
        val e = info.entry
        val cat = catMap[e.categoryId]
        item {
            Text(
                "${cat?.name ?: "Przejazd"} · ${Fmt.dayTitle(Fmt.toLocalDate(e.startAt))} · ${Fmt.time(e.startAt)}–${e.endAt?.let { Fmt.time(it) } ?: "teraz"}",
                Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            if (x.pts.size < 2) {
                Text(
                    "Brak punktów GPS dla tej trasy (telefon mógł oszczędzać baterię albo trasa była bardzo krótka).",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OsmMap(
                    key = Triple(e.id, others.size, showOthers),
                    modifier = Modifier.padding(16.dp).fillMaxWidth().height(340.dp).clip(RoundedCornerShape(20.dp)),
                ) { m ->
                    others.forEach { drawRoute(m, it, false, 0x668E8E93, 7f) }
                    drawRoute(m, x.pts, true)
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(x.pts.first().lat, x.pts.first().lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Start ${Fmt.time(e.startAt)}"
                    })
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(x.pts.last().lat, x.pts.last().lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Meta ${e.endAt?.let { Fmt.time(it) } ?: ""}"
                    })
                    m.fit(x.pts.map { GeoPoint(it.lat, it.lng) }, 17.0)
                }
            }
        }
        item {
            val st = remember(x) { Routes.stats(e, x.pts, cat?.kind, x.steps) }
            val walkLike = cat?.kind == pl.rafal.lifecycle.data.Kind.WALK || cat?.kind == pl.rafal.lifecycle.data.Kind.RUN
            TrendCard {
                Row {
                    RStat(Fmt.distance(st.distanceM), "dystans", Modifier.weight(1f))
                    RStat(Fmt.durationShort(info.durationMs), "czas", Modifier.weight(1f))
                    RStat(
                        st.paceSecPerKm?.let { pace(it) } ?: "${st.avgKmh.toInt()} km/h",
                        if (st.paceSecPerKm != null) "tempo" else "średnio", Modifier.weight(1f),
                    )
                }
                if (x.pts.size >= 2) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        RStat(Fmt.durationShort(st.movingMs), "w ruchu", Modifier.weight(1f))
                        RStat(
                            if (st.stops == 0) "0" else "${st.stops} · ${Fmt.durationShort(st.stopMs)}",
                            if (walkLike) "przystanki" else "postoje", Modifier.weight(1f),
                        )
                        RStat(
                            if (walkLike && st.movingMs > 0 && st.distanceM > 200) pace(st.movingMs / 1000.0 / (st.distanceM / 1000))
                            else "${st.avgMovingKmh.toInt()} km/h",
                            if (walkLike) "tempo w ruchu" else "śr. w ruchu", Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        RStat("${st.maxKmh.toInt()} km/h", "maks.", Modifier.weight(1f))
                        RStat(if (st.steps > 0) Fmt.steps(st.steps) else "—", "kroków (ok.)", Modifier.weight(1f))
                        RStat("${x.pts.size}", "punktów GPS", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Prędkość w czasie", style = MaterialTheme.typography.labelLarge)
                    SpeedChart(st.profile)
                    Text(
                        "Kolor trasy na mapie: zielony — szybciej, czerwony — wolniej (korki, światła, postoje).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val st0 = Routes.stats(e, x.pts, cat?.kind)
        if (st0.splits.size >= 2) {
            item { SectionTitle("Kilometr po kilometrze") }
            item {
                TrendCard {
                    val best = st0.splits.min()
                    st0.splits.forEachIndexed { i, ms ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1} km", Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
                            Box(
                                Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    Modifier.fillMaxWidth((best.toFloat() / ms).coerceIn(0.1f, 1f)).height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (ms == best) Color(0xFF34C759) else Color(0xFF0A84FF))
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (walkLikeKind(cat?.kind)) pace(ms / 1000.0) else "${Fmt.durationShort(ms)} · ${(3_600_000.0 / ms).toInt()} km/h",
                                style = MaterialTheme.typography.bodySmall, fontWeight = if (ms == best) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if (x.same.isNotEmpty()) {
            val all = x.same + info
            val best = all.minBy { it.durationMs }
            val avg = all.map { it.durationMs }.average().toLong()
            item { SectionTitle("Ta sama trasa · ${all.size}×") }
            item {
                TrendCard {
                    Text(
                        "Średnio ${Fmt.durationShort(avg)} · najszybciej ${Fmt.durationShort(best.durationMs)} (${best.entry.startAt.let { Fmt.toLocalDate(it).format(dayF) }})" +
                            if (info.durationMs <= avg) " · dziś szybciej niż zwykle 👍" else " · dziś o ${Fmt.durationShort(info.durationMs - avg)} dłużej",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PairStats(all)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.clickable { showOthers = !showOthers }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(showOthers, { showOthers = it })
                        Text("Pokaż poprzednie przejazdy na mapie (szare)")
                    }
                    // Słupki czasu przejazdu: ostatnie 20.
                    val bars = all.sortedBy { it.entry.startAt }.takeLast(20)
                    val maxD = bars.maxOf { it.durationMs }.toFloat()
                    Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        bars.forEach { b ->
                            Box(
                                Modifier.weight(1f).barHeight(b.durationMs / maxD).clip(RoundedCornerShape(3.dp))
                                    .background(if (b.entry.id == e.id) Color(0xFF0A84FF) else Color(0x668E8E93))
                                    .clickable { if (b.entry.id != e.id) onOpen("route:${b.entry.id}") }
                            )
                        }
                    }
                }
            }
            items(x.same.take(15), key = { it.entry.id }) { r -> RouteRow(r, catMap[r.entry.categoryId]?.name) { onOpen("route:${r.entry.id}") } }
        }
    }
}

private fun Modifier.barHeight(f: Float) = this.then(Modifier.height((70 * f.coerceIn(0.05f, 1f)).dp))

@Composable
private fun RStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RouteRow(r: RouteInfo, cat: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${r.from} → ${r.to}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Fmt.toLocalDate(r.entry.startAt).format(dayF)} · ${Fmt.time(r.entry.startAt)}" + (cat?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.durationShort(r.durationMs), fontWeight = FontWeight.SemiBold)
            val d = r.entry.distanceM ?: 0.0
            if (d > 0) {
                val kmh = d / (r.durationMs / 1000.0).coerceAtLeast(1.0) * 3.6
                Text(
                    "${Fmt.distance(d)} · ${kmh.toInt()} km/h",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Lista tras: filtr wg rodzaju, powtarzające się trasy (np. dom → praca). */
@Composable
fun RoutesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = LifeApp.instance
    val categories by app.repo.categories.collectAsState(initial = emptyList())
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val list by produceState<List<RouteInfo>?>(null) {
        val now = System.currentTimeMillis()
        value = Routes.describe(ctx, now - 365L * 86_400_000L, now).sortedByDescending { it.entry.startAt }
    }
    var cat by rememberSaveable { mutableStateOf<Long?>(null) }
    var pair by rememberSaveable { mutableStateOf<String?>(null) }
    var range by rememberSaveable { mutableStateOf(30) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Trasy", onBack = onBack) }
        val l = list
        if (l == null) { item { Text("Wczytuję…", Modifier.padding(20.dp)) }; return@LazyColumn }
        if (l.isEmpty()) { item { Text("Brak zapisanych tras.", Modifier.padding(20.dp)) }; return@LazyColumn }
        val cats = l.map { it.entry.categoryId }.distinct().mapNotNull { catMap[it] }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(cat == null && pair == null, { cat = null; pair = null }, { Text("Wszystkie") })
                cats.forEach { c -> FilterChip(cat == c.id, { cat = if (cat == c.id) null else c.id; pair = null }, { Text(c.name) }) }
            }
        }
        val pairs = l.mapNotNull { r -> r.pairKey?.let { it to r } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 3 }.toList().sortedByDescending { it.second.size }.take(6)
        if (pairs.isNotEmpty() && cat == null) {
            item { SectionTitle("Powtarzające się trasy") }
            items(pairs, key = { "p" + it.first }) { (key, rs) ->
                val avg = rs.map { it.durationMs }.average().toLong()
                val best = rs.minOf { it.durationMs }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (pair == key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { pair = if (pair == key) null else key }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${rs.first().from} → ${rs.first().to}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "${rs.size}× · średnio ${Fmt.durationShort(avg)} · najszybciej ${Fmt.durationShort(best)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val shown = l.filter {
            (cat == null || it.entry.categoryId == cat) && (pair == null || it.pairKey == pair) &&
                it.entry.startAt >= System.currentTimeMillis() - range * 86_400_000L
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(7 to "7 dni", 30 to "30 dni", 365 to "Rok").forEach { (d, t) -> FilterChip(range == d, { range = d }, { Text(t) }) }
            }
        }
        if (shown.isNotEmpty()) item { RoutesSummary(shown, catMap) }
        item { SectionTitle(if (pair != null) "Przejazdy tą trasą · ${shown.size}" else "Ostatnie · ${shown.size}") }
        items(shown.take(200), key = { it.entry.id }) { r -> RouteRow(r, catMap[r.entry.categoryId]?.name) { onOpen("route:${r.entry.id}") } }
    }
}

private fun walkLikeKind(k: String?) = k == pl.rafal.lifecycle.data.Kind.WALK || k == pl.rafal.lifecycle.data.Kind.RUN

/** Wykres prędkości w czasie trasy. */
@Composable
private fun SpeedChart(profile: List<Pair<Long, Double>>) {
    if (profile.size < 3) return
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val maxV = profile.maxOf { it.second }.coerceAtLeast(5.0)
    val maxT = profile.last().first.coerceAtLeast(1L)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical = 6.dp)) {
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height * f), androidx.compose.ui.geometry.Offset(size.width, size.height * f))
        }
        val path = androidx.compose.ui.graphics.Path()
        profile.forEachIndexed { i, (t, v) ->
            val x = size.width * t / maxT
            val y = size.height * (1f - (v / maxV).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
    Text(
        "maks. ${maxV.toInt()} km/h · ${Fmt.durationShort(maxT)}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Podsumowanie wybranych tras: liczba, dystans, czas, prędkość, rekordy, podział na rodzaje. */
@Composable
private fun RoutesSummary(list: List<RouteInfo>, catMap: Map<Long, pl.rafal.lifecycle.data.Category>) {
    val km = list.sumOf { it.entry.distanceM ?: 0.0 }
    val ms = list.sumOf { it.durationMs }
    val longest = list.maxBy { it.entry.distanceM ?: 0.0 }
    val days = list.map { Fmt.toLocalDate(it.entry.startAt) }.distinct().size
    TrendCard {
        Row {
            RStat("${list.size}", "tras", Modifier.weight(1f))
            RStat(Fmt.distance(km), "dystans", Modifier.weight(1f))
            RStat(Fmt.durationShort(ms), "w drodze", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            RStat(if (ms > 0) "${(km / (ms / 1000.0) * 3.6).toInt()} km/h" else "—", "średnio", Modifier.weight(1f))
            RStat(if (days > 0) Fmt.distance(km / days) else "—", "dziennie", Modifier.weight(1f))
            RStat(Fmt.distance(longest.entry.distanceM ?: 0.0), "najdłuższa", Modifier.weight(1f))
        }
        val byCat = list.groupBy { it.entry.categoryId }.mapValues { (_, l) -> l.sumOf { it.entry.distanceM ?: 0.0 } to l.sumOf { it.durationMs } }
            .toList().sortedByDescending { it.second.first }
        if (byCat.size > 1) {
            Spacer(Modifier.height(10.dp))
            byCat.forEach { (id, v) ->
                val c = catMap[id]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(c, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(c?.name ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${Fmt.distance(v.first)} · ${Fmt.durationShort(v.second)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Statystyki tej samej trasy: rozrzut czasu, dystans, najlepsza pora wyjazdu. */
@Composable
private fun PairStats(all: List<RouteInfo>) {
    val worst = all.maxBy { it.durationMs }
    val dists = all.mapNotNull { it.entry.distanceM?.takeIf { d -> d > 0 } }
    // Pora wyjazdu co pół godziny → średni czas przejazdu.
    val bySlot = all.groupBy {
        val z = java.time.Instant.ofEpochMilli(it.entry.startAt).atZone(Fmt.zone)
        z.hour * 2 + z.minute / 30
    }.filterValues { it.size >= 2 }.mapValues { (_, l) -> l.map { it.durationMs }.average() }
    val bestSlot = bySlot.minByOrNull { it.value }
    val worstSlot = bySlot.maxByOrNull { it.value }
    fun slot(s: Int) = "%d:%02d".format(s / 2, (s % 2) * 30)
    Spacer(Modifier.height(4.dp))
    Text(
        "Najwolniej ${Fmt.durationShort(worst.durationMs)} (${Fmt.toLocalDate(worst.entry.startAt).format(dayF)})" +
            (if (dists.isNotEmpty()) " · średnio ${Fmt.distance(dists.average())}" else ""),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (bestSlot != null && worstSlot != null && bestSlot.key != worstSlot.key) {
        Text(
            "Najszybciej, gdy ruszasz ok. ${slot(bestSlot.key)} (śr. ${Fmt.durationShort(bestSlot.value.toLong())}); " +
                "najwolniej ok. ${slot(worstSlot.key)} (śr. ${Fmt.durationShort(worstSlot.value.toLong())})",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
