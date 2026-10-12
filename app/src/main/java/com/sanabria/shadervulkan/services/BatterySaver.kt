package com.sanabria.shadervulkan.services

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.Toast
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.PerfSettings
import com.sanabria.shadervulkan.native.VulkanEngineBridge

/**
 * Modo batería (T71): por debajo de un porcentaje (y sin cargar) ahorra energía; al cargar o subir de nuevo, lo deja como estaba.
 * Acciones: REDUCIR (mitad de resolución virtual y 30 FPS), 30 FPS (solo el tope) o PAUSAR (quita el shader hasta recuperar batería).
 * No toca las preferencias del usuario: la resolución reducida es un factor en tiempo de ejecución que aplica OverlayManager.
 * ShaderCaptureService lo evalúa con cada cambio de batería mientras hay sesión.
 */
object BatterySaver {
    const val ACTION_REDUCE = 0
    const val ACTION_CAP = 1
    const val ACTION_PAUSE = 2
    private const val CAP_FPS = 30
    private const val HYSTERESIS = 3 // puntos de batería por encima del umbral para volver a la normalidad

    /** Factor de la resolución virtual (1 = normal). Lo lee OverlayManager al colocar el shader. */
    @Volatile var resolutionFactor = 1f
        private set
    @Volatile var active = false
        private set
    private var pausedByBattery = false

    /** Tope de FPS en vigor: el del usuario y, con el modo batería encendido (salvo PAUSAR), como mucho 30. */
    fun effectiveFpsCap(context: Context): Int {
        val user = PerfSettings.getFpsCap(context)
        if (!active || PerfSettings.getBatteryAction(context) == ACTION_PAUSE) return user
        return if (user == 0) CAP_FPS else minOf(user, CAP_FPS)
    }

    /** Lee el estado de la batería (el broadcast es pegajoso) y aplica o retira el modo. */
    fun reevaluate(context: Context) {
        evaluate(context, context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    /** Cambió un ajuste del modo: si estaba actuando, vuelve a la normalidad y se decide de nuevo con los valores nuevos. */
    fun restart(context: Context) {
        if (active) exit(context, announce = false)
        reevaluate(context)
    }

    fun evaluate(context: Context, battery: Intent?) {
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val enabled = PerfSettings.isBatteryMode(context) && ShaderCaptureService.isRunning
        val threshold = PerfSettings.getBatteryThreshold(context)

        if (!active && enabled && !charging && pct <= threshold) enter(context)
        else if (active && (!enabled || charging || pct >= threshold + HYSTERESIS)) exit(context)
    }

    private fun enter(context: Context) {
        active = true
        when (PerfSettings.getBatteryAction(context)) {
            ACTION_REDUCE -> resolutionFactor = 0.5f
            ACTION_PAUSE -> if (ShaderCaptureService.isCapturing) {
                pausedByBattery = true
                context.startService(Intent(context, ShaderCaptureService::class.java).apply { action = ShaderCaptureService.ACTION_STOP })
            }
        }
        apply()
        Toast.makeText(context, R.string.toast_battery_on, Toast.LENGTH_LONG).show()
    }

    private fun exit(context: Context, announce: Boolean = true) {
        active = false
        resolutionFactor = 1f
        if (pausedByBattery) {
            pausedByBattery = false
            if (ShaderCaptureService.state == ServiceState.PAUSED) {
                context.startService(Intent(context, ShaderCaptureService::class.java).apply { action = ShaderCaptureService.ACTION_START })
            }
        }
        apply()
        if (announce) Toast.makeText(context, R.string.toast_battery_off, Toast.LENGTH_SHORT).show()
    }

    private fun apply() {
        ShaderOverlayAccessibilityService.applyPerfSettings() // tope de FPS
        VulkanEngineBridge.refreshZone() // vuelve a mandar la resolución con el factor nuevo
    }

    /** La sesión terminó: nada que restaurar. */
    fun reset() {
        active = false
        resolutionFactor = 1f
        pausedByBattery = false
    }
}
