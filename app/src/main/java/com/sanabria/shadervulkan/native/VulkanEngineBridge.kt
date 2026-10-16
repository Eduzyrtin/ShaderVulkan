package com.sanabria.shadervulkan.native

import android.content.Context
import android.content.res.AssetManager
import android.view.Surface
import android.hardware.HardwareBuffer
import android.util.Log
import androidx.core.net.toUri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sanabria.shadervulkan.model.ShaderParamStore
import com.sanabria.shadervulkan.model.ShaderParameter
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * VulkanEngineBridge — Clase puente JNI para el motor nativo.
 */
object VulkanEngineBridge {

    init {
        System.loadLibrary("shader_engine")
    }

    /** Inicializa la instancia de Vulkan y el dispositivo */
    external fun initVulkan(context: Context, assetManager: AssetManager): Boolean

    /**
     * Salidas del motor (T15): la 0 es la de siempre (Captura/Glass en una ventana); la 1 existe solo en modo dual, con su propia
     * superficie, región de entrada y resolución virtual. Preset, parámetros, modo y giro van a todas las salidas, y cada frame
     * capturado alimenta a todas las que tienen superficie.
     */
    const val OUTPUT_MAIN = 0
    const val OUTPUT_SECOND = 1

    /** Crea la salida [output] (1) sobre el mismo dispositivo y con el estado actual del motor. */
    external fun enableOutput(output: Int): Boolean

    /** Destruye la salida [output]. Antes hay que quitarle la superficie con setOutputSurface(output, null). */
    external fun disableOutput(output: Int)

    /** Configura el surface de la salida [output] (el overlay de esa salida) */
    external fun setOutputSurface(output: Int, surface: Surface?): Boolean

    /** Configura el surface de la salida principal (el overlay) */
    fun setOutputSurface(surface: Surface?): Boolean = setOutputSurface(OUTPUT_MAIN, surface)

    /**
     * Procesa un frame proveniente de un HardwareBuffer. R06: true = el motor lee ahora ESTE buffer directamente (sin copiarlo) y ya ha
     * terminado con el anterior: quien llama retiene la Image de este buffer hasta la siguiente captura que devuelva true y cierra la
     * anterior. false = no lo ha tocado (p. ej. lo descartó el tope de FPS): se cierra ya y se sigue reteniendo la anterior.
     */
    external fun processFrame(buffer: HardwareBuffer): Boolean

    /** R06: el motor deja de leer los buffers de captura (espera a la GPU). Llamarlo antes de cerrar el lector o la Image retenida. */
    external fun releaseCaptureInput()

    /** Carga un preset de shader .slangp */
    external fun loadPreset(content: String, basePath: String): Boolean

    /** Progreso de la carga del preset: -1 = nada cargando, 0..99 = porcentaje */
    external fun getLoadProgress(): Int

    /** Texto de la tarea en curso durante la carga (p. ej. "Compilando pase 3/12: bloom") */
    external fun getLoadLabel(): String

    /** Motivo del último fallo de carga (pase que no compila, preset ilegible...) o "". Se entrega una sola vez. */
    external fun consumeLoadStatus(): String

    /** Obtiene los parámetros del shader actual */
    external fun getShaderParameters(): Array<ShaderParameter>?

    /** Actualiza un parámetro del shader */
    external fun updateShaderParameter(name: String, value: Float)

    /** Varios parámetros de golpe (perfil, restaurar valores): guarda todos y redibuja una sola vez. */
    external fun updateShaderParameters(names: Array<String>, values: FloatArray)

    /** Atajo para un mapa nombre -> valor. */
    fun updateShaderParameters(params: Map<String, Float>) {
        if (params.isEmpty()) return
        updateShaderParameters(params.keys.toTypedArray(), params.values.toFloatArray())
    }

    /** Establece la resolución nativa virtual para shaders tipo CRT en la salida [output] */
    external fun setVirtualNativeResolution(output: Int, width: Float, height: Float)

    fun setVirtualNativeResolution(width: Float, height: Float) = setVirtualNativeResolution(OUTPUT_MAIN, width, height)

    /**
     * Zona del shader (T05): rectángulo de la pantalla, en fracciones 0..1 (izquierda, arriba, derecha, abajo), que procesa el
     * shader. La ventana del overlay mide lo mismo. (0,0,1,1) = pantalla completa. Lo fija OverlayManager.updateOverlayLayout.
     */
    external fun setInputRegion(output: Int, left: Float, top: Float, right: Float, bottom: Float)

    fun setInputRegion(left: Float, top: Float, right: Float, bottom: Float) = setInputRegion(OUTPUT_MAIN, left, top, right, bottom)

