package com.sanabria.shadervulkan.services

import com.sanabria.shadervulkan.AppLocale
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sanabria.shadervulkan.QuickStartActivity
import com.sanabria.shadervulkan.R

/**
 * Azulejo de ajustes rápidos (T70): enciende el último shader (en el último modo usado) y, con una sesión en marcha, la cierra.
 * El arranque pasa por [QuickStartActivity], que es quien puede pedir el permiso de captura.
 */
class ShaderTileService : TileService() {

    // Idioma de la app (no el del sistema) para el texto del icono de Ajustes rápidos
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onStartListening() = update()

    override fun onClick() {
        if (ShaderCaptureService.isRunning) {
            startService(Intent(this, ShaderCaptureService::class.java).apply { action = ShaderCaptureService.ACTION_STOP_FULL })
            return
        }
        val intent = Intent(this, QuickStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // la versión con PendingIntent solo existe desde Android 14
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = ShaderCaptureService.isRunning
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    companion object {
        /** Pide al sistema que repinte el azulejo (cambió el estado de la sesión). */
        fun refresh(context: Context) {
            try { requestListeningState(context, ComponentName(context, ShaderTileService::class.java)) } catch (_: Exception) {}
        }
    }
}
