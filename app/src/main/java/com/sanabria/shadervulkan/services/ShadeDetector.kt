package com.sanabria.shadervulkan.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo

/**
 * ShadeDetector — Detecta si el panel de notificaciones / ajustes rápidos está desplegado.
 *
 * Regla (a validar en el S24 y en el Thor con los logs SVDIAG_SHADE): SystemUI mantiene una ventana
 * de tipo TYPE_SYSTEM (la "NotificationShade") que, recogida, solo mide lo que la barra de estado y,
 * desplegada, ocupa (casi) toda la pantalla. Se considera abierto el panel cuando existe una ventana
 * TYPE_SYSTEM de SystemUI con una altura >= SHADE_MIN_HEIGHT_FRACTION de la pantalla.
 * Efectos conocidos: una notificación emergente (heads-up) y la pantalla de bloqueo también agrandan
 * esa ventana, así que cuentan como "abierto".
 *
 * Abrir es inmediato (el toque no debe llegar al panel sin que se vea); cerrar espera CLOSE_DEBOUNCE_MS
 * para no parpadear mientras se arrastra el panel.
 */
class ShadeDetector(
    private val service: AccessibilityService,
    private val onChange: (open: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "SVDIAG_SHADE"
        private const val SHADE_MIN_HEIGHT_FRACTION = 0.5f
        private const val CLOSE_DEBOUNCE_MS = 120L
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        // Spike T11: vuelca las ventanas TYPE_SYSTEM en cada evento. Desactivar cuando la regla esté validada.
        private const val LOG_ALL_EVENTS = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bounds = Rect()

    var isOpen = false
        private set

    private val confirmClose = Runnable {
        if (isOpen && !isShadeWindowPresent { "confirmClose" }) {
            isOpen = false
            Log.i(TAG, "Panel CERRADO")
            onChange(false)
        }
    }

    /** Llamar en cada TYPE_WINDOWS_CHANGED / TYPE_WINDOW_STATE_CHANGED. [reason] solo se evalúa con LOG_ALL_EVENTS (R08). */
    fun onWindowsEvent(reason: () -> String) {
        if (isShadeWindowPresent(reason)) {
            handler.removeCallbacks(confirmClose)
            if (!isOpen) {
                isOpen = true
                Log.i(TAG, "Panel ABIERTO")
                onChange(true)
            }
        } else if (isOpen) {
            handler.removeCallbacks(confirmClose)
            handler.postDelayed(confirmClose, CLOSE_DEBOUNCE_MS)
        }
    }

    /** Fuerza el estado a "cerrado" (p. ej. al desactivar la función) y cancela cierres pendientes. */
    fun reset() {
        handler.removeCallbacks(confirmClose)
        isOpen = false
    }

    private fun isShadeWindowPresent(reason: () -> String): Boolean {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer las ventanas: ${e.message}")
            return false
        }
        // maximumWindowMetrics es de Android 11; en Android 10 (minSdk) se cerraría la app
        val screenHeight = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.heightPixels
        }
        val minHeight = (screenHeight * SHADE_MIN_HEIGHT_FRACTION).toInt()

        var found = false
        val dump = if (LOG_ALL_EVENTS) StringBuilder() else null
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(bounds)
            val tall = bounds.height() >= minHeight
            // R08: el paquete solo importa en las ventanas altas. getRoot() es una llamada entre procesos que hace trabajar al hilo de
            // SystemUI; antes se pedía en cada evento para todas (también la barra de estado y la de navegación, que nunca cuentan).
            var pkg: String? = null
            if (tall || dump != null) {
                @Suppress("DEPRECATION")
                val root = w.root
                pkg = root?.packageName?.toString()
                @Suppress("DEPRECATION")
                root?.recycle()
            }

            // Si el paquete no se puede leer, se acepta; si se puede, tiene que ser SystemUI.
            val matches = tall && (pkg == null || pkg == SYSTEMUI_PACKAGE)
            if (matches) found = true
            dump?.append("\n   [layer=${w.layer} title=${w.title} pkg=$pkg bounds=${bounds.flattenToString()} h=${bounds.height()} match=$matches]")
        }
        if (dump != null) {
            Log.d(TAG, "${reason()} -> abierto=$found (minH=$minHeight, pantallaH=$screenHeight)$dump")
        }
        return found
    }
}
