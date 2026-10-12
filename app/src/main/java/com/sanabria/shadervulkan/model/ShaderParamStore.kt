package com.sanabria.shadervulkan.model

import android.content.Context
import org.json.JSONObject

/**
 * Persistencia de perfiles de ajustes específicos para cada shader.
 */
object ShaderParamStore {
    private const val PREFS_NAME = "shader_profiles_store"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"

    private const val KEY_RES_W = "res_w"
    private const val KEY_RES_H = "res_h"
    private const val KEY_RES_IDX = "res_idx"
    private const val KEY_RES_SCALE = "res_scale"
    private const val KEY_ASPECT = "aspect_idx"
    private const val KEY_ZONE_P = "zone_portrait"
    private const val KEY_ZONE_L = "zone_landscape"
    private const val KEY_BARS_P = "bars_portrait"
    private const val KEY_BARS_L = "bars_landscape"
    private const val KEY_AUTO_SIDES_P = "auto_sides_portrait"
    private const val KEY_AUTO_SIDES_L = "auto_sides_landscape"
    private const val KEY_GRID_ADAPT = "grid_adapt"
    private const val KEY_DUAL = "dual"
    private const val KEY_ZONE_B_P = "zone_b_portrait"
    private const val KEY_ZONE_B_L = "zone_b_landscape"

