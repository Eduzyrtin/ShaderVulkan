package com.sanabria.shadervulkan.ui

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderPreferences

/**
 * Paleta de color de la interfaz (T60). Los colores no son recursos @color sino atributos del tema (?attr/crtPhosphor, crtHot...)
 * y cada paleta es un estilo que se aplica encima del tema: [applyTo]. Las 73 paletas (72 tonos cada 5° + neutro) las genera
 * tools/gen-crt-palettes.ps1 en res/values/crt_palettes.xml, junto a los colores representativos y los índices de los atajos.
 *
 * El índice de paleta que se guarda en ShaderPreferences es el de ese fichero: 0..71 = tono, [neutralIndex] = neutro.
 */
object CrtTheme {

    /** Atajo con nombre en Ajustes; [palette] es el recurso entero con su índice de paleta. */
    class Preset(@param:StringRes val label: Int, @param:IntegerRes val palette: Int)

    val PRESETS = listOf(
        Preset(R.string.preset_classic, R.integer.crt_preset_classic),
        Preset(R.string.preset_amber, R.integer.crt_preset_amber),
        Preset(R.string.preset_red, R.integer.crt_preset_red),
        Preset(R.string.preset_magenta, R.integer.crt_preset_magenta),
        Preset(R.string.preset_ice, R.integer.crt_preset_ice),
        Preset(R.string.preset_cyan, R.integer.crt_preset_cyan),
        Preset(R.string.preset_neutral, R.integer.crt_hue_neutral),
    )

    /** Número de tonos de la barra de arcoíris (el neutro no está en ella). */
    fun hueSteps(ctx: Context): Int = ctx.resources.getInteger(R.integer.crt_hue_steps)

    fun neutralIndex(ctx: Context): Int = ctx.resources.getInteger(R.integer.crt_hue_neutral)

    /** Paleta guardada; si nunca se ha elegido, la clásica (la de T09). */
    fun paletteIndex(ctx: Context): Int {
        val saved = ShaderPreferences.getCrtPalette(ctx)
        return if (saved in 0..neutralIndex(ctx)) saved else ctx.resources.getInteger(R.integer.crt_preset_classic)
    }

    /** Estilo (CrtPalette_H*) de una paleta. */
    fun paletteStyle(ctx: Context, index: Int): Int {
        val styles = ctx.resources.obtainTypedArray(R.array.crt_palette_styles)
        try {
            return styles.getResourceId(index.coerceIn(0, styles.length() - 1), 0)
        } finally {
            styles.recycle()
        }
    }

    private fun glowStyle(level: Int): Int = when (level) {
        0 -> R.style.CrtGlow_Off
        2 -> R.style.CrtGlow_Strong
        else -> R.style.CrtGlow_Soft
    }

    /** Aplica al tema de [ctx] la paleta y el glow guardados. Hay que hacerlo antes de crear ninguna vista (ni la ventana). */
    fun applyTo(ctx: Context) {
        ctx.theme.applyStyle(paletteStyle(ctx, paletteIndex(ctx)), true)
        ctx.theme.applyStyle(glowStyle(ShaderPreferences.getCrtGlow(ctx)), true)
    }

    /** Contexto con el tema de la app y una paleta candidata (la vista previa de Ajustes) con el glow guardado. */
    fun previewContext(base: Context, palette: Int): Context =
        ContextThemeWrapper(base, R.style.Theme_ShaderVulkan).also {
            it.theme.applyStyle(paletteStyle(base, palette), true)
            it.theme.applyStyle(glowStyle(ShaderPreferences.getCrtGlow(base)), true)
        }

    /**
     * Cambia si cambia la paleta o el glow: las pantallas abiertas la comparan al volver a primer plano y se recrean si ya no
     * coincide (CrtActivity).
     */
    fun signature(ctx: Context): Int = paletteIndex(ctx) * 3 + ShaderPreferences.getCrtGlow(ctx)

    /** Color representativo de cada paleta (el rol "tenue", el más vivo): la barra de arcoíris y los atajos. */
    fun swatches(ctx: Context): IntArray {
        val colors = ctx.resources.obtainTypedArray(R.array.crt_hue_swatches)
        try {
            return IntArray(colors.length()) { colors.getColor(it, 0) }
        } finally {
            colors.recycle()
        }
    }

    /** Valor de un atributo de color del tema de [ctx] (el que dé la paleta aplicada). */
    fun color(ctx: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        ctx.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /** Valor de un atributo numérico del tema (los radios del glow, en píxeles). */
    fun number(ctx: Context, @AttrRes attr: Int): Float {
        val value = TypedValue()
        ctx.theme.resolveAttribute(attr, value, true)
        return if (value.type == TypedValue.TYPE_FLOAT) value.float else value.data.toFloat()
    }
}
