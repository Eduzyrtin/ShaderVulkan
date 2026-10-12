package com.sanabria.shadervulkan.model

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.sanabria.shadervulkan.services.SessionMode
import com.sanabria.shadervulkan.services.ShaderCaptureService

/**
 * En qué pantallas físicas se pone el shader (T17, Ayn Thor y consolas con dos pantallas).
 *
 * La captura (MediaProjection) solo ve el display 0, así que la segunda pantalla solo admite Glass. El plan sale de la
 * preferencia, del modo de la sesión y de los displays que existan en este momento.
 */
object ScreenLayout {
    private const val TAG = "SVDIAG_SCREENS"

    const val TOP = 0      // display 0: la pantalla de siempre
    const val BOTTOM = 1   // solo la segunda pantalla
    const val BOTH = 2     // las dos

    /** La segunda pantalla física, si hay: un display que no es el 0, ni privado (los espejos de MediaProjection lo son) ni apagado. */
    fun secondDisplay(ctx: Context): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRIVATE) == 0 && it.state != Display.STATE_OFF
        }
    }

    /** Hay una segunda pantalla y el sistema sabe poner ventanas en ella (API 30+). */
    fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && secondDisplay(ctx) != null

    /**
     * Pantallas que valen ahora: la elegida, salvo que la sesión no sea Glass (la captura no llega a la segunda pantalla), no
     * haya segunda pantalla o esté activo el modo dual de zonas (que usa las dos salidas en la pantalla de siempre).
     */
    fun effective(ctx: Context): Int {
        val wanted = ShaderPreferences.getScreens(ctx)
        if (wanted == TOP) return TOP
        if (ShaderPreferences.isDual(ctx)) return TOP
        if (ShaderCaptureService.sessionMode != SessionMode.GLASS) return TOP
        if (!available(ctx)) return TOP
        return wanted
    }

    /**
     * Display de cada salida del motor, en orden: una entrada por ventana de shader. Salida 0 y, con modo dual de zonas o con
     * las dos pantallas, salida 1.
     */
    fun plan(ctx: Context): List<Int> {
        val second = secondDisplay(ctx)?.displayId ?: Display.DEFAULT_DISPLAY
        return when (effective(ctx)) {
            BOTTOM -> listOf(second)
            BOTH -> listOf(Display.DEFAULT_DISPLAY, second)
            else -> if (ShaderPreferences.isDual(ctx)) listOf(Display.DEFAULT_DISPLAY, Display.DEFAULT_DISPLAY) else listOf(Display.DEFAULT_DISPLAY)
        }
    }

    /**
     * Contexto con el que se ponen ventanas del servicio de accesibilidad en ese display (API 30+). Para el 0 es el propio
     * servicio. Si el sistema no da el contexto de ventana de accesibilidad en el display secundario (fallo conocido de AOSP,
     * PROYECTO.md §12 T17) se cae al contexto del display a secas y se deja constancia en el log.
     */
    fun windowContext(service: Context, displayId: Int): Context {
        if (displayId == Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return service
        val dm = service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return service
        return try {
            val displayCtx = service.createDisplayContext(display)
            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            } else {
                displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            }
            Log.i(TAG, "Contexto de ventana de accesibilidad para el display $displayId (${display.name})")
            ctx
        } catch (e: Exception) {
            Log.e(TAG, "createWindowContext falló en el display $displayId (${e.message}); se usa createDisplayContext", e)
            service.createDisplayContext(display)
        }
    }

    /** Paso 0 de T17 (spike en el Thor): deja en el log todos los displays para anotar lo que salga en DOCUMENTACION.md §17. */
    fun logDisplays(ctx: Context) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (d in dm.displays) {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(m)
            Log.i(TAG, "display ${d.displayId} \"${d.name}\": ${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} " +
                    "flags=0x${Integer.toHexString(d.flags)} estado=${d.state} rotación=${d.rotation}")
        }
        Log.i(TAG, "segunda pantalla: ${secondDisplay(ctx)?.displayId ?: "ninguna"}; pantallas efectivas=${effective(ctx)} plan=${plan(ctx)}")
    }
}
