package pl.rafal.lifecycle.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.R
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.domain.ExchangeImport
import pl.rafal.lifecycle.domain.ExtVisibility
import pl.rafal.lifecycle.domain.Metric
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.PeriodType
import pl.rafal.lifecycle.domain.Stats
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.domain.Trends
import pl.rafal.lifecycle.tracking.Notifications
import pl.rafal.lifecycle.tracking.Steps
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate

/** Wspólna obsługa: każda aktualizacja odświeża wszystkie trzy warianty widżetu. */
abstract class BaseDobaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == DobaWidget.ACTION_REFRESH) refresh(context)
    }

    private fun refresh(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as LifeApp
        app.appScope.launch {
            try {
                DobaWidget.updateAll(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** Mały: pierścień dnia, bieżąca aktywność, kroki i 3 kategorie. */
class DobaWidget : BaseDobaWidget() {
    companion object {
        const val ACTION_REFRESH = "pl.rafal.lifecycle.WIDGET_REFRESH"
        private val lock = Mutex()

        suspend fun updateAll(ctx: Context) = lock.withLock {
            val mgr = AppWidgetManager.getInstance(ctx)
            val small = mgr.getAppWidgetIds(ComponentName(ctx, DobaWidget::class.java))
            val large = mgr.getAppWidgetIds(ComponentName(ctx, DobaWidgetLarge::class.java))
            val week = mgr.getAppWidgetIds(ComponentName(ctx, DobaWidgetWeek::class.java))
            if (small.isEmpty() && large.isEmpty() && week.isEmpty()) return@withLock
            val d = DayData.load(ctx)
            if (small.isNotEmpty()) mgr.updateAppWidget(small, small(ctx, d))
            if (large.isNotEmpty()) mgr.updateAppWidget(large, large(ctx, d))
            if (week.isNotEmpty()) mgr.updateAppWidget(week, week(ctx))
        }

        private fun refreshIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 21,
                Intent(ctx, DobaWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        // ------------------------------------------------------------------ dane dnia

        private data class DayData(
            val ring: Bitmap,
            val title: String,
            val subtitle: String,
            val steps: Long,
            val stepGoal: Int,
            val tracked: Long,
            val top: List<Triple<Category?, Long, Int>>,
            val health: String,
        ) {
            companion object {
                suspend fun load(ctx: Context): DayData {
                    val app = ctx.applicationContext as LifeApp
                    val now = System.currentTimeMillis()
                    val today = LocalDate.now()
                    val from = Fmt.dayStart(today)
                    val to = Fmt.dayStart(today.plusDays(1))
                    val entries = app.repo.getRange(from, to)
                    val cats = app.db.categories().getAll().associateBy { it.id }
                    val places = app.db.places().getAll().associateBy { it.id }
                    val segs = Timeline.resolve(entries, from, to, now)
                    val totals = Timeline.totals(segs).toList().sortedByDescending { it.second }
                    val tracked = totals.sumOf { it.second }
                    val current = segs.lastOrNull()?.takeIf { now - it.end < 3 * 60_000L }
                    val ring = drawRing(
                        segs.map { (cats[it.entry.categoryId]?.color ?: 0xFFA0A0A8.toInt()) to (it.start - from to it.end - from) },
                        to - from, now - from, 300,
                    )
                    val title: String
                    val subtitle: String
                    if (current != null) {
                        val cat = cats[current.entry.categoryId]
                        val place = current.entry.placeId?.let { places[it] }
                        title = place?.name ?: cat?.name ?: "Doba"
                        val since = maxOf(current.entry.startAt, from)
                        subtitle = "od ${Fmt.time(since)} · ${Fmt.durationShort(now - since)}" +
                            if (place != null && cat != null) " · ${cat.name}" else ""
                    } else {
                        title = "Doba"
                        subtitle = if (tracked > 0) "Śledzono ${Fmt.durationShort(tracked)}" else "Brak danych z dziś"
                    }
                    val steps = Steps.total(app.db.steps().getRange(from, to))

                    // Zdrowie dziś
                    val parts = mutableListOf<String>()
                    val hs = app.db.health().getRange(from, to).groupBy { it.metric }
                    hs[Metric.HR.key]?.takeIf { it.isNotEmpty() }?.let { l -> parts += "♥ ${Metric.HR.format(l.map { it.value }.average())} bpm" }
                    hs[Metric.RESTING_HR.key]?.lastOrNull()?.let { parts += "spocz. ${Metric.RESTING_HR.format(it.value)}" }
                    hs[Metric.ACTIVE_KCAL.key]?.sumOf { it.value }?.takeIf { it > 0 }?.let { parts += "🔥 ${Metric.ACTIVE_KCAL.format(it)} kcal" }
                    hs[Metric.BP.key]?.lastOrNull()?.let { parts += "${Metric.BP.format(it.value)}/${Metric.BP.format(it.v2 ?: 0.0)}" }
                    app.db.health().latest(Metric.WEIGHT.key)?.let { parts += "⚖ ${Metric.WEIGHT.format(it.value)} kg" }
                    app.db.screen().get(today.toEpochDay())?.takeIf { it.screenMs > 0 && Prefs(ctx).screenTime }
                        ?.let { parts += "📱 ${Fmt.durationShort(it.screenMs)}" }
                    if (ExtVisibility.fartsVisible) {
                        val ev = app.db.ext().all().filter { it.source == ExchangeImport.SRC_NIGHT && it.start in (from - 12 * 3_600_000L) until to }
                        ExchangeImport.dailyCounts(ev, ExchangeImport.SRC_NIGHT, "")[today]?.let { parts += "💨 $it" }
                    }

                    return DayData(
                        ring, title, subtitle, steps, Prefs(ctx).stepGoal, tracked,
                        totals.map { (id, v) -> Triple(cats[id], v, cats[id]?.color ?: 0xFFA0A0A8.toInt()) },
                        parts.joinToString("  ·  "),
                    )
                }
            }
        }

        private fun catsText(top: List<Triple<Category?, Long, Int>>, n: Int, oneLine: Boolean): CharSequence {
            val sb = SpannableStringBuilder()
            top.take(n).forEachIndexed { i, (c, v, color) ->
                if (i > 0) sb.append(if (oneLine) "  " else "\n")
                val s = sb.length
                sb.append("●")
                sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" ${c?.name ?: "—"} ${Fmt.durationShort(v)}")
            }
            return sb
        }

        private fun small(ctx: Context, d: DayData): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_doba)
            v.setImageViewBitmap(R.id.ring, d.ring)
            v.setTextViewText(R.id.title, d.title)
            v.setTextViewText(R.id.subtitle, d.subtitle)
            val legend = SpannableStringBuilder()
            if (d.steps > 0) legend.append("👟 ${Fmt.steps(d.steps)} kroków\n")
            legend.append(catsText(d.top, 3, true))
            v.setTextViewText(R.id.legend, legend)
            v.setOnClickPendingIntent(R.id.root, Notifications.openApp(ctx, null))
            return v
        }

        private fun large(ctx: Context, d: DayData): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_doba_large)
            v.setImageViewBitmap(R.id.ring, d.ring)
            v.setTextViewText(R.id.center_value, Fmt.steps(d.steps))
            v.setTextViewText(R.id.title, d.title)
            v.setTextViewText(R.id.subtitle, d.subtitle)
            val pct = if (d.stepGoal > 0) (d.steps * 100 / d.stepGoal).toInt() else 0
            v.setProgressBar(R.id.steps_bar, 100, pct.coerceIn(0, 100), false)
            v.setTextViewText(
                R.id.steps_text,
                if (d.stepGoal > 0) "Kroki: ${Fmt.steps(d.steps)} z ${Fmt.steps(d.stepGoal.toLong())} · $pct%" +
                    (if (d.steps >= d.stepGoal) " ✓" else "")
                else "Kroki: ${Fmt.steps(d.steps)}",
            )
            v.setTextViewText(R.id.cats, if (d.top.isEmpty()) "Jeszcze brak wpisów z dziś" else catsText(d.top, 4, false))
            v.setTextViewText(R.id.health, d.health)
            v.setViewVisibility(R.id.health, if (d.health.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            v.setOnClickPendingIntent(R.id.root, Notifications.openApp(ctx, null))
            v.setOnClickPendingIntent(R.id.btn_quick, Notifications.openApp(ctx, "quick"))
            v.setOnClickPendingIntent(R.id.btn_refresh, refreshIntent(ctx))
            return v
        }

        // ------------------------------------------------------------------ tydzień

        private suspend fun week(ctx: Context): RemoteViews {
            val app = ctx.applicationContext as LifeApp
            val now = System.currentTimeMillis()
            val p = Period.current(PeriodType.WEEK)
            val from = Fmt.dayStart(p.prev().start)
            val to = Fmt.dayStart(p.end)
            val entries = app.repo.getRange(from, to)
            val categories = app.db.categories().getAll()
            val cats = categories.associateBy { it.id }
            val s = Stats.compute(entries, p, now)
            val stepDays = Steps.perDay(app.db.steps().getRange(Fmt.dayStart(p.start), to))
            val order = categories.withIndex().associate { it.value.id to it.index }

            val bars = s.buckets.map { b ->
                b.toList().sortedBy { order[it.first] ?: Int.MAX_VALUE }.map { (id, ms) -> (cats[id]?.color ?: 0xFFA0A0A8.toInt()) to ms }
            }
            val chart = drawWeek(bars, (0 until 7).map { stepDays[p.start.plusDays(it.toLong())] ?: 0L }, LocalDate.now().dayOfWeek.value - 1)

            val v = RemoteViews(ctx.packageName, R.layout.widget_doba_week)
            v.setTextViewText(R.id.title, "Tydzień · ${p.label()}")
            val top = s.totals.toList().filter { cats[it.first]?.kind != Kind.UNKNOWN }.sortedByDescending { it.second }
                .take(3).map { (id, ms) -> Triple(cats[id], ms / s.days, cats[id]?.color ?: 0xFFA0A0A8.toInt()) }
            v.setTextViewText(R.id.subtitle, SpannableStringBuilder("średnio dziennie: ").append(catsText(top, 3, true)))
            v.setImageViewBitmap(R.id.chart, chart)

            val parts = mutableListOf<String>()
            val sleep = categories.firstOrNull { it.kind == Kind.SLEEP }
            if (sleep != null) {
                val nights = Trends.nights(entries, sleep.id, p.start, p.end, now)
                if (nights.isNotEmpty()) parts += "Sen śr. ${Fmt.durationShort(nights.sumOf { it.end - it.start } / nights.size)}"
            }
            if (stepDays.isNotEmpty()) parts += "Kroki śr. ${Fmt.steps(stepDays.values.sum() / stepDays.size)}"
            val work = categories.firstOrNull { it.kind == Kind.WORK }
            work?.let { s.totals[it.id] }?.let { parts += "Praca ${Fmt.durationShort(it)}" }
            val goal = Prefs(ctx).stepGoal
            if (goal > 0 && stepDays.isNotEmpty()) parts += "cel kroków ${stepDays.count { it.value >= goal }}/${stepDays.size}"
            v.setTextViewText(R.id.stats, parts.joinToString("  ·  ").ifEmpty { "Jeszcze brak danych z tego tygodnia" })
            v.setOnClickPendingIntent(R.id.root, Notifications.openApp(ctx, "summary:WEEK:${p.start.toEpochDay()}"))
            v.setOnClickPendingIntent(R.id.btn_refresh, refreshIntent(ctx))
            return v
        }

        // ------------------------------------------------------------------ rysowanie

        private fun drawRing(arcs: List<Pair<Int, Pair<Long, Long>>>, dayLen: Long, now: Long, size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val sw = size * 0.14f
            val rect = RectF(sw / 2 + 2, sw / 2 + 2, size - sw / 2 - 2, size - sw / 2 - 2)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = sw
            }
            p.color = 0x33FFFFFF
            c.drawArc(rect, 0f, 360f, false, p)
            for ((color, span) in arcs) {
                p.color = color
                val start = -90f + 360f * span.first / dayLen
                val sweep = (360f * (span.second - span.first) / dayLen).coerceAtLeast(1f)
                c.drawArc(rect, start, sweep, false, p)
            }
            // Kreski godzin 0/6/12/18
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = size * 0.012f }
            val r = rect.width() / 2
            for (h in 0 until 4) {
                val a = Math.toRadians(-90.0 + h * 90.0)
                val r1 = r - sw / 2 - size * 0.02f
                val r2 = r1 - size * 0.05f
                c.drawLine(
                    size / 2f + r1 * Math.cos(a).toFloat(), size / 2f + r1 * Math.sin(a).toFloat(),
                    size / 2f + r2 * Math.cos(a).toFloat(), size / 2f + r2 * Math.sin(a).toFloat(), tick,
                )
            }
            val ang = Math.toRadians(-90.0 + 360.0 * now / dayLen)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            c.drawCircle(size / 2f + r * Math.cos(ang).toFloat(), size / 2f + r * Math.sin(ang).toFloat(), sw * 0.3f, dot)
            return bmp
        }

        private val dayLabels = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")

        /** 7 słupków skumulowanych (kategorie) + kropki kroków. */
        private fun drawWeek(bars: List<List<Pair<Int, Long>>>, steps: List<Long>, todayIdx: Int): Bitmap {
            val w = 700
            val h = 260
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val labelH = 34f
            val chartH = h - labelH - 6f
            val slot = w / 7f
            val bw = slot * 0.56f
            val day = 24 * 3_600_000f
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 24f; textAlign = Paint.Align.CENTER }
            val bold = Paint(text).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val maxSteps = (steps.maxOrNull() ?: 0L).coerceAtLeast(1L)
            for (i in 0 until 7) {
                val x = i * slot + (slot - bw) / 2
                c.drawRoundRect(RectF(x, 0f, x + bw, chartH), 10f, 10f, track)
                var y = chartH
                bars.getOrNull(i)?.forEach { (color, ms) ->
                    val bh = chartH * (ms / day)
                    fill.color = color
                    c.drawRect(x, y - bh, x + bw, y, fill)
                    y -= bh
                }
                // kroki: mała kropka nad dniem (wielkość ∝ kroki)
                val st = steps.getOrNull(i) ?: 0L
                if (st > 0) {
                    fill.color = 0xFF34C759.toInt()
                    val r = 4f + 8f * st / maxSteps
                    c.drawCircle(x + bw + 10f, chartH - 8f - (chartH - 30f) * st / maxSteps, r, fill)
                }
                c.drawText(dayLabels[i], i * slot + slot / 2, h - 8f, if (i == todayIdx) bold else text)
            }
            return bmp
        }
    }
}

/** Duży: dzień w szczegółach + szybkie przełączanie. */
class DobaWidgetLarge : BaseDobaWidget()

/** Tydzień: czas w kategoriach dzień po dniu. */
class DobaWidgetWeek : BaseDobaWidget()
