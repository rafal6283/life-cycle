package pl.rafal.lifecycle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.util.Fmt
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue

// ---------- Motyw ----------

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    background = Color(0xFFF4F4F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9E9EF),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEDEDF2),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF2C2C2E),
)

@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}

// ---------- Ikony kategorii ----------

object CatIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "bed" to Icons.Filled.Bedtime,
        "home" to Icons.Filled.Home,
        "work" to Icons.Filled.Work,
        "car" to Icons.Filled.DirectionsCar,
        "walk" to Icons.AutoMirrored.Filled.DirectionsWalk,
        "run" to Icons.AutoMirrored.Filled.DirectionsRun,
        "bike" to Icons.AutoMirrored.Filled.DirectionsBike,
        "gym" to Icons.Filled.FitnessCenter,
        "food" to Icons.Filled.Restaurant,
        "shop" to Icons.Filled.ShoppingCart,
        "friends" to Icons.Filled.People,
        "fun" to Icons.Filled.Movie,
        "study" to Icons.Filled.School,
        "coffee" to Icons.Filled.LocalCafe,
        "health" to Icons.Filled.LocalHospital,
        "flight" to Icons.Filled.Flight,
        "train" to Icons.Filled.Train,
        "bus" to Icons.Filled.DirectionsBus,
        "park" to Icons.Filled.Park,
        "pets" to Icons.Filled.Pets,
        "music" to Icons.Filled.MusicNote,
        "sport" to Icons.Filled.SportsSoccer,
        "swim" to Icons.Filled.Pool,
        "beach" to Icons.Filled.BeachAccess,
        "book" to Icons.AutoMirrored.Filled.MenuBook,
        "family" to Icons.Filled.FamilyRestroom,
        "tv" to Icons.Filled.Tv,
        "hair" to Icons.Filled.ContentCut,
        "church" to Icons.Filled.Church,
        "moon" to Icons.Filled.DarkMode,
        "commute" to Icons.Filled.Commute,
        "weight" to Icons.Filled.MonitorWeight,
        "lungs" to Icons.Filled.Air,
        "air" to Icons.Filled.Air,
        "temp" to Icons.Filled.Thermostat,
        "drop" to Icons.Filled.WaterDrop,
        "fire" to Icons.Filled.LocalFireDepartment,
        "stairs" to Icons.Filled.Stairs,
        "heart" to Icons.Filled.Favorite,
        "bar" to Icons.Filled.LocalBar,
        "bag" to Icons.Filled.ShoppingBag,
        "relax" to Icons.Filled.Spa,
        "sofa" to Icons.Filled.Weekend,
        "unknown" to Icons.Filled.QuestionMark,
    )
    private val map = all.toMap()
    fun get(key: String?): ImageVector = map[key] ?: Icons.Filled.QuestionMark
}

val CategoryPalette: List<Int> = listOf(
    0xFF5E5CE6, 0xFF30B0C7, 0xFFFF9F0A, 0xFFAC8E68, 0xFF34C759, 0xFFFF3B30, 0xFFFFCC00, 0xFFFF2D55,
    0xFFFF6B22, 0xFFBF5AF2, 0xFFFF8AC4, 0xFF8E44AD, 0xFF007AFF, 0xFF00C7BE, 0xFF64D2FF, 0xFF6D8B74,
    0xFFA2845E, 0xFF5856D6, 0xFFE91E63, 0xFF8BC34A, 0xFF795548, 0xFF607D8B, 0xFFA0A0A8, 0xFF1C1C1E,
).map { it.toInt() }

val UnknownGray = Color(0xFFA0A0A8)

fun catColor(c: Category?): Color = c?.let { Color(it.color) } ?: UnknownGray

// ---------- Drobne komponenty ----------

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis()
    }
}

@Composable
fun CategoryBadge(cat: Category?, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(catColor(cat)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CatIcons.get(cat?.icon), null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Fmt.PL),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(categories: List<Category>, selected: Long?, onSelect: (Long) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { c ->
            val sel = c.id == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) Color(c.color) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(c.id) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    CatIcons.get(c.icon), null,
                    tint = if (sel) Color.White else Color(c.color),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    c.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Pasek udziału (np. w podsumowaniu dnia). */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    // pasek wypełnia się płynnie przy pojawieniu i przy każdej zmianie
    val target = fraction.coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) }
    Box(
        modifier.height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier.fillMaxWidth(anim.value.coerceIn(0f, 1f)).height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(color)
        )
    }
}

// ---------- Pierścienie ----------

data class RingArc(val color: Color, val start: Long, val end: Long)

