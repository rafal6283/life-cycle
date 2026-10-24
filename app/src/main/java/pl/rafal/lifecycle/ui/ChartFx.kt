package pl.rafal.lifecycle.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/*
 * Wspólne "efekty" wykresów: płynne wejście (słupki rosną kaskadą),
 * płynne przejście między danymi (np. zmiana okresu), wybór słupka
 * dotknięciem lub przeciągnięciem palcem z dymkiem i lekką wibracją.
 */

/** Animowana seria: przy pierwszym pokazaniu rośnie od zera, przy zmianie danych płynie od starych wartości do nowych. */
class AnimSeries(private val from: List<Float>, private val to: List<Float>, val t: Float) {
    val size get() = to.size

    /** Wartość i-tego elementu z lekkim opóźnieniem kaskadowym (lewy rośnie pierwszy). */
    operator fun get(i: Int): Float {
        val n = to.size
        val delay = if (n <= 1) 0f else 0.35f * i / (n - 1)
        val p = ((t - delay) / (1f - 0.35f)).coerceIn(0f, 1f)
        val e = 1f - (1f - p) * (1f - p) * (1f - p) // ease-out cubic
        val a = from.getOrElse(i) { 0f }
        return a + (to[i] - a) * e
    }
}

@Composable
fun animatedSeries(values: List<Float>, durationMs: Int = 750): AnimSeries {
    var from by remember { mutableStateOf(List(values.size) { 0f }) }
    var to by remember { mutableStateOf(values) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(values) {
        if (to != values || t.value < 1f) {
            val shown = AnimSeries(from, to, t.value)
            from = if (to.size == values.size) List(values.size) { shown[it] } else List(values.size) { 0f }
            to = values
            t.snapTo(0f)
            t.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
        }
    }
    return AnimSeries(from, to, t.value)
}

/** Postęp 0→1 wejścia wykresu, uruchamiany ponownie przy zmianie klucza. */
@Composable
fun entrance(key: Any?, durationMs: Int = 800): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        a.snapTo(0f)
        a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return a.value
}

/** Stan wybranego elementu wykresu + płynne pojawianie się dymka. */
class Selection {
    var index by mutableIntStateOf(-1)
    var last by mutableIntStateOf(-1)
    val alpha = Animatable(0f)
}

@Composable
fun rememberSelection(reset: Any? = null): Selection {
    val s = remember(reset) { Selection() }
    LaunchedEffect(s.index) {
        if (s.index >= 0) {
            s.last = s.index
            s.alpha.animateTo(1f, tween(160))
        } else {
            s.alpha.animateTo(0f, tween(220))
        }
    }
    return s
}

/**
 * Dotknięcie wybiera element (drugie dotknięcie tego samego chowa dymek),
 * przeciąganie palcem w poziomie przesuwa wybór. Każda zmiana = delikatny "klik".
 */
@Composable
fun Modifier.chartTouch(sel: Selection, count: Int, indexAt: (o: Offset, width: Float) -> Int): Modifier {
    val haptic = LocalHapticFeedback.current
    val find by rememberUpdatedState(indexAt)
    fun pick(i: Int) {
        val k = if (i in 0 until count) i else -1
        if (k != sel.index) {
            sel.index = k
            if (k >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    return this
        .pointerInput(sel, count) {
            detectTapGestures { o ->
                val i = find(o, size.width.toFloat())
                if (i == sel.index || i < 0) sel.index = -1 else pick(i)
            }
        }
        .pointerInput(sel, count) {
            detectHorizontalDragGestures(
                onDragStart = { o -> pick(find(o, size.width.toFloat())) },
                onHorizontalDrag = { change, _ -> pick(find(change.position, size.width.toFloat())) },
            )
        }
}

/** Mocniejszy kolor wybranego słupka, przygaszone pozostałe. */
fun barAlpha(i: Int, sel: Selection, base: Float): Float {
    val a = sel.alpha.value
    if (sel.last < 0 || a <= 0f) return base
    return if (i == sel.last) base + (1f - base) * a else base * (1f - 0.55f * a)
}

/** Dymek z tekstem nad punktem (x, y); dosuwany do krawędzi wykresu. */
fun DrawScope.tooltip(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    bg: Color,
    alpha: Float,
    guide: Color? = null,
    guideBottom: Float = 0f,
) {
    if (alpha <= 0f) return
    val t = measurer.measure(text, style)
    val padH = 8.dp.toPx()
    val padV = 4.dp.toPx()
    val w = t.size.width + 2 * padH
    val h = t.size.height + 2 * padV
    val lift = 6.dp.toPx() * (1f - alpha)
    val left = (x - w / 2f).coerceIn(0f, maxOf(0f, size.width - w))
    val top = (y - h - 6.dp.toPx() + lift).coerceAtLeast(0f)
    if (guide != null) {
        drawLine(guide.copy(alpha = guide.alpha * alpha), Offset(x, top + h), Offset(x, guideBottom), strokeWidth = 1.dp.toPx())
    }
    drawRoundRect(bg.copy(alpha = 0.94f * alpha), Offset(left, top), Size(w, h), CornerRadius(6.dp.toPx(), 6.dp.toPx()))
    drawText(t, topLeft = Offset(left + padH, top + padV), alpha = alpha)
}

/** Kaskada: postęp i-tego z n elementów przy globalnym postępie t (0→1), z wygładzeniem ease-out. */
fun stagger(t: Float, i: Int, n: Int, spread: Float = 0.35f): Float {
    val delay = if (n <= 1) 0f else spread * i / (n - 1)
    val p = ((t - delay) / (1f - spread)).coerceIn(0f, 1f)
    return 1f - (1f - p) * (1f - p) * (1f - p)
}
