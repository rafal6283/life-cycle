package pl.rafal.lifecycle.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class DonutSlice(
    /** Unikalny klucz (np. id segmentu albo kategorii) — do zaznaczania. */
    val key: Any,
    val color: Color,
    val value: Float,
    val iconKey: String?,
    val label: String,
)

private data class SliceGeo(val slice: DonutSlice, val start: Float, val sweep: Float)

/**
 * Pierścień w stylu Life Cycle: grube segmenty z przerwami, ikona i czas w środku segmentu,
 * animacja rozwijania i wysuwanie zaznaczonego segmentu.
 */
@Composable
fun AnimatedDonut(
    slices: List<DonutSlice>,
    selected: Any?,
    onSelect: (Any?) -> Unit,
    animKey: Any,
    modifier: Modifier = Modifier,
    centerContent: @Composable () -> Unit,
) {
    val progress = remember(animKey) { Animatable(0f) }
    LaunchedEffect(animKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 950, easing = FastOutSlowInEasing))
    }
    val pop by animateFloatAsState(
        targetValue = if (selected != null) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "pop",
    )

    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val painters = CatIcons.all.associate { (k, v) -> k to rememberVectorPainter(v) }
    val measurer = rememberTextMeasurer()
    val gapColor = MaterialTheme.colorScheme.background
    val dashColor = MaterialTheme.colorScheme.surfaceVariant
    val labelStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

    val geo = remember(slices) {
        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) emptyList() else {
            val minSweep = 3f
            val raw = slices.map { max(360f * it.value / total, minSweep) }
            val scale = 360f / raw.sum()
            var a = -90f
            slices.mapIndexed { i, s ->
                val sw = raw[i] * scale
                SliceGeo(s, a, sw).also { a += sw }
            }
        }
    }

    Box(
        modifier.pointerInput(geo) {
            detectTapGestures { pos ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val dx = pos.x - c.x
                val dy = pos.y - c.y
                val dist = sqrt(dx * dx + dy * dy)
                val outer = size.width.coerceAtMost(size.height) / 2f - 14.dp.toPx()
                val thick = outer * 0.44f
                if (dist < outer - thick - 6.dp.toPx() || dist > outer + 10.dp.toPx()) {
                    currentOnSelect(null)
                    return@detectTapGestures
                }
                var ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                while (ang < -90f) ang += 360f
                val hit = geo.firstOrNull { ang >= it.start && ang < it.start + it.sweep }
                currentOnSelect(if (hit == null || hit.slice.key == currentSelected) null else hit.slice.key)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f - 14.dp.toPx()
            val thick = outer * 0.44f
            val mid = outer - thick / 2f
            val gapPx = 3.dp.toPx()
            val gapDeg = Math.toDegrees((gapPx / mid).toDouble()).toFloat()

            // Wewnętrzny przerywany pierścień (24 kreski = godziny).
            val innerR = outer - thick - 12.dp.toPx()
            val dashStroke = 9.dp.toPx()
            for (h in 0 until 24) {
                drawArc(
                    dashColor, -90f + h * 15f + 2f, 11f, false,
                    Offset(center.x - innerR, center.y - innerR), Size(innerR * 2, innerR * 2),
                    style = Stroke(dashStroke),
                )
            }

            if (geo.isEmpty()) {
                drawArc(
                    dashColor, 0f, 360f, false,
                    Offset(center.x - mid, center.y - mid), Size(mid * 2, mid * 2),
                    style = Stroke(thick),
                )
                return@Canvas
            }

            val limit = -90f + 360f * progress.value
            for (g in geo) {
                if (g.start >= limit) break
                val isSel = g.slice.key == selected
                val dim = selected != null && !isSel
                val sweep = (minOf(g.start + g.sweep, limit) - g.start - gapDeg).coerceAtLeast(0.4f)
                val midAng = Math.toRadians((g.start + g.sweep / 2).toDouble())
                val push = if (isSel) 10.dp.toPx() * pop else 0f
                val ox = (cos(midAng) * push).toFloat()
                val oy = (sin(midAng) * push).toFloat()
                val r = if (isSel) mid + 3.dp.toPx() * pop else mid
                val t = if (isSel) thick + 6.dp.toPx() * pop else thick
                drawArc(
                    g.slice.color.copy(alpha = if (dim) 0.35f + 0.65f * (1f - pop) else 1f),
                    g.start + gapDeg / 2, sweep, false,
                    Offset(center.x - r + ox, center.y - r + oy), Size(r * 2, r * 2),
                    style = Stroke(t),
                )
            }

            // Ikony i czasy — pojawiają się pod koniec animacji.
            val labelAlpha = ((progress.value - 0.75f) / 0.25f).coerceIn(0f, 1f)
            if (labelAlpha <= 0f) return@Canvas
            val iconSize = 20.dp.toPx()
            for (g in geo) {
                if (g.sweep < 9f) continue
                val isSel = g.slice.key == selected
                val dim = selected != null && !isSel
                val a = Math.toRadians((g.start + g.sweep / 2).toDouble())
                val push = if (isSel) 10.dp.toPx() * pop else 0f
                val px = center.x + ((mid + push) * cos(a)).toFloat()
                val py = center.y + ((mid + push) * sin(a)).toFloat()
                val alpha = labelAlpha * (if (dim) 0.5f else 1f)
                val showText = g.sweep >= 20f
                val text = if (showText) measurer.measure(g.slice.label, labelStyle) else null
                val totalH = iconSize + (text?.size?.height ?: 0)
                val painter = g.slice.iconKey?.let { painters[it] }
                if (painter != null) {
                    translate(px - iconSize / 2, py - totalH / 2) {
                        with(painter) {
                            draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(Color.White))
                        }
                    }
                }
                if (text != null) {
                    drawText(
                        text,
                        topLeft = Offset(px - text.size.width / 2f, py - totalH / 2 + iconSize),
                        alpha = alpha,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = selected,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(150)) },
            label = "center",
        ) { _ -> centerContent() }
    }
}