    /**
     * Modo de salida del motor (T30): [MODE_CAPTURE] (el shader procesa la captura) o [MODE_GLASS] (capa transparente con
     * alfa sin captura, PROYECTO.md §10). Se aplica en el siguiente redibujado: recrea el swapchain y recompila el preset.
     */
    external fun setRenderMode(mode: Int)

    /** FPS a los que se redibuja la capa Glass (parámetro GLASS_ANIM_FPS, T34); 0 = estática o no es Glass. */
    external fun getGlassAnimFps(): Float

    /** Valor actual de un parámetro por nombre; [fallback] si el preset no lo declara (T35 lee GLASS_BLUR). */
    external fun getParameterValue(name: String, fallback: Float): Float

    /** Pide un redibujado en todas las salidas. */
    external fun requestRedraw()

    /** Preset propio para la zona B (salida 1, T18); devuelve false si no se pudo. */
    external fun loadPresetForOutput(content: String, basePath: String, output: Int): Boolean
    // Conjunto de presets combinados (T45): la primera pieza va delante; output 0 = A (y B si no tiene el suyo), 1 = zona B
    external fun loadPresetStack(contents: Array<String>, basePaths: Array<String>, output: Int): Boolean
    // Contador de FPS y tope de salida (T66)
    external fun setPerfEnabled(enabled: Boolean)
    /** [frames de salida acumulados, microsegundos de CPU en renderizarlos]; quien lee calcula las diferencias. */
    external fun getPerfCounters(): LongArray
    /** Tope de FPS de salida: 0 = sin tope. */
    external fun setFpsCap(fps: Int)

    /** La zona B vuelve a seguir al preset de la A. */
    external fun clearOutputPreset(output: Int)

    /** Salida cuyos parámetros editan los controles (0 = A, 1 = B). Solo cuenta si la B tiene preset propio. */
    external fun setEditOutput(output: Int)

    /** Pone la salida en edición según las preferencias (zona B elegida y con preset propio). */
    fun syncEditOutput(context: Context) {
        setEditOutput(if (ShaderPreferences.editsZoneB(context)) 1 else 0)
    }

    /** Aplica el perfil activo de un preset de una salida concreta (la 1 = zona B con preset propio). */
    fun applySavedShaderParametersFor(context: Context, shaderId: String, output: Int) {
        if (output != 1) { applySavedShaderParameters(context, shaderId); return }
        synchronized(this) {
            setEditOutput(1)
            try { applySavedShaderParameters(context, shaderId, paramsOnly = true) } finally { syncEditOutput(context) }
        }
    }

    const val MODE_CAPTURE = 0
    const val MODE_GLASS = 1

    /**
     * Detección automática de bandas negras (T07): activa o no, y en qué lados puede recortar (bits de ShaderZone.BAR_*).
     * Lo fija OverlayManager.updateOverlayLayout según el selector de zona.
     */
    external fun setAutoDetect(enabled: Boolean, sides: Int)

    /**
     * Llamada desde C++ (hilo de render) cuando la detección mide una caja de contenido nueva: fracciones 0..1 de la
     * pantalla y orientación de la captura analizada. (0,0,1,1) = sin recorte.
     */
    @JvmStatic
    fun onAutoRegion(left: Float, top: Float, right: Float, bottom: Float, landscape: Boolean) {
        val full = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
        val changed = com.sanabria.shadervulkan.model.ShaderZone.setDetectedBox(
            landscape, if (full) null else floatArrayOf(left, top, right, bottom)
        )
        if (changed) refreshZone()
    }

    /** Recalcula la zona del shader (aspecto y resolución actuales) y la aplica a la ventana del overlay y al motor. */
    fun refreshZone() {
        ShaderOverlayAccessibilityService.updateOverlayLayout()
    }

    /** Actualiza la rotación detectada del dispositivo (0, 1, 2, 3) */
    external fun updateRotation(rotation: Int)

    /** Actualiza los datos de los sensores (Giroscopio y Acelerómetro) */
    external fun updateSensors(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float)

    /** true si el preset activo usa Gyroscope/Accelerometer/AccelerometerRest (lo fija el motor al adoptar el preset). */
    @Volatile var sensorsNeeded = false
        private set

    /** Quien enciende/apaga los sensores reales (el servicio de captura). Se llama desde un hilo del motor. */
    @Volatile var sensorsNeededListener: ((Boolean) -> Unit)? = null

    /** Llamada desde C++ cuando el preset activo empieza o deja de necesitar los sensores. */
    @JvmStatic
    fun onSensorsNeeded(needed: Boolean) {
        sensorsNeeded = needed
        sensorsNeededListener?.invoke(needed)
    }

