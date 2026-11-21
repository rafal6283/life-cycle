package pl.rafal.lifecycle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.domain.WeekHeat
import kotlin.math.roundToInt

private val dayNames = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")
private val dayLong = listOf("poniedziałki", "wtorki", "środy", "czwartki", "piątki", "soboty", "niedziele")

/**
 * Tydzień w kratkach (7 dni × 24 godziny): im mocniejszy kolor, tym częściej o tej porze trwa wybrana kategoria.
 * Kratki pojawiają się falą od poniedziałku rano; dotknięcie albo przeciąganie palcem pokazuje szczegóły.
 */
@Composable
fun WeekHeatCard(heat: Array<FloatArray>, color: Color, name: String, animKey: Any) {
    val grow = rememberGrow(animKey)
    var sel by remember(animKey) { mutableStateOf<Pair<Int, Int>?>(null) }
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.onSurface
    val leftDp = 26.dp
    val labelDp = 16.dp

    fun cellAt(o: Offset, w: Float, h: Float, left: Float, labelH: Float): Pair<Int, Int>? {
        if (o.x < left || o.y > h - labelH) return null
        val cw = (w - left) / 24f
        val ch = (h - labelH) / 7f
        return (o.y / ch).toInt().coerceIn(0, 6) to ((o.x - left) / cw).toInt().coerceIn(0, 23)
    }
    fun pick(c: Pair<Int, Int>?) {
        if (c != null && c != sel) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        sel = c
    }

    TrendCard {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(animKey) {
                    detectTapGestures { o ->
                        val c = cellAt(o, size.width.toFloat(), size.height.toFloat(), leftDp.toPx(), labelDp.toPx())
                        if (c == sel) sel = null else pick(c)
                    }
                }
                .pointerInput(animKey) {
                    detectDragGestures(
                        onDragStart = { o -> pick(cellAt(o, size.width.toFloat(), size.height.toFloat(), leftDp.toPx(), labelDp.toPx())) },
                        onDrag = { ch, _ -> pick(cellAt(ch.position, size.width.toFloat(), size.height.toFloat(), leftDp.toPx(), labelDp.toPx())) },
                    )
                }
        ) {
            val left = leftDp.toPx()
            val labelH = labelDp.toPx()
            val cw = (size.width - left) / 24f
            val ch = (size.height - labelH) / 7f
            val pad = 1.2.dp.toPx()
            val r = CornerRadius(2.5.dp.toPx())
            for (d in 0 until 7) {
                val t = measurer.measure(dayNames[d], style)
                drawText(t, topLeft = Offset(0f, d * ch + (ch - t.size.height) / 2f))
                for (h in 0 until 24) {
                    // fala: od lewego górnego rogu do prawego dolnego
                    val g = stagger(grow, d * 24 + h, 7 * 24, spread = 0.6f)
                    val v = heat[d][h]
                    val tl = Offset(left + h * cw + pad, d * ch + pad)
                    val sz = Size(cw - 2 * pad, ch - 2 * pad)
                    drawRoundRect(empty, tl, sz, r)
                    if (v > 0f) drawRoundRect(color.copy(alpha = (0.12f + 0.88f * v) * g), tl, sz, r)
                }
            }
            for (h in listOf(0, 6, 12, 18)) {
                val t = measurer.measure(h.toString(), style)
                drawText(t, topLeft = Offset(left + h * cw, size.height - labelH + 2.dp.toPx()))
            }
            sel?.let { (d, h) ->
                drawRoundRect(ring, Offset(left + h * cw, d * ch), Size(cw, ch), r, style = Stroke(2.dp.toPx()))
            }
        }
        val s = sel
        val peak = remember(heat) { WeekHeat.peak(heat) }
        Text(
            when {
                s != null -> {
                    val pct = (heat[s.first][s.second] * 100).roundToInt()
                    "${dayNames[s.first]} ${s.second}:00–${s.second + 1}:00 · „$name” w $pct% takich godzin"
                }
                peak != null -> "Najczęściej: ${dayLong[peak.first]} ${peak.second}:00 (${(peak.third * 100).roundToInt()}%). Dotknij kratki."
                else -> "Brak „$name” w tym okresie."
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (s != null) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
