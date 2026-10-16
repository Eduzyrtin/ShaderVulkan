package com.sanabria.shadervulkan.model

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit

/**
 * Gestión persistente de los ajustes y del shader activo. El estado de la captura (activa, en pausa...) NO se guarda aquí:
 * vive en memoria en ShaderCaptureService.state (T14).
 */
object ShaderPreferences {
    private const val PREFS_NAME = "shader_vulkan_prefs"
    private const val KEY_ACTIVE_SHADER = "active_shader_id"
    private const val KEY_ACTIVE_SHADER_NAME = "active_shader_name"
    private const val KEY_SHADER_FOLDER = "shader_folder_uri"
    private const val KEY_RES_W = "res_width"
    private const val KEY_RES_H = "res_height"
    private const val KEY_RES_IDX = "res_idx"
    private const val KEY_RES_SCALE = "res_scale_val"
    private const val KEY_ASPECT_RATIO = "aspect_ratio_idx"
    private const val KEY_BTN_X = "control_btn_x"
    private const val KEY_BTN_Y = "control_btn_y"
    private const val KEY_AUTO_PAUSE_SHADE = "auto_pause_on_shade"
    private const val KEY_FLOATING_BUTTON = "floating_button"
    private const val KEY_CRT_PALETTE = "crt_palette"
    private const val KEY_CRT_GLOW = "crt_glow"
    private const val KEY_CRT_GRAIN = "crt_grain"
    private const val KEY_ZONE_PORTRAIT = "zone_portrait"
    private const val KEY_ZONE_LANDSCAPE = "zone_landscape"
    private const val KEY_DUAL = "dual_mode"
    private const val KEY_SCREENS = "screens_mode"
    private const val KEY_ZONE_B_SHADER = "zone_b_shader"
    private const val KEY_ZONE_B_SHADER_NAME = "zone_b_shader_name"
    private const val KEY_EDIT_ZONE = "edit_zone"
    private const val KEY_APP_PROFILES = "app_profiles_enabled"
    private const val KEY_ZONE2_PORTRAIT = "zone2_portrait"
    private const val KEY_ZONE2_LANDSCAPE = "zone2_landscape"
    private const val KEY_ZONE_B_PORTRAIT = "zone_b_portrait"
    private const val KEY_ZONE_B_LANDSCAPE = "zone_b_landscape"
    private const val KEY_BARS_PORTRAIT = "bars_portrait"
    private const val KEY_BARS_LANDSCAPE = "bars_landscape"
    private const val KEY_AUTO_SIDES_PORTRAIT = "auto_sides_portrait"
    private const val KEY_AUTO_SIDES_LANDSCAPE = "auto_sides_landscape"
    private const val KEY_ASPECT_BEFORE_AUTO = "aspect_before_auto"
    private const val KEY_GRID_ADAPT = "grid_adapt"
    private const val KEY_RENDER_MODE = "render_mode"
    private const val KEY_LAST_CONSOLE = "last_console_idx"

    /** La bienvenida (T63) ya se enseñó la primera vez. */
    fun isWelcomeSeen(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("welcome_seen", false)

    fun setWelcomeSeen(ctx: Context, seen: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean("welcome_seen", seen) }
    }

