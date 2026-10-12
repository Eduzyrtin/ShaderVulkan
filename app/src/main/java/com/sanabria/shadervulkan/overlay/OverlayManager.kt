package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.sanabria.shadervulkan.services.BatterySaver
import com.sanabria.shadervulkan.services.CaptureScale
import com.sanabria.shadervulkan.model.ScreenLayout
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.model.ShaderZone
import com.sanabria.shadervulkan.native.VulkanEngineBridge

/**
 * OverlayManager — Gestiona la ventana de sistema con la superficie (SurfaceView o TextureView, ver [USE_TEXTURE_VIEW]) para Vulkan.
 *
 * Hay uno por salida del motor (T16): la 0 es la zona A (y la pantalla completa fuera del modo dual) y la 1 es la zona B del
 * modo dual. Solo la salida 0 maneja las barras negras y la detección de bandas.
 */
class OverlayManager(private val context: Context, private val output: Int = 0) {

    private companion object {
        /** Android 13 y anteriores: TextureView en vez de SurfaceView, para que los toques atraviesen el overlay (ver createTextureView). */
        val USE_TEXTURE_VIEW = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    // Display donde va la ventana (T17): el 0 salvo la salida que toque la segunda pantalla. Se cambia con [setDisplay].
    private var displayId = Display.DEFAULT_DISPLAY
    private var windowContext: Context = context
    private var windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var canvasView: View? = null
    private var rootLayout: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // Suspensión ligera: ventana transparente pero viva. Con removeView/GONE se destruiría la superficie
    // y al volver habría que recompilar el pipeline entero.
    private var suspended = false

    // Modo Glass (T31): la ventana no tiene fondo, deja ver la app de debajo a través del alfa del shader.
    // En Captura el fondo es negro (lo que no pinta el shader no debe dejar ver la pantalla real duplicada).
    private var glass = false

    /**
     * Formato de la ventana y de su superficie (R03). En Captura el shader lo tapa todo: con OPAQUE el compositor sabe que la capa es
     * opaca, no la mezcla con lo de debajo y deja de componer la app que queda tapada dentro de la zona (con TRANSLUCENT componía y
     * mezclaba en cada refresco una capa entera que no se ve). Ventana y superficie llevan siempre el mismo formato (DOCUMENTACION.md
     * §17.7). RGBX_8888 y no PixelFormat.OPAQUE: con OPAQUE el SurfaceView elige RGB_565 como formato por defecto (los buffers los fija
     * igualmente la swapchain de Vulkan en RGBA8, pero así no hay duda). En Glass hace falta el alfa: TRANSLUCENT.
     */
    private fun surfaceFormat() = if (glass) PixelFormat.TRANSLUCENT else PixelFormat.RGBX_8888

    /**
     * Cambia de modo. Devuelve true si la ventana se ha rehecho: entonces ha quedado encima de las demás ventanas del servicio (botón,
     * menú, panel de carga, contador) y quien llama tiene que volver a subirlas.
     */
    fun setGlassMode(value: Boolean): Boolean {
        if (glass == value) return false
        glass = value
        if (!value) setBlur(0)
        Log.i("SVDIAG_OVERLAY", "Ventana del overlay en modo ${if (value) "Glass (transparente)" else "Captura (fondo negro, opaca)"}")
        // El formato de la ventana y de la superficie se fija al crearlas: con la ventana puesta, se rehace (el motor recibe la
        // superficie nueva; el cambio de modo ya recrea su swapchain y recompila de todos modos)
        if (rootLayout == null) return false
        detachWindow()
        showOverlay()
        return true
    }

    // Desenfoque del contenido de debajo (T35, GLASS_BLUR): lo hace el compositor (FLAG_BLUR_BEHIND, API 31+). Se vuelve a aplicar
    // si el ahorro de batería lo apaga o lo enciende.
    private var wantedBlur = 0
    private var blurListener: java.util.function.Consumer<Boolean>? = null

    /** Radio en píxeles (0 = sin desenfoque). Solo cuenta en Glass y si el sistema permite desenfoque entre ventanas. */
    fun setBlur(px: Int) {
        wantedBlur = px.coerceAtLeast(0)
        applyBlur()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val container = rootLayout ?: return
        val lp = params ?: return
        val on = glass && wantedBlur > 0 && windowManager.isCrossWindowBlurEnabled
        val radius = if (on) wantedBlur else 0
        val flagSet = (lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0
        if (lp.blurBehindRadius == radius && flagSet == on) return
        lp.blurBehindRadius = radius
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        try {
            windowManager.updateViewLayout(container, lp)
            Log.i("SVDIAG_OVERLAY", "Desenfoque de la salida $output: ${radius} px (permitido=${windowManager.isCrossWindowBlurEnabled})")
        } catch (e: Exception) {
            Log.e("SVDIAG_OVERLAY", "Error en applyBlur: ${e.message}")
        }
    }

    // Barras negras por lado fuera de la zona (T08); siguen al overlay del shader
    private val bars = BlackBars(context)

    /** Se avisa cuando las barras negras crean su ventana (quedaría encima del botón y del menú flotantes). */
    var onBarsAdded: (() -> Unit)?
        get() = bars.onAdded
        set(value) { bars.onAdded = value }

    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        bars.setSuspended(value)
        val container = rootLayout ?: return
        val lp = params ?: return
        lp.alpha = if (value) 0f else 1f
        try {
            windowManager.updateViewLayout(container, lp)
            Log.i("SVDIAG_OVERLAY", "Overlay ${if (value) "suspendido" else "reanudado"} (alpha=${lp.alpha})")
        } catch (e: Exception) {
            Log.e("SVDIAG_OVERLAY", "Error en setSuspended: ${e.message}")
        }
    }

    /**
     * Pone la ventana de esta salida en otro display (T17). Con la ventana ya puesta se quita y se vuelve a poner en el nuevo; el
     * motor conserva la salida, que recibe la superficie nueva. Devuelve true si cambió algo.
     */
    fun setDisplay(id: Int): Boolean {
        if (id == displayId) return false
        val wasShown = rootLayout != null
        if (wasShown) detachWindow()
        displayId = id
        windowContext = ScreenLayout.windowContext(context, id)
        windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.i("SVDIAG_OVERLAY", "Salida $output -> display $id")
        if (wasShown) showOverlay()
        return true
    }

    /** La ventana de esta salida existe. */
    val isShown: Boolean get() = rootLayout != null

    fun showOverlay() {
        Log.i("SVDIAG_OVERLAY", "showOverlay($output) invocado. rootLayout es nulo? ${rootLayout == null}")
        if (rootLayout != null) {
            updateOverlayLayout()
            return
        }
        // La salida extra del motor tiene que existir antes que su superficie
        if (output > 0 && !VulkanEngineBridge.enableOutput(output)) {
            Log.e("SVDIAG_OVERLAY", "No se pudo crear la salida $output del motor: no hay ventana")
            return
        }

        Log.d("SVDIAG_OVERLAY", "Configurando LayoutParams para overlay. SDK: ${Build.VERSION.SDK_INT}")

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    (if (USE_TEXTURE_VIEW) WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED else 0), // el TextureView solo se dibuja con aceleración
            surfaceFormat() // R03: opaca en Captura, translúcida en Glass
        ).apply {
            alpha = if (suspended) 0f else 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyZone(lp)
        params = lp

        val canvas: View = if (USE_TEXTURE_VIEW) createTextureView() else createSurfaceView()
        canvasView = canvas

        val container = FrameLayout(windowContext).apply {
            setBackgroundColor(if (glass) Color.TRANSPARENT else Color.BLACK)
            addView(canvas, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        try {
            Log.d("SVDIAG_OVERLAY", "Llamando a windowManager.addView")
            windowManager.addView(container, lp)
            rootLayout = container
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val l = java.util.function.Consumer<Boolean> { container.post { applyBlur() } }
                blurListener = l
                windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, l)
            }
            Log.i("SVDIAG_OVERLAY", "addView exitoso")
        } catch (e: Exception) {
            Log.e("SVDIAG_OVERLAY", "ERROR en addView: ${e.message}")
            e.printStackTrace()
        }
    }

    /** Superficie de dibujo del overlay con un SurfaceView (Android 14+): el camino de siempre. */
    private fun createSurfaceView(): View = SurfaceView(windowContext).apply {
        val view = this
        setZOrderOnTop(true)
        holder.setFormat(surfaceFormat()) // R03: el mismo que la ventana
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i("SVDIAG_OVERLAY", "surfaceCreated: holder.surface=${holder.surface}")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                Log.i("SVDIAG_OVERLAY", "surfaceChanged DISPARADO: ${w}x${h}, format=$format, surface=${holder.surface}")
                if (canvasView !== view) return // de una ventana ya quitada (ver surfaceDestroyed)
                VulkanEngineBridge.setOutputSurface(output, holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i("SVDIAG_OVERLAY", "surfaceDestroyed")
                // El aviso de una ventana ya quitada puede llegar tarde (Android lo difiere), cuando ya hay otra ventana con su
                // superficie (R03 la rehace al cambiar de modo; también setDisplay): ese no toca el motor, que ya la soltó en detachWindow
                if (canvasView !== view) return
                VulkanEngineBridge.setOutputSurface(output, null)
            }
        })
    }

    /**
     * Superficie de dibujo con un TextureView (Android 13 y anteriores). Un SurfaceView crea su propia capa en SurfaceFlinger y
     * el InputDispatcher la cuenta como una ventana NO de confianza que tapa la pantalla entera (dumpsys input: NO_INPUT_CHANNEL,
     * BLOCK_UNTRUSTED), aunque la ventana del overlay sí sea de confianza. En Android 13 eso descarta los toques que la atraviesan
     * hacia las apps con targetSdk >= 31 ("Untrusted touch due to occlusion by /<uid>"). El TextureView se dibuja dentro de la
     * propia ventana, sin capa aparte, y los toques pasan. R18 (probado en un emulador con Android 13): una SurfaceControl hija de la
     * ventana (AttachedSurfaceControl) sale igual de no confiable que el SurfaceView; no hay alternativa al TextureView.
     */
    private fun createTextureView(): View = TextureView(windowContext).apply {
        val view = this
        isOpaque = !glass // R03: en Captura tapa todo; en Glass deja ver la app a través del alfa
        var surface: Surface? = null
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                Log.i("SVDIAG_OVERLAY", "TextureView disponible: ${w}x${h}")
                surface?.release()
                surface = Surface(st).also { if (canvasView === view) VulkanEngineBridge.setOutputSurface(output, it) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                Log.i("SVDIAG_OVERLAY", "TextureView cambió de tamaño: ${w}x${h}")
                if (canvasView !== view) return
                surface?.let { VulkanEngineBridge.setOutputSurface(output, it) } // recrea la cadena de intercambio al nuevo tamaño
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                Log.i("SVDIAG_OVERLAY", "TextureView destruido")
                // El motor suelta la superficie antes de que se libere; si este TextureView ya no es el de la ventana (aviso que llega
                // tarde, ver createSurfaceView), el motor ya la soltó en detachWindow y no se toca la de la ventana nueva
                if (canvasView === view) VulkanEngineBridge.setOutputSurface(output, null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    fun updateOverlayLayout() {
        val container = rootLayout ?: return
        val lp = params ?: return
        try {
            applyZone(lp) // tamaño y posición de la ventana = zona del shader (o pantalla completa)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                lp.setFitInsetsTypes(0)
            } else {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            windowManager.updateViewLayout(container, lp)
            Log.i("SVDIAG_OVERLAY", "updateViewLayout de OverlayManager completado con éxito")
        } catch (e: Exception) {
            Log.e("SVDIAG_OVERLAY", "Error en updateOverlayLayout: ${e.message}")
        }
    }

    /**
     * Zona del shader (T05): la ventana del overlay mide y se coloca igual que la zona, así que la salida del shader la llena
     * y fuera de ella se ve la pantalla real. Se le pasa la misma zona al motor para que recorte la captura. Sin zona,
     * ventana a pantalla completa como siempre.
     */
    private fun applyZone(lp: WindowManager.LayoutParams) {
        val (sw, sh) = ShaderZone.screenSize(windowManager)
        // La segunda pantalla (T17) tiene su propia zona; las barras y la detección son solo de la pantalla principal
        val onSecond = displayId != Display.DEFAULT_DISPLAY
        val dual = ShaderZone.isDual(context)
        val zones = if (onSecond) emptyList() else ShaderZone.currentRects(context, sw, sh)
        // En modo dual la zona de cada salida es la suya (una zona que ocupa toda la pantalla cuenta como "sin zona")
        val rect = if (onSecond) ShaderZone.zoneForSecond(context, sw, sh)
                   else zones.getOrNull(output)?.takeUnless { it.left <= 0 && it.top <= 0 && it.right >= sw && it.bottom >= sh }
        if (rect == null) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.x = 0
            lp.y = 0
            VulkanEngineBridge.setInputRegion(output, 0f, 0f, 1f, 1f)
        } else {
            lp.width = rect.width()
            lp.height = rect.height()
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = rect.left
            lp.y = rect.top
            VulkanEngineBridge.setInputRegion(
                output, rect.left.toFloat() / sw, rect.top.toFloat() / sh, rect.right.toFloat() / sw, rect.bottom.toFloat() / sh
            )
        }
        // Rejilla del shader: la de la resolución elegida (la B, con su resolución propia, en la segunda salida), o (zona manual
        // con REJILLA ADAPTADA) ajustada a la forma de la zona. Lo adaptado solo se manda al motor, no se guarda.
        val useB = output == 1 && dual && !onSecond // con una pantalla por salida (T17) las dos usan la rejilla A
        val (resW, resH) = if (useB) ShaderPreferences.getVirtualResolutionB(context) else ShaderPreferences.getVirtualResolution(context)
        val grid = ShaderZone.adaptedGrid(context, rect, sw, sh, output, onSecond)
        var gridW = grid?.first ?: resW
        var gridH = grid?.second ?: resH
        // Modo batería (T71): resolución virtual reducida. En Auto (0) se parte del tamaño de la zona o, sin ella, de la pantalla.
        val saver = BatterySaver.resolutionFactor
        if (saver < 1f) {
            if (gridW <= 0f || gridH <= 0f) { gridW = (rect?.width() ?: sw).toFloat(); gridH = (rect?.height() ?: sh).toFloat() }
            gridW = maxOf(64f, gridW * saver)
            gridH = maxOf(64f, gridH * saver)
        }
        VulkanEngineBridge.setVirtualNativeResolution(output, gridW, gridH)
        // R12: tamaño de captura que necesita esta salida (solo las de la pantalla principal leen la captura)
        if (onSecond) CaptureScale.clear(output) else CaptureScale.update(context, output, rect, sw, sh, gridW, gridH)
        if (output == 0 && onSecond) {
            VulkanEngineBridge.setAutoDetect(false, 0)
            bars.update(emptyList())
        } else if (output == 0) {
            // Detección automática de bandas negras (T07): el motor solo mide si el selector está en "Auto (detectar bandas)"
            // y no hay dos zonas (mediría la unión de las dos, P5)
            VulkanEngineBridge.setAutoDetect(ShaderZone.isAutoDetect(context) && !dual, ShaderPreferences.getAutoSides(context, sw >= sh))
            // Barras negras de esta orientación: por lado fuera de la zona o, en dual, todo lo que queda fuera de las dos
            val mask = ShaderPreferences.getBars(context, sw >= sh)
            bars.update(if (dual) ShaderZone.dualBarRects(zones, mask, sw, sh) else ShaderZone.barRects(rect, mask, sw, sh))
        }
        Log.i("SVDIAG_OVERLAY", "Zona del shader (salida $output): pantalla ${sw}x$sh -> " +
                (rect?.let { "${it.width()}x${it.height()} en (${it.left},${it.top})" } ?: "completa"))
    }

    fun hideOverlay() {
        Log.i("SVDIAG_OVERLAY", "hideOverlay($output) invocado")
        bars.remove()
        CaptureScale.clear(output) // R12: sin ventana, esta salida no lee la captura
        // La salida extra se destruye en el motor después de quitar su ventana
        if (detachWindow() && output > 0) VulkanEngineBridge.disableOutput(output)
    }

    /** Quita la ventana sin tocar la salida del motor (la salida extra suelta antes su superficie). false si no había ventana. */
    private fun detachWindow(): Boolean {
        val view = rootLayout ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurListener?.let { windowManager.removeCrossWindowBlurEnabledListener(it) }
        blurListener = null
        // El motor suelta la superficie ya, en todas las salidas: el surfaceDestroyed de esta ventana puede llegar tarde y entonces
        // se ignora (ver createSurfaceView)
        VulkanEngineBridge.setOutputSurface(output, null)
        try {
            windowManager.removeView(view)
            Log.d("SVDIAG_OVERLAY", "removeView exitoso")
        } catch (e: Exception) {
            Log.e("SVDIAG_OVERLAY", "ERROR en removeView: ${e.message}")
            e.printStackTrace()
        }
        rootLayout = null
        canvasView = null
        params = null
        return true
    }
}