/** Pierścień 24-godzinny: segmenty ułożone według pory dnia (jak tarcza zegara). */
@Composable
fun ClockRing(arcs: List<RingArc>, dayLength: Long, now: Long?, modifier: Modifier, stroke: Dp = 30.dp, animKey: Any = dayLength) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = tickColor)
    // tarcza "nawija się" od północy przy pokazaniu dnia
    val grow = rememberGrow(animKey)
    Canvas(modifier) {
        val sw = stroke.toPx()
        val inset = sw / 2 + 16.dp.toPx()
        val d = size.minDimension - 2 * inset
        val arcSize = Size(d, d)
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(sw))
        val limit = -90f + 360f * grow
        arcs.forEach { a ->
            val start = -90f + 360f * a.start / dayLength
            if (start >= limit) return@forEach
            val sweep = (360f * (a.end - a.start) / dayLength).coerceAtLeast(0.8f)
            drawArc(a.color, start, minOf(sweep, limit - start), false, tl, arcSize, style = Stroke(sw))
        }
        val r = d / 2
        val labelR = r + sw / 2 + 9.dp.toPx()
        for (h in listOf(0, 6, 12, 18)) {
            val ang = Math.toRadians(-90.0 + h * 15.0)
            val t = measurer.measure(h.toString(), style)
            drawText(
                t,
                topLeft = Offset(
                    center.x + labelR * cos(ang).toFloat() - t.size.width / 2f,
                    center.y + labelR * sin(ang).toFloat() - t.size.height / 2f,
                ),
            )
        }
        for (h in 0 until 24) {
            val ang = Math.toRadians(-90.0 + h * 15.0)
            val r1 = r - sw / 2 - 4.dp.toPx()
            val r2 = r1 - (if (h % 6 == 0) 7.dp.toPx() else 3.dp.toPx())
            drawLine(
                tickColor.copy(alpha = 0.5f),
                Offset(center.x + r1 * cos(ang).toFloat(), center.y + r1 * sin(ang).toFloat()),
                Offset(center.x + r2 * cos(ang).toFloat(), center.y + r2 * sin(ang).toFloat()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (now != null && grow >= 1f) {
            val ang = Math.toRadians(-90.0 + 360.0 * now / dayLength)
            val p = Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat())
            drawCircle(Color.White, sw / 2 * 0.55f, p)
            drawCircle(nowColor, sw / 2 * 0.35f, p)
        }
    }
}

/** Pierścień proporcji: udział każdej kategorii w śledzonym czasie. */
@Composable
fun ProportionRing(slices: List<Pair<Color, Float>>, modifier: Modifier, stroke: Dp = 30.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val grow = rememberGrow(slices.map { it.second })
    Canvas(modifier) {
        val sw = stroke.toPx()
        val inset = sw / 2 + 16.dp.toPx()
        val d = size.minDimension - 2 * inset
        val arcSize = Size(d, d)
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(sw))
        val total = slices.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) {
            drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(sw))
            return@Canvas
        }
        val gap = if (slices.size > 1) 1.5f else 0f
        var a = -90f
        val limit = -90f + 360f * grow
        slices.forEach { (c, v) ->
            val sweep = 360f * v / total
            if (a < limit) {
                drawArc(c, a + gap / 2, (minOf(sweep, limit - a) - gap).coerceAtLeast(0.6f), false, tl, arcSize, style = Stroke(sw))
            }
            a += sweep
        }
    }
}

/**
 * Słupki skumulowane (rozkład w czasie). Wyrastają kaskadą od lewej;
 * dotknięcie albo przeciągnięcie palcem pokazuje sumę dla słupka w dymku.
 */
@Composable
fun BarsChart(
    bars: List<List<Pair<Color, Long>>>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    avg: Long? = null,
    animKey: Any = labels,
    tipLabels: List<String>? = null,
) {
    val grow = rememberGrow(animKey)
    val avgColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val sel = rememberSelection(animKey)
    val maxRaw = (bars.maxOfOrNull { b -> b.sumOf { it.second } } ?: 0L).coerceAtLeast(60_000L)
    val maxAnim by animateFloatAsState(maxRaw.toFloat(), spring(stiffness = Spring.StiffnessLow), label = "max")
    Canvas(
        modifier.fillMaxWidth().height(190.dp)
            .chartTouch(sel, bars.size) { o, w -> if (bars.isEmpty()) -1 else (o.x / (w / bars.size)).toInt().coerceIn(0, bars.size - 1) }
    ) {
        val maxV = maxAnim
        val labelH = 20.dp.toPx()
        val topPad = 18.dp.toPx()
        val chartH = size.height - labelH - topPad
        val n = bars.size.coerceAtLeast(1)
        val slot = size.width / n
        val bw = slot * 0.62f
        val baseY = topPad + chartH
        drawLine(grid, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
        drawLine(grid.copy(alpha = 0.5f), Offset(0f, topPad), Offset(size.width, topPad), 1.dp.toPx())
        bars.forEachIndexed { i, stack ->
            var y = baseY
            val x = i * slot + (slot - bw) / 2
            val g = stagger(grow, i, n)
            val a = barAlpha(i, sel, 1f)
            stack.forEach { (c, v) ->
                val h = chartH * (v.toFloat() / maxV) * g
                if (h > 0f) drawRect(c.copy(alpha = c.alpha * a), Offset(x, y - h), Size(bw, h))
                y -= h
            }
            val show = n <= 12 || i % 5 == 0
            if (show && i < labels.size) {
                val tl = measurer.measure(labels[i], labelStyle)
                drawText(tl, topLeft = Offset(i * slot + slot / 2 - tl.size.width / 2f, baseY + 4.dp.toPx()))
            }
        }
        val mt = measurer.measure(Fmt.durationShort(maxRaw), labelStyle)
        drawText(mt, topLeft = Offset(0f, 0f))
        if (avg != null && avg > 0) {
            val y = baseY - chartH * (avg.toFloat() / maxV)
            drawLine(
                avgColor.copy(alpha = avgColor.alpha * grow), Offset(0f, y), Offset(size.width * grow, y), 1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
            val at = measurer.measure("śr. " + Fmt.durationShort(avg), labelStyle)
            drawText(at, topLeft = Offset(size.width - at.size.width, (y - at.size.height - 2f).coerceAtLeast(0f)), alpha = grow)
        }
        val s = sel.last
        if (s in bars.indices) {
            val total = bars[s].sumOf { it.second }
            val top = baseY - chartH * (total.toFloat() / maxV)
            val name = tipLabels?.getOrNull(s) ?: labels.getOrNull(s) ?: ""
            tooltip(
                measurer, (if (name.isNotEmpty()) "$name · " else "") + Fmt.durationShort(total),
                s * slot + slot / 2, top, tipStyle, tipBg, sel.alpha.value, grid, baseY,
            )
        }
    }
}
