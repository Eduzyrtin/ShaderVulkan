package com.sanabria.shadervulkan.model

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit

/**
 * Ajustes de rendimiento y de uso (T66): el contador de FPS (esquina, tamaño, opacidad, qué muestra, intervalo, solo con el menú
 * abierto, aviso de frames perdidos), el tope de FPS de salida y el aspecto del botón flotante (tamaño y opacidad en reposo).
 */
object PerfSettings {
    private const val PREFS_NAME = "shader_vulkan_prefs"

    // Esquinas del contador
    const val TOP_LEFT = 0
    const val TOP_RIGHT = 1
    const val BOTTOM_LEFT = 2
    const val BOTTOM_RIGHT = 3

    /** Tope de FPS de salida que ofrece la interfaz (0 = sin tope). */
    val FPS_CAPS = listOf(0, 24, 30, 45, 60)
    /** Intervalo de refresco del contador en ms. */
    val INTERVALS_MS = listOf(500L, 1000L)
    /** Umbrales de batería que ofrece el modo batería. */
    val BATTERY_THRESHOLDS = listOf(10, 15, 20, 30)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    private fun getInt(ctx: Context, key: String, def: Int, min: Int, max: Int) = prefs(ctx).getInt(key, def).coerceIn(min, max)
    private fun putInt(ctx: Context, key: String, v: Int, min: Int, max: Int) { prefs(ctx).edit { putInt(key, v.coerceIn(min, max)) } }

    fun isFpsCounter(ctx: Context) = prefs(ctx).getBoolean("fps_counter", false)
    fun setFpsCounter(ctx: Context, on: Boolean) { prefs(ctx).edit { putBoolean("fps_counter", on) } }

    /** Esquina de la pantalla (TOP_LEFT..BOTTOM_RIGHT). */
    fun getFpsCorner(ctx: Context) = getInt(ctx, "fps_corner", TOP_LEFT, 0, 3)
    fun setFpsCorner(ctx: Context, v: Int) = putInt(ctx, "fps_corner", v, 0, 3)

    /** Tamaño del texto: 0 pequeño (por defecto), 1 mediano, 2 grande. */
    fun getFpsSize(ctx: Context) = getInt(ctx, "fps_size", 0, 0, 2)
    fun setFpsSize(ctx: Context, v: Int) = putInt(ctx, "fps_size", v, 0, 2)

    /** Opacidad del contador en tanto por ciento (30..100). */
    fun getFpsOpacity(ctx: Context) = getInt(ctx, "fps_opacity", 80, 30, 100)
    fun setFpsOpacity(ctx: Context, v: Int) = putInt(ctx, "fps_opacity", v, 30, 100)

    /** Qué muestra: 0 = entrada y salida; 1 = además ms de frame y frames perdidos. */
    fun getFpsDetail(ctx: Context) = getInt(ctx, "fps_detail", 0, 0, 1)
    fun setFpsDetail(ctx: Context, v: Int) = putInt(ctx, "fps_detail", v, 0, 1)

    /** Índice en [INTERVALS_MS]. */
    fun getFpsInterval(ctx: Context) = getInt(ctx, "fps_interval", 1, 0, INTERVALS_MS.lastIndex)
    fun setFpsInterval(ctx: Context, v: Int) = putInt(ctx, "fps_interval", v, 0, INTERVALS_MS.lastIndex)

    /** El contador solo se ve mientras el menú flotante está abierto. */
    fun isFpsOnlyWithMenu(ctx: Context) = prefs(ctx).getBoolean("fps_only_menu", false)
    fun setFpsOnlyWithMenu(ctx: Context, on: Boolean) { prefs(ctx).edit { putBoolean("fps_only_menu", on) } }

    /** Añade UI: el refresco al que va realmente la pantalla (Choreographer), para compararlo con la entrada. */
    fun isFpsShowUi(ctx: Context) = prefs(ctx).getBoolean("fps_show_ui", true)
    fun setFpsShowUi(ctx: Context, on: Boolean) { prefs(ctx).edit { putBoolean("fps_show_ui", on) } }

    /** El contador se pone en rojo cuando la salida se queda por debajo de la entrada de forma sostenida. */
    fun isFpsLostWarning(ctx: Context) = prefs(ctx).getBoolean("fps_lost_warn", true)
    fun setFpsLostWarning(ctx: Context, on: Boolean) { prefs(ctx).edit { putBoolean("fps_lost_warn", on) } }

    /** Tope de FPS de salida (0 = sin tope). */
    fun getFpsCap(ctx: Context): Int = prefs(ctx).getInt("fps_cap", 0).takeIf { it in FPS_CAPS } ?: 0
    fun setFpsCap(ctx: Context, fps: Int) { prefs(ctx).edit { putInt("fps_cap", fps) } }

    // Modo batería (T71)
    fun isBatteryMode(ctx: Context) = prefs(ctx).getBoolean("battery_mode", false)
    fun setBatteryMode(ctx: Context, on: Boolean) { prefs(ctx).edit { putBoolean("battery_mode", on) } }

    /** Porcentaje de batería por debajo del cual actúa (ver [BATTERY_THRESHOLDS]). */
    fun getBatteryThreshold(ctx: Context): Int = prefs(ctx).getInt("battery_threshold", 20).takeIf { it in BATTERY_THRESHOLDS } ?: 20
    fun setBatteryThreshold(ctx: Context, pct: Int) { prefs(ctx).edit { putInt("battery_threshold", pct) } }

    /** Qué hace: 0 reducir (mitad de resolución y 30 FPS), 1 solo 30 FPS, 2 pausar el shader. */
    fun getBatteryAction(ctx: Context) = getInt(ctx, "battery_action", 0, 0, 2)
    fun setBatteryAction(ctx: Context, v: Int) = putInt(ctx, "battery_action", v, 0, 2)

    // Captura a resolución reducida (R12, ver services/CaptureScale)
    const val CAPTURE_FULL = 0
    const val CAPTURE_QUALITY = 1
    const val CAPTURE_PERFORMANCE = 2

    /** 0 = siempre a resolución de pantalla; 1 = calidad (4 píxeles de captura por píxel de la rejilla, por defecto); 2 = rendimiento (2). */
    fun getCaptureScale(ctx: Context) = getInt(ctx, "capture_scale", CAPTURE_QUALITY, CAPTURE_FULL, CAPTURE_PERFORMANCE)
    fun setCaptureScale(ctx: Context, v: Int) = putInt(ctx, "capture_scale", v, CAPTURE_FULL, CAPTURE_PERFORMANCE)

    /** Tamaño del punto del botón flotante: 0 pequeño, 1 mediano (por defecto), 2 grande. */
    fun getButtonSize(ctx: Context) = getInt(ctx, "button_size", 1, 0, 2)
    fun setButtonSize(ctx: Context, v: Int) = putInt(ctx, "button_size", v, 0, 2)

    /** Opacidad del punto del botón flotante, en tanto por ciento (5..100); 27 es el aspecto de siempre (el apagado, un 60 % de eso). */
    fun getButtonRestOpacity(ctx: Context) = getInt(ctx, "button_rest_opacity", 27, 5, 100)
    fun setButtonRestOpacity(ctx: Context, v: Int) = putInt(ctx, "button_rest_opacity", v, 5, 100)
}
