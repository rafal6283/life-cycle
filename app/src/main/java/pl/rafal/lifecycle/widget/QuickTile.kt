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