    /** 
     * Función llamada desde C++ para cargar contenido de archivos externos (URIs)
     * que el AAssetManager nativo no puede alcanzar.
     */
    @JvmStatic
    fun fetchExternalContent(context: Context, fullPath: String): String? {
        Log.d("DEBUG-LOG-BRIDGE", "C++ solicita contenido de: $fullPath")
        return try {
            if (fullPath.startsWith("content://")) {
                var uriToOpen = fullPath.toUri()
                
                // REPARACIÓN DE URI SAF (Robusta):
                // C++ concatena rutas usando '/', pero SAF espera Document IDs con '%2F' codificado.
                if (fullPath.contains("/document/") && fullPath.count { it == '/' } > 4) {
                    val parts = fullPath.split("/document/")
                    if (parts.size == 2) {
                        val base = parts[0] + "/document/"
                        val docIdWithSubPath = parts[1].replace("/", "%2F")
                        uriToOpen = (base + docIdWithSubPath).toUri()
                        Log.d("DEBUG-LOG-BRIDGE", "URI Reparada para SAF: $uriToOpen")
                    }
                }

                context.contentResolver.openInputStream(uriToOpen)?.use {
                    it.bufferedReader().readText().also { text ->
                        Log.i("DEBUG-LOG-BRIDGE", "OK: Cargados ${text.length} bytes desde URI")
                    }
                }
            } else {
                null
            }
        } catch (_: FileNotFoundException) {
            if (fullPath.endsWith(".spv", ignoreCase = true)) {
                Log.d("DEBUG-LOG-BRIDGE", "Archivo precompilado .spv no encontrado (se usará compilador de código fuente .slang): $fullPath")
            } else {
                Log.w("DEBUG-LOG-BRIDGE", "Archivo no encontrado: $fullPath")
            }
            null
        } catch (e: Exception) {
            if (fullPath.endsWith(".spv", ignoreCase = true)) {
                // El motor prueba a leer un .spv precompilado junto a cada .slang y casi nunca existe; la carpeta externa (SAF) lo
                // dice con IllegalArgumentException en vez de FileNotFoundException. No es un error: se compila el .slang.
                Log.d("DEBUG-LOG-BRIDGE", "Sin .spv precompilado (se compilará el .slang): $fullPath")
            } else {
                Log.e("DEBUG-LOG-BRIDGE", "Error leyendo $fullPath: ${e.message}")
            }
            null
        }
    }

