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
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.R
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.tracking.Notifications
import pl.rafal.lifecycle.ui.MainActivity
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

/** Widżet: pierścień dzisiejszego dnia, bieżąca aktywność i 3 największe kategorie. */
class DobaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as LifeApp
        app.appScope.launch {
            try {
                updateAll(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DobaWidget::class.java))
            if (ids.isEmpty()) return
            val app = ctx.applicationContext as LifeApp
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val from = Fmt.dayStart(today)
            val to = Fmt.dayStart(today.plusDays(1))
            val entries = app.db.entries().getRange(from, to)
            val cats = app.db.categories().getAll().associateBy { it.id }
            val places = app.db.places().getAll().associateBy { it.id }
            val segs = Timeline.resolve(entries, from, to, now)
            val totals = Timeline.totals(segs).toList().sortedByDescending { it.second }
            val tracked = totals.sumOf { it.second }
            val current = segs.lastOrNull()?.takeIf { now - it.end < 3 * 60_000L }

            val ring = drawRing(
                segs.map { (cats[it.entry.categoryId]?.color ?: 0xFFA0A0A8.toInt()) to (it.start - from to it.end - from) },
                to - from, now - from, 240,
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget_doba)
            views.setImageViewBitmap(R.id.ring, ring)
            if (current != null) {
                val cat = cats[current.entry.categoryId]
                val place = current.entry.placeId?.let { places[it] }
                views.setTextViewText(R.id.title, place?.name ?: cat?.name ?: "Doba")
                views.setTextViewText(
                    R.id.subtitle,
                    "od ${Fmt.time(maxOf(current.entry.startAt, from))} · ${Fmt.durationShort(now - current.entry.startAt)}",
                )
            } else {
                views.setTextViewText(R.id.title, "Doba")
                views.setTextViewText(R.id.subtitle, if (tracked > 0) "Śledzono ${Fmt.durationShort(tracked)}" else "Brak danych z dziś")
            }
            views.setTextViewText(
                R.id.legend,
                totals.take(3).joinToString(" · ") { (id, v) -> "${cats[id]?.name ?: "—"} ${Fmt.durationShort(v)}" },
            )
            views.setOnClickPendingIntent(R.id.root, Notifications.openApp(ctx, null))
            mgr.updateAppWidget(ids, views)
        }

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
            val ang = Math.toRadians(-90.0 + 360.0 * now / dayLen)
            val r = rect.width() / 2
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            c.drawCircle(
                size / 2f + r * Math.cos(ang).toFloat(),
                size / 2f + r * Math.sin(ang).toFloat(),
                sw * 0.3f, dot,
            )
            return bmp
        }
    }
}

/** Kafelek w szybkich ustawieniach: „Teraz robię…”. */
class QuickTileImpl {
    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", "quick")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

class QuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.tile_label)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = QuickTileImpl.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 11, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
