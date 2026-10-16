package com.sanabria.shadervulkan.model

import android.content.Context
import androidx.core.content.edit

/**
 * Parámetros favoritos de cada shader (T74): lo que se marca con la estrella en Ajustes del shader o en el menú flotante. El menú
 * puede mostrar solo los favoritos, para no recorrer 40 sliders. Van por shader (su id, que en un conjunto de presets es el del
 * conjunto) y no dependen del perfil de ajustes activo.
 */
object ParamFavorites {
    private const val PREFS = "shader_param_favorites"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, shaderId: String): Set<String> =
        prefs(ctx).getStringSet("fav|$shaderId", emptySet()) ?: emptySet()

    /** Marca o desmarca [param]; devuelve si queda como favorito. */
    fun toggle(ctx: Context, shaderId: String, param: String): Boolean {
        val set = get(ctx, shaderId).toMutableSet() // copia: el conjunto que devuelve SharedPreferences no se puede modificar
        val nowFav = set.add(param) || !set.remove(param)
        prefs(ctx).edit { putStringSet("fav|$shaderId", set) }
        return nowFav
    }

    /** El menú flotante muestra solo los favoritos de este shader. */
    fun isOnlyFavorites(ctx: Context, shaderId: String): Boolean = prefs(ctx).getBoolean("only|$shaderId", false)

    fun setOnlyFavorites(ctx: Context, shaderId: String, on: Boolean) {
        prefs(ctx).edit { putBoolean("only|$shaderId", on) }
    }
}
