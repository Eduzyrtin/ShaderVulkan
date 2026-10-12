package com.sanabria.shadervulkan.services

import android.content.Context
import android.graphics.Rect
import com.sanabria.shadervulkan.model.PerfSettings
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Captura a resolución reducida (R12; retoma T52 con la zona en cuenta).
 *
 * El shader no ve la captura entera a resolución de pantalla: el motor la recorta a la zona y la reduce a la rejilla de la consola
 * (NES 256x224, Game Boy 160x144...). Capturar la pantalla completa a 2340x1080 para quedarse con 256x224 obliga al compositor a componer
 * en cada frame una pantalla entera para el VirtualDisplay (un pase de GPU más, que también paga el emulador) y al motor a leerla. Con
 * esto el VirtualDisplay mide lo justo para que cada zona tenga K píxeles de captura por píxel de su rejilla:
 *  - CALIDAD (K = 4, por defecto): el muestreo de la reducción a la rejilla cae dentro del mismo píxel retro que con la captura entera,
 *    así que la imagen que entra al shader no cambia. Ahorra donde la zona mide más de 4 veces la rejilla (consolas portátiles, 4:3 en
 *    horizontal...).
 *  - RENDIMIENTO (K = 2): ahorra mucho más; con escalados no enteros los bordes de los píxeles salen algo más suaves.
 *  - COMPLETA: como antes.
 * Con resolución "Auto" (la de la zona) no se reduce nada. El VirtualDisplay conserva la forma exacta de la pantalla (múltiplos de su
 * máximo común divisor) para que el sistema no añada bandas negras al escalar.
 *
 * OverlayManager.applyZone informa de la zona y la rejilla de cada salida; ShaderCaptureService recrea el VirtualDisplay cuando el
 * tamaño necesario cambia (como al girar).
 */
object CaptureScale {
    /** Lado mínimo del VirtualDisplay, para no degenerar con rejillas diminutas. */
    private const val MIN_SIDE = 64

    // Fracción de la pantalla (0..1) que necesita cada salida; 0 = la salida no lee la captura (no existe, o está en otra pantalla)
    private val needed = FloatArray(2)

    /** Avisa al servicio de captura de que el tamaño necesario puede haber cambiado (lo fija ShaderCaptureService). */
    @Volatile var listener: (() -> Unit)? = null

    /**
     * Zona (en píxeles de pantalla; null = pantalla completa) y rejilla (la que se manda al motor; 0 = "Auto") de una salida que lee la
     * captura. Lo llama OverlayManager.applyZone.
     */
    fun update(context: Context, output: Int, zone: Rect?, screenW: Int, screenH: Int, gridW: Float, gridH: Float) {
        if (output !in needed.indices) return
        val k = factor(context)
        val zw = (zone?.width() ?: screenW).toFloat()
        val zh = (zone?.height() ?: screenH).toFloat()
        val value = if (k <= 0f || gridW <= 0f || gridH <= 0f || zw <= 0f || zh <= 0f) {
            1f // sin reducción, o "Auto": la rejilla es la propia zona
        } else {
            // El motor orienta la rejilla como la zona (su lado largo sobre el lado largo de la zona)
            val gLong = max(gridW, gridH); val gShort = min(gridW, gridH)
            val zLong = max(zw, zh); val zShort = min(zw, zh)
            min(1f, k * max(gLong / zLong, gShort / zShort))
        }
        val changed = synchronized(needed) {
            val old = needed[output]
            needed[output] = value
            old != value
        }
        if (changed) listener?.invoke()
    }

    /** La salida deja de leer la captura (se quita su ventana o pasa a otra pantalla). */
    fun clear(output: Int) {
        if (output !in needed.indices) return
        val changed = synchronized(needed) {
            val old = needed[output]
            needed[output] = 0f
            old != 0f
        }
        if (changed) listener?.invoke()
    }

    /**
     * Tamaño del VirtualDisplay para una pantalla de [w] x [h]: la fracción más grande que pida alguna salida, redondeada hacia arriba a
     * un tamaño con la misma forma exacta que la pantalla.
     */
    fun captureSize(context: Context, w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0 || factor(context) <= 0f) return w to h
        val fraction = synchronized(needed) { needed.maxOrNull() ?: 0f }
        if (fraction <= 0f || fraction >= 1f) return w to h
        val g = gcd(w, h)
        val unitW = w / g
        val unitH = h / g
        val minUnits = ceil(MIN_SIDE.toFloat() / min(unitW, unitH)).toInt()
        val n = max(minUnits, ceil(fraction * g).toInt()).coerceAtMost(g)
        return unitW * n to unitH * n
    }

    /** Píxeles de captura por píxel de la rejilla según el ajuste; 0 = captura completa. */
    private fun factor(context: Context): Float = when (PerfSettings.getCaptureScale(context)) {
        PerfSettings.CAPTURE_QUALITY -> 4f
        PerfSettings.CAPTURE_PERFORMANCE -> 2f
        else -> 0f
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
