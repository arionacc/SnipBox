package com.arionacc.snipbox

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** Tile Quick Settings (termasuk Samsung One UI) untuk menyalakan/mematikan ikon melayang. */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        } else if (!Settings.canDrawOverlays(this)) {
            openApp()
        } else {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                )
            } catch (e: Exception) {
                openApp()
            }
        }
        // Layanan memperbarui tile lagi lewat requestListeningState; ini hanya tampilan cepat
        qsTile?.let {
            it.state = if (OverlayService.running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun render() {
        val t = qsTile ?: return
        t.state = if (OverlayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "SnipBox"
        t.updateTile()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