    @JvmStatic
    fun fetchTexture(context: Context, fullPath: String): Bitmap? {
        Log.d("DEBUG-LOG-BRIDGE", "C++ solicita textura de: $fullPath")
        return try {
            val inputStream: InputStream? = if (fullPath.startsWith("content://")) {
                var uriToOpen = fullPath.toUri()
                if (fullPath.contains("/document/") && fullPath.count { it == '/' } > 4) {
                    val parts = fullPath.split("/document/")
                    if (parts.size == 2) {
                        val base = parts[0] + "/document/"
                        val docIdWithSubPath = parts[1].replace("/", "%2F")
                        uriToOpen = (base + docIdWithSubPath).toUri()
                        Log.d("DEBUG-LOG-BRIDGE", "URI Reparada para SAF (Textura): $uriToOpen")
                    }
                }
                context.contentResolver.openInputStream(uriToOpen)
            } else {
                context.assets.open(fullPath)
            }

            inputStream?.use {
                val opts = BitmapFactory.Options().apply { 
                    inPreferredConfig = Bitmap.Config.ARGB_8888 
                    inScaled = false // Evitar que Android reescale texturas de shaders
                    // Las LUTs con alfa (máscaras, rejillas) deben llegar tal cual: por defecto Android premultiplica el
                    // color por el alfa y altera el resultado del shader
                    inPremultiplied = false
                }
                BitmapFactory.decodeStream(it, null, opts).also { bmp ->
                    if (bmp != null) {
                        Log.i("DEBUG-LOG-BRIDGE", "OK: Textura decodificada ${bmp.width}x${bmp.height}")
                    } else {
                        Log.e("DEBUG-LOG-BRIDGE", "Error: BitmapFactory devolvió null para $fullPath")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DEBUG-LOG-BRIDGE", "Error cargando textura $fullPath: ${e.message}")
            null
        }
    }

    /** Libera recursos */
    external fun release()

    /** Aplica ajustes guardados en SharedPreferences al motor nativo */
    fun applySavedSettings(context: Context) {
        val (w, h) = ShaderPreferences.getVirtualResolution(context)
        if (w > 0 && h > 0) {
            setVirtualNativeResolution(w, h)
        }
        refreshZone() // la zona sale del aspecto y la resolución que acaban de leerse de las preferencias
    }

    /** Vuelve a los valores con los que cargó el preset (descarta lo cambiado con sliders o perfiles) y redibuja. */
    external fun resetShaderParameters()

    /** Aplica el perfil activo de un shader (al cargarlo). Sin perfil guardado no toca nada: el preset recién cargado manda. */
    fun applySavedShaderParameters(context: Context, shaderId: String, paramsOnly: Boolean = ShaderPreferences.editsZoneB(context)) {
        val activeProfile = ShaderParamStore.getActiveProfileName(context, shaderId) ?: "Default"
        applyProfile(context, shaderId, activeProfile, paramsOnly)
    }

    /**
     * Aplica un perfil COMPLETO: primero vuelve a los valores del preset y encima pone los del perfil, así un perfil que
     * no guarda un parámetro no hereda el valor que dejó el perfil anterior. También resolución y aspecto: la resolución
     * se aplica siempre, un perfil en Auto (0x0 = pantalla nativa) tiene que anular la del shader o perfil previo.
     * No cambia cuál es el perfil activo (eso lo hace ShaderParamStore.setActiveProfile). Devuelve false si no existe.
     */
    /**
     * [paramsOnly]: solo los valores de los parámetros; no toca resolución, zona, barras ni modo dual (que son de la pantalla, no del
     * preset). Es lo que corresponde al preset propio de la zona B (T18): su perfil no puede cambiar la disposición de la A.
     */
    fun applyProfile(context: Context, shaderId: String, profileName: String, paramsOnly: Boolean = ShaderPreferences.editsZoneB(context)): Boolean {
        val data = ShaderParamStore.getProfileData(context, shaderId, profileName) ?: return false
        Log.i("VulkanEngineBridge", "Aplicando perfil '$profileName' para $shaderId")

        resetShaderParameters()
        updateShaderParameters(data.params)
        if (paramsOnly) return true

        setVirtualNativeResolution(data.resW, data.resH)
        ShaderPreferences.setVirtualResolution(context, data.resW, data.resH)
        ShaderPreferences.setVirtualResolutionIndex(context, data.resIdx)
        if (data.resIdx == 7) { // Scale
            ShaderPreferences.setScaleValue(context, data.resScale)
        }
        ShaderPreferences.setAspectRatioIndex(context, data.aspectIdx)
        // Las zonas manuales (una por orientación) también son del perfil; un perfil sin ellas deja ambas sin definir
        ShaderPreferences.setManualZone(context, false, data.zonePortrait)
        ShaderPreferences.setManualZone(context, true, data.zoneLandscape)
        ShaderPreferences.setBars(context, false, data.barsPortrait)
        ShaderPreferences.setBars(context, true, data.barsLandscape)
        ShaderPreferences.setAutoSides(context, false, data.autoSidesPortrait)
        ShaderPreferences.setAutoSides(context, true, data.autoSidesLandscape)
        ShaderPreferences.setGridAdapt(context, data.gridAdapt)
        // Modo dual (T16): un perfil sin él lo apaga, como pasa con las zonas
        ShaderPreferences.setDual(context, data.dual)
        ShaderPreferences.setManualZoneB(context, false, data.zoneBPortrait)
        ShaderPreferences.setManualZoneB(context, true, data.zoneBLandscape)
        refreshZone() // después de guardar resolución, aspecto y zonas: la zona sale de ellos
        return true
    }

    /**
     * Guarda el estado actual (todos los parámetros editables + resolución + aspecto) como perfil y lo deja activo.
     * Es una foto completa: así al volver a este perfil no depende de lo que hubiera antes.
     */
    fun saveCurrentAsProfile(context: Context, shaderId: String, profileName: String) {
        val params = getShaderParameters()?.filterNot { it.isInfo }?.associate { it.name to it.current } ?: emptyMap()
        val res = ShaderPreferences.getVirtualResolution(context)
        ShaderParamStore.saveProfile(
            context, shaderId, profileName, params, res.first, res.second,
            ShaderPreferences.getVirtualResolutionIndex(context), ShaderPreferences.getScaleValue(context),
            ShaderPreferences.getAspectRatioIndex(context),
            ShaderPreferences.getManualZone(context, false), ShaderPreferences.getManualZone(context, true),
            ShaderPreferences.getBars(context, false), ShaderPreferences.getBars(context, true),
            ShaderPreferences.getAutoSides(context, false), ShaderPreferences.getAutoSides(context, true),
            ShaderPreferences.isGridAdapt(context),
            ShaderPreferences.isDual(context),
            ShaderPreferences.getManualZoneB(context, false), ShaderPreferences.getManualZoneB(context, true)
        )
    }
}