    /**
     * Guarda el ajuste actual como un perfil con nombre, incluyendo resolución y aspecto.
     */
    fun saveProfile(
        ctx: Context,
        shaderId: String,
        profileName: String,
        params: Map<String, Float>,
        resW: Float = 0f,
        resH: Float = 0f,
        resIdx: Int = 0,
        resScale: Float = 1.0f,
        aspectIdx: Int = 0,
        zonePortrait: FloatArray? = null,   // zona manual (fracciones l,t,r,b) para vertical; null = no definida
        zoneLandscape: FloatArray? = null,  // idem para horizontal
        barsPortrait: Int = 0,              // barras negras por lado fuera de la zona (bits de ShaderZone.BAR_*), vertical
        barsLandscape: Int = 0,             // idem para horizontal
        autoSidesPortrait: Int = ShaderZone.BAR_ALL,   // lados en los que la detección de bandas puede recortar (vertical)
        autoSidesLandscape: Int = ShaderZone.BAR_ALL,  // idem para horizontal
        gridAdapt: Boolean = false,                    // rejilla adaptada a la forma de la zona manual
        dual: Boolean = false,                         // modo dual (T16): dos zonas, A y B
        zoneBPortrait: FloatArray? = null,             // zona B del modo dual para vertical
        zoneBLandscape: FloatArray? = null             // idem para horizontal
    ) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dataJson = prefs.getString(shaderId, "{}") ?: "{}"
        try {
            val root = JSONObject(dataJson)
            val profiles = root.optJSONObject(KEY_PROFILES) ?: JSONObject()
            
            val profileJson = JSONObject()
            
            // Guardar parámetros del shader
            val paramJson = JSONObject()
            params.forEach { (name, value) ->
                paramJson.put(name, value.toDouble())
            }
            profileJson.put("params", paramJson)
            
            // Guardar ajustes de resolución y aspecto
            profileJson.put(KEY_RES_W, resW.toDouble())
            profileJson.put(KEY_RES_H, resH.toDouble())
            profileJson.put(KEY_RES_IDX, resIdx)
            profileJson.put(KEY_RES_SCALE, resScale.toDouble())
            profileJson.put(KEY_ASPECT, aspectIdx)
            zonePortrait?.let { profileJson.put(KEY_ZONE_P, ShaderZone.formatFractions(it)) }
            zoneLandscape?.let { profileJson.put(KEY_ZONE_L, ShaderZone.formatFractions(it)) }
            if (barsPortrait != 0) profileJson.put(KEY_BARS_P, barsPortrait)
            if (barsLandscape != 0) profileJson.put(KEY_BARS_L, barsLandscape)
            if (autoSidesPortrait != ShaderZone.BAR_ALL) profileJson.put(KEY_AUTO_SIDES_P, autoSidesPortrait)
            if (autoSidesLandscape != ShaderZone.BAR_ALL) profileJson.put(KEY_AUTO_SIDES_L, autoSidesLandscape)
            if (gridAdapt) profileJson.put(KEY_GRID_ADAPT, true)
            if (dual) profileJson.put(KEY_DUAL, true)
            zoneBPortrait?.let { profileJson.put(KEY_ZONE_B_P, ShaderZone.formatFractions(it)) }
            zoneBLandscape?.let { profileJson.put(KEY_ZONE_B_L, ShaderZone.formatFractions(it)) }

            profiles.put(profileName, profileJson)
            root.put(KEY_PROFILES, profiles)
            root.put(KEY_ACTIVE, profileName)
            
            prefs.edit().putString(shaderId, root.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Obtiene la lista de nombres de perfiles para un shader.
     */
    fun getProfileNames(ctx: Context, shaderId: String): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dataJson = prefs.getString(shaderId, "{}") ?: "{}"
        val names = mutableListOf<String>()
        try {
            val root = JSONObject(dataJson)
            val profiles = root.optJSONObject(KEY_PROFILES) ?: return emptyList()
            val keys = profiles.keys()
            while (keys.hasNext()) {
                names.add(keys.next())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return names.sorted()
    }

    /**
     * Obtiene los datos completos de un perfil.
     */
    fun getProfileData(ctx: Context, shaderId: String, profileName: String): ProfileData? {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dataJson = prefs.getString(shaderId, "{}") ?: "{}"
        try {
            val root = JSONObject(dataJson)
            val profiles = root.optJSONObject(KEY_PROFILES) ?: return null
            val profileJson = profiles.optJSONObject(profileName) ?: return null
            
            val params = mutableMapOf<String, Float>()
            val paramJson = profileJson.optJSONObject("params")
            paramJson?.let {
                val keys = it.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    params[key] = it.getDouble(key).toFloat()
                }
            }
            
            return ProfileData(
                params = params,
                resW = profileJson.optDouble(KEY_RES_W, 0.0).toFloat(),
                resH = profileJson.optDouble(KEY_RES_H, 0.0).toFloat(),
                resIdx = profileJson.optInt(KEY_RES_IDX, 0),
                resScale = profileJson.optDouble(KEY_RES_SCALE, 1.0).toFloat(),
                aspectIdx = profileJson.optInt(KEY_ASPECT, 0),
                zonePortrait = profileJson.optString(KEY_ZONE_P, "").takeIf { it.isNotEmpty() }?.let { ShaderZone.parseFractions(it) },
                zoneLandscape = profileJson.optString(KEY_ZONE_L, "").takeIf { it.isNotEmpty() }?.let { ShaderZone.parseFractions(it) },
                barsPortrait = profileJson.optInt(KEY_BARS_P, 0) and ShaderZone.BAR_ALL,
                barsLandscape = profileJson.optInt(KEY_BARS_L, 0) and ShaderZone.BAR_ALL,
                autoSidesPortrait = profileJson.optInt(KEY_AUTO_SIDES_P, ShaderZone.BAR_ALL) and ShaderZone.BAR_ALL,
                autoSidesLandscape = profileJson.optInt(KEY_AUTO_SIDES_L, ShaderZone.BAR_ALL) and ShaderZone.BAR_ALL,
                gridAdapt = profileJson.optBoolean(KEY_GRID_ADAPT, false),
                dual = profileJson.optBoolean(KEY_DUAL, false),
                zoneBPortrait = profileJson.optString(KEY_ZONE_B_P, "").takeIf { it.isNotEmpty() }?.let { ShaderZone.parseFractions(it) },
                zoneBLandscape = profileJson.optString(KEY_ZONE_B_L, "").takeIf { it.isNotEmpty() }?.let { ShaderZone.parseFractions(it) }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    data class ProfileData(
        val params: Map<String, Float>,
        val resW: Float,
        val resH: Float,
        val resIdx: Int,
        val resScale: Float,
        val aspectIdx: Int,
        val zonePortrait: FloatArray? = null,
        val zoneLandscape: FloatArray? = null,
        val barsPortrait: Int = 0,
        val barsLandscape: Int = 0,
        val autoSidesPortrait: Int = ShaderZone.BAR_ALL,
        val autoSidesLandscape: Int = ShaderZone.BAR_ALL,
        val gridAdapt: Boolean = false,
        val dual: Boolean = false,
        val zoneBPortrait: FloatArray? = null,
        val zoneBLandscape: FloatArray? = null
    )

    /**
     * Carga solo los parámetros de un perfil específico (compatibilidad).
     */
    fun getProfileParameters(ctx: Context, shaderId: String, profileName: String): Map<String, Float>? {
        return getProfileData(ctx, shaderId, profileName)?.params
    }

    /**
     * Devuelve el nombre del perfil activo.
     */
    fun getActiveProfileName(ctx: Context, shaderId: String): String? {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dataJson = prefs.getString(shaderId, "{}") ?: "{}"
        return try {
            val root = JSONObject(dataJson)
            if (root.has(KEY_ACTIVE)) root.getString(KEY_ACTIVE) else null
        } catch (_: Exception) { null }
    }

    /**
     * Establece cuál es el perfil activo (sin cambiar los valores guardados).
     */
    fun setActiveProfile(ctx: Context, shaderId: String, profileName: String?) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dataJson = prefs.getString(shaderId, "{}") ?: "{}"
        try {
            val root = JSONObject(dataJson)
            if (profileName == null) {
                root.remove(KEY_ACTIVE)
            } else {
                root.put(KEY_ACTIVE, profileName)
            }
            prefs.edit().putString(shaderId, root.toString()).apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Renombra un perfil (T60). false si no existe, si el nombre nuevo ya está en uso o si es Default. El perfil activo, si era ese, sigue
     * siéndolo con el nombre nuevo.
     */
    fun renameProfile(ctx: Context, shaderId: String, oldName: String, newName: String): Boolean {
        if (oldName == "Default" || newName.isBlank()) return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val root = JSONObject(prefs.getString(shaderId, "{}") ?: "{}")
            val profiles = root.optJSONObject(KEY_PROFILES) ?: return false
            val data = profiles.optJSONObject(oldName) ?: return false
            if (profiles.has(newName)) return false
            profiles.put(newName, data)
            profiles.remove(oldName)
            if (root.optString(KEY_ACTIVE) == oldName) root.put(KEY_ACTIVE, newName)
            prefs.edit().putString(shaderId, root.toString()).apply()
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    /**
     * Elimina un perfil.
     */
    fun deleteProfile(ctx: Context, shaderId: String, profileName: String) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dataJson = prefs.getString(shaderId, "{}") ?: "{}"
        try {
            val root = JSONObject(dataJson)
            val profiles = root.optJSONObject(KEY_PROFILES) ?: return
            profiles.remove(profileName)
            
            if (root.optString(KEY_ACTIVE) == profileName) {
                root.remove(KEY_ACTIVE)
            }
            
            prefs.edit().putString(shaderId, root.toString()).apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Restablece todo para un shader (borra todos los perfiles).
     */
    fun clearAllProfiles(ctx: Context, shaderId: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(shaderId)
            .apply()
    }

}