    /** Modo de funcionamiento (T31): 0 = Captura (el oficial), 1 = Glass (compatibilidad: capa sin captura). */
    fun getRenderMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_RENDER_MODE, 0).coerceIn(0, 1)

    fun setRenderMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_RENDER_MODE, mode.coerceIn(0, 1)) }
    }

    /** Pantalla principal en vertical: los selectores (CONTENT RES, ZONE, MODE) desplegados o plegados (por defecto, plegados). */
    fun isMainSettingsExpanded(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("main_settings_expanded", false)

    fun setMainSettingsExpanded(ctx: Context, expanded: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean("main_settings_expanded", expanded) }
    }

    /** Última consola elegida en CONTENT RES (para volver a ella al cambiar de AUTO/SCALE/CUSTOM a CONSOLE). */
    fun getLastConsole(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_LAST_CONSOLE, ConsoleResolutions.DEFAULT_INDEX)

    fun setLastConsole(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_LAST_CONSOLE, index) }
    }

    /** Suspender el shader mientras el panel de notificaciones está desplegado (activado por defecto). */
    fun isAutoPauseOnShade(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_AUTO_PAUSE_SHADE, true)

    fun setAutoPauseOnShade(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putBoolean(KEY_AUTO_PAUSE_SHADE, enabled)
            }
    }

    /**
     * Botón flotante (el punto) sí o no (activado por defecto). Sin él no hay forma de abrir el menú flotante: quedan la
     * notificación y la pantalla principal.
     */
    fun isFloatingButton(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_FLOATING_BUTTON, true)

    fun setFloatingButton(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean(KEY_FLOATING_BUTTON, enabled) }
    }

    // --- Apariencia (T60). La paleta es un índice (0..71 = tono cada 5°, 72 = neutro); -1 = la clásica de T09 ---

    fun getCrtPalette(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_CRT_PALETTE, -1)

    fun setCrtPalette(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_CRT_PALETTE, index) }
    }

    /** Intensidad del glow del texto: 0 apagado, 1 suave (por defecto), 2 fuerte. */
    fun getCrtGlow(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_CRT_GLOW, 1).coerceIn(0, 2)

    fun setCrtGlow(ctx: Context, level: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_CRT_GLOW, level.coerceIn(0, 2)) }
    }

    /** Grano de fósforo que tiembla en las pantallas de la app (activado por defecto; apagado ahorra batería). */
    fun isCrtGrain(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_CRT_GRAIN, true)

    fun setCrtGrain(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean(KEY_CRT_GRAIN, enabled) }
    }

    fun getControlButtonX(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_BTN_X, 0)

    fun getControlButtonY(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_BTN_Y, 200)

    fun setControlButtonPosition(ctx: Context, x: Int, y: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putInt(KEY_BTN_X, x)
                putInt(KEY_BTN_Y, y)
            }
    }

    fun getActiveShaderId(ctx: Context): String? {
        val id = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_SHADER, null)
        // Id antiguo de los presets de assets ("asset_shaders/x"): ahora es la propia ruta ("shaders/x"), B17
        if (id.isNullOrEmpty()) return null
        return if (id.startsWith("asset_shaders/")) "shaders/" + id.removePrefix("asset_shaders/") else id
    }

    fun getActiveShaderName(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_SHADER_NAME, "None")?.let { ShaderItem.cleanName(it) }
    }

    fun setActiveShaderId(ctx: Context, shaderId: String?, shaderName: String?) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putString(KEY_ACTIVE_SHADER, shaderId)
                putString(KEY_ACTIVE_SHADER_NAME, shaderName)
            }
    }

    fun getShaderFolderUri(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_SHADER_FOLDER, null)
    }

    fun setShaderFolderUri(ctx: Context, uri: String?) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putString(KEY_SHADER_FOLDER, uri)
            }
    }

    fun getVirtualResolution(ctx: Context): Pair<Float, Float> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return Pair(
            prefs.getFloat(KEY_RES_W, 0f),
            prefs.getFloat(KEY_RES_H, 0f)
        )
    }

    fun setVirtualResolution(ctx: Context, w: Float, h: Float) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putFloat(KEY_RES_W, w)
                putFloat(KEY_RES_H, h)
            }
    }

    fun getVirtualResolutionIndex(ctx: Context): Int {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_RES_IDX, 0)
    }

    fun setVirtualResolutionIndex(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putInt(KEY_RES_IDX, index)
            }
    }

    fun getScaleValue(ctx: Context): Float {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(KEY_RES_SCALE, 1.0f)
    }

    fun setScaleValue(ctx: Context, scale: Float) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putFloat(KEY_RES_SCALE, scale)
            }
    }

    fun getAspectRatioIndex(ctx: Context): Int {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_ASPECT_RATIO, 0)
    }

    fun setAspectRatioIndex(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putInt(KEY_ASPECT_RATIO, index)
            }
    }

    /**
     * Zona manual del shader (T06), una por orientación: fracciones 0..1 de la pantalla [izquierda, arriba, derecha, abajo].
     * null = sin zona definida para esa orientación (pantalla completa). Se usa cuando el selector está en "Manual".
     */
    fun getManualZone(ctx: Context, landscape: Boolean): FloatArray? {
        val s = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(if (landscape) KEY_ZONE_LANDSCAPE else KEY_ZONE_PORTRAIT, null) ?: return null
        return ShaderZone.parseFractions(s)
    }

    /**
     * Barras negras por lado (T08), una máscara por orientación: qué lados FUERA de la zona se pintan de negro encima de la
     * pantalla real (bits de ShaderZone.BAR_*). 0 = ninguna: fuera de la zona se ve la app, como siempre.
     */
    fun getBars(ctx: Context, landscape: Boolean): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(if (landscape) KEY_BARS_LANDSCAPE else KEY_BARS_PORTRAIT, 0) and ShaderZone.BAR_ALL

    fun setBars(ctx: Context, landscape: Boolean, mask: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putInt(if (landscape) KEY_BARS_LANDSCAPE else KEY_BARS_PORTRAIT, mask and ShaderZone.BAR_ALL)
        }
    }

    /**
     * Lados en los que la detección automática de bandas negras (T07) puede recortar, una máscara por orientación (bits de
     * ShaderZone.BAR_*). Por defecto, los cuatro. Los lados no marcados se quedan en el borde de la pantalla.
     */
    fun getAutoSides(ctx: Context, landscape: Boolean): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(if (landscape) KEY_AUTO_SIDES_LANDSCAPE else KEY_AUTO_SIDES_PORTRAIT, ShaderZone.BAR_ALL) and ShaderZone.BAR_ALL

    fun setAutoSides(ctx: Context, landscape: Boolean, mask: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putInt(if (landscape) KEY_AUTO_SIDES_LANDSCAPE else KEY_AUTO_SIDES_PORTRAIT, mask and ShaderZone.BAR_ALL)
        }
    }

    /**
     * Rejilla adaptada (solo con la zona "Manual" y una resolución fija): en vez de la rejilla exacta de la resolución, la
     * rejilla se ajusta a la forma de la zona para que el píxel conserve su forma (cuadrado en Game Boy, GBA y Custom).
     * Se elige en el editor de zona. Por defecto apagada: la resolución de la consola tal cual.
     */
    fun isGridAdapt(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_GRID_ADAPT, false)

    fun setGridAdapt(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean(KEY_GRID_ADAPT, enabled) }
    }

    /** Selector de zona antes de activar "Auto (detectar bandas)": el botón AUTO del menú vuelve a él al desactivarlo. */
    fun getAspectBeforeAuto(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_ASPECT_BEFORE_AUTO, 0)

    fun setAspectBeforeAuto(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_ASPECT_BEFORE_AUTO, index) }
    }

    /**
     * Modo dual (T16): dos zonas de shader a la vez (DS, 3DS). Con él activo las zonas salen de [getManualZone] (A) y
     * [getManualZoneB] (B), y el selector de aspecto no cuenta. Va en los perfiles, como la zona.
     */
    fun isDual(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_DUAL, false)

    fun setDual(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean(KEY_DUAL, enabled) }
    }

    // --- Preset propio de la zona B (T18) ---

    /** Preset de la zona B del modo dual; null = la B sigue al preset de la A (P2). */
    fun getZoneBShaderId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ZONE_B_SHADER, null)?.takeIf { it.isNotEmpty() }

    fun getZoneBShaderName(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ZONE_B_SHADER_NAME, null)?.let { ShaderItem.cleanName(it) }

    fun setZoneBShader(ctx: Context, shaderId: String?, name: String?) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_ZONE_B_SHADER, shaderId)
            putString(KEY_ZONE_B_SHADER_NAME, name)
        }
    }

    /** Zona cuyos ajustes se editan en el menú flotante y en SETTINGS: 0 = A, 1 = B. */
    fun getEditZone(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_EDIT_ZONE, 0)

    fun setEditZone(ctx: Context, zone: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_EDIT_ZONE, zone.coerceIn(0, 1)) }
    }

    /** Se está editando la zona B y esta tiene su propio preset (con modo dual): ajustes, perfiles y exportación son los de ese preset. */
    fun editsZoneB(ctx: Context): Boolean = isDual(ctx) && getEditZone(ctx) == 1 && getZoneBShaderId(ctx) != null

    /** Preset cuyos parámetros y perfiles se editan ahora: el de la zona B si se edita esa, y si no, el activo. */
    fun getEditShaderId(ctx: Context): String? = if (editsZoneB(ctx)) getZoneBShaderId(ctx) else getActiveShaderId(ctx)

    fun getEditShaderName(ctx: Context): String? = if (editsZoneB(ctx)) getZoneBShaderName(ctx) else getActiveShaderName(ctx)

    /** Perfiles por app (T61) activados: al abrir una app con perfil, el shader se pone solo. Por defecto sí. */
    fun isAppProfilesEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_APP_PROFILES, true)

    fun setAppProfilesEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean(KEY_APP_PROFILES, enabled) }
    }

    /**
     * Pantallas con shader (T17): [ScreenLayout.TOP] (la de siempre), [ScreenLayout.BOTTOM] (solo la segunda pantalla física) o
     * [ScreenLayout.BOTH]. Es del dispositivo, no del perfil; lo que vale de verdad lo decide [ScreenLayout.plan].
     */
    fun getScreens(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_SCREENS, ScreenLayout.TOP)

    fun setScreens(ctx: Context, screens: Int) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(KEY_SCREENS, screens) }
    }

    /**
     * Zona del shader en la segunda pantalla (T17), una por orientación de esa pantalla (fracciones 0..1). null = sin dibujar:
     * se usa el aspecto del selector, como en la pantalla principal. Se guarda aunque ocupe todo (0,0,1,1) cuando se eligió FULL.
     */
    fun getSecondZone(ctx: Context, landscape: Boolean): FloatArray? {
        val s = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(if (landscape) KEY_ZONE2_LANDSCAPE else KEY_ZONE2_PORTRAIT, null) ?: return null
        return ShaderZone.parseFractions(s)
    }

    fun setSecondZone(ctx: Context, landscape: Boolean, zone: FloatArray?) {
        val key = if (landscape) KEY_ZONE2_LANDSCAPE else KEY_ZONE2_PORTRAIT
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            if (zone == null) remove(key) else putString(key, ShaderZone.formatFractions(zone))
        }
    }

    /** Zona B del modo dual, una por orientación (fracciones 0..1). null = sin definir: se usa la forma inicial según la resolución. */
    fun getManualZoneB(ctx: Context, landscape: Boolean): FloatArray? {
        val s = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(if (landscape) KEY_ZONE_B_LANDSCAPE else KEY_ZONE_B_PORTRAIT, null) ?: return null
        return ShaderZone.parseFractions(s)
    }

    fun setManualZoneB(ctx: Context, landscape: Boolean, zone: FloatArray?) {
        val key = if (landscape) KEY_ZONE_B_LANDSCAPE else KEY_ZONE_B_PORTRAIT
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            if (zone == null) remove(key) else putString(key, ShaderZone.formatFractions(zone))
        }
    }

    /**
     * Resolución de la zona B: la segunda de la consola elegida si tiene (3DS: 320x240); con cualquier otra, la misma rejilla
     * que la A.
     */
    fun getVirtualResolutionB(ctx: Context): Pair<Float, Float> {
        val entry = ConsoleResolutions.byIndex(getVirtualResolutionIndex(ctx))
        if (entry != null && entry.w2 > 0 && entry.h2 > 0) return entry.w2.toFloat() to entry.h2.toFloat()
        return getVirtualResolution(ctx)
    }

    fun setManualZone(ctx: Context, landscape: Boolean, zone: FloatArray?) {
        val key = if (landscape) KEY_ZONE_LANDSCAPE else KEY_ZONE_PORTRAIT
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            if (zone == null) remove(key) else putString(key, ShaderZone.formatFractions(zone))
        }
    }
}
