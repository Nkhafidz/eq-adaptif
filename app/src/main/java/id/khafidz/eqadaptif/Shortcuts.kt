package id.khafidz.eqadaptif

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Widget layar utama: status AI dan tombol on/off. */
class EqWidget : AppWidgetProvider() {

    companion object {
        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, EqWidget::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
            } catch (_: Exception) {}
        }

        private fun views(ctx: Context): RemoteViews {
            Store.init(ctx)
            val on = EqService.instance != null
            val v = RemoteViews(ctx.packageName, R.layout.widget_eq)
            v.setTextViewText(R.id.w_preset, if (!on) "Mati" else if (Store.aiOn) "AI aktif" else "AI dijeda")
            v.setTextViewText(R.id.w_power, if (on) "⏻ ON" else "⏻ OFF")
            v.setOnClickPendingIntent(
                R.id.w_power,
                if (on) PendingIntent.getService(
                    ctx, 13, Intent(ctx, EqService::class.java).setAction(EqService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ) else PendingIntent.getActivity(
                    ctx, 15, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_AUTOSTART),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            v.setOnClickPendingIntent(
                R.id.w_title,
                PendingIntent.getActivity(ctx, 14, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            return v
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetManager.updateAppWidget(appWidgetIds, views(context))
    }
}

/** Tombol di panel Quick Settings. */
class EqTile : TileService() {

    companion object {
        fun refresh(ctx: Context) {
            try { TileService.requestListeningState(ctx, ComponentName(ctx, EqTile::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        Store.init(this)
        val t = qsTile ?: return
        val on = EqService.instance != null
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "EQ Adaptif"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) (if (Store.aiOn) "AI aktif" else "AI dijeda") else "Mati"
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (EqService.instance != null) {
            EqService.stop(this)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_AUTOSTART)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 21, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
