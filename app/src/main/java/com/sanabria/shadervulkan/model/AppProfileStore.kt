package com.sanabria.shadervulkan.model

import android.content.Context
import org.json.JSONObject

/**
 * Perfiles por app (T61): qué hacer cuando una app concreta pasa a primer plano. Cada perfil dice el modo (Captura, Glass o sin
 * shader), el shader y el perfil de ajustes de ese shader (parámetros, resolución, zona, barras y modo dual: ver
 * [ShaderParamStore]) y, para dispositivos de dos pantallas, en cuáles va y la zona de la segunda.
 */
object AppProfileStore {
    private const val PREFS_NAME = "app_profiles_store"
    private const val KEY_ALL = "profiles"

    /** Clave del perfil por defecto (T60): se aplica a toda app que no tenga el suyo. No es un paquete real. */
    const val DEFAULT_KEY = "*"

    /** Qué hacer con el shader en esa app. */
    enum class Mode {
        CAPTURE,  // captura la pantalla (pide el permiso de compartir cada vez que se abre la app)
        GLASS,    // capa encima de la pantalla, sin permiso
        OFF;      // el shader se oculta mientras esa app está delante

        companion object {
            fun parse(s: String?): Mode = values().firstOrNull { it.name == s } ?: GLASS
        }
    }

    data class AppProfile(
        val packageName: String,
        val mode: Mode,
        val shaderId: String,              // id == ruta del preset (assets o URI de la carpeta externa)
        val shaderName: String,
        val profileName: String?,          // perfil de ajustes del shader (ShaderParamStore); null = el activo
        val screens: Int = ScreenLayout.TOP,
        val zone2Portrait: FloatArray? = null,   // zona en la segunda pantalla (T17)
        val zone2Landscape: FloatArray? = null,
        val zoneBShaderId: String? = null,        // preset propio de la zona B del modo dual (T18); null = el mismo que la A
        val zoneBShaderName: String? = null,
        val zoneBProfile: String? = null          // perfil de ajustes de ese preset
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY_ALL, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun get(ctx: Context, packageName: String): AppProfile? {
        val j = readAll(ctx).optJSONObject(packageName) ?: return null
        val mode = Mode.parse(j.optString("mode"))
        val shaderId = j.optString("shader", "")
        if (shaderId.isEmpty() && mode != Mode.OFF) return null // sin shader solo vale el perfil "sin shader"
        return AppProfile(
            packageName = packageName,
            mode = mode,
            shaderId = shaderId,
            shaderName = j.optString("shader_name", shaderId.substringAfterLast('/').substringBeforeLast('.')),
            profileName = j.optString("profile", "").takeIf { it.isNotEmpty() },
            screens = j.optInt("screens", ScreenLayout.TOP),
            zone2Portrait = j.optString("zone2_p", "").takeIf { it.isNotEmpty() }?.let { ShaderZone.parseFractions(it) },
            zone2Landscape = j.optString("zone2_l", "").takeIf { it.isNotEmpty() }?.let { ShaderZone.parseFractions(it) },
            zoneBShaderId = j.optString("zb_shader", "").takeIf { it.isNotEmpty() },
            zoneBShaderName = j.optString("zb_name", "").takeIf { it.isNotEmpty() },
            zoneBProfile = j.optString("zb_profile", "").takeIf { it.isNotEmpty() }
        )
    }

    fun all(ctx: Context): List<AppProfile> {
        val root = readAll(ctx)
        return root.keys().asSequence().mapNotNull { get(ctx, it) }.toList()
    }

    fun save(ctx: Context, p: AppProfile) {
        val root = readAll(ctx)
        val j = JSONObject()
        j.put("mode", p.mode.name)
        j.put("shader", p.shaderId)
        j.put("shader_name", p.shaderName)
        p.profileName?.let { j.put("profile", it) }
        if (p.screens != ScreenLayout.TOP) j.put("screens", p.screens)
        p.zone2Portrait?.let { j.put("zone2_p", ShaderZone.formatFractions(it)) }
        p.zone2Landscape?.let { j.put("zone2_l", ShaderZone.formatFractions(it)) }
        p.zoneBShaderId?.let { j.put("zb_shader", it) }
        p.zoneBShaderName?.let { j.put("zb_name", it) }
        p.zoneBProfile?.let { j.put("zb_profile", it) }
        root.put(p.packageName, j)
        prefs(ctx).edit().putString(KEY_ALL, root.toString()).apply()
    }

    /** Se renombró un perfil de ajustes de un shader (T60): los perfiles de app que lo usaban pasan a usar el nombre nuevo. */
    fun renameProfileRefs(ctx: Context, shaderId: String, oldName: String, newName: String) {
        val root = readAll(ctx)
        var changed = false
        for (pkg in root.keys().asSequence().toList()) {
            val j = root.optJSONObject(pkg) ?: continue
            if (j.optString("shader") == shaderId && j.optString("profile") == oldName) { j.put("profile", newName); changed = true }
            if (j.optString("zb_shader") == shaderId && j.optString("zb_profile") == oldName) { j.put("zb_profile", newName); changed = true }
        }
        if (changed) prefs(ctx).edit().putString(KEY_ALL, root.toString()).apply()
    }

    fun delete(ctx: Context, packageName: String) {
        val root = readAll(ctx)
        root.remove(packageName)
        prefs(ctx).edit().putString(KEY_ALL, root.toString()).apply()
    }
}
