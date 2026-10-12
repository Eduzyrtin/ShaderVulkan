package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.PerfSettings
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.services.SessionMode
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService
import com.sanabria.shadervulkan.ui.CrtUi
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Frames de captura que llegan al servicio (entrada del contador, T66). Solo cuenta mientras el contador se muestra. */
object PerfStats {
    val arrived = AtomicLong()
    @Volatile var counting = false
}

/**
 * Contador de FPS (T66): entrada (frames que llegan de la captura) y salida (frames que renderiza el motor tras el shader), en la
 * esquina elegida. Es una ventana de accesibilidad que no toma toques ni foco (como LoadingHud): no entra en la captura, así que
 * el shader no la procesa. Se sondea cada intervalo; con el contador apagado (o fuera de la sesión) el motor no mide nada.
 * Salida muy por debajo de la entrada de forma sostenida = la GPU o el shader no dan la talla; el texto se pone rojo.
 */
class FpsCounter(private val context: Context, private val menuOpen: () -> Boolean) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var running = false

    // Línea base de la última lectura
    private var lastT = 0L
    private var lastIn = 0L
    private var lastOut = 0L
    private var lastMicros = 0L
    private var badTicks = 0
    private var lastUi = 0L

    // Refresco de la pantalla: cada vsync que recibe esta app. Es el del display (en pantallas LTPO baja con contenido quieto), no los
    // fotogramas de la app de debajo, que Android no expone. Solo corre mientras el contador se muestra y UI está activado.
    private var uiFrames = 0L
    private var uiTracking = false
    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            uiFrames++
            if (uiTracking) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun trackUi(on: Boolean) {
        if (on == uiTracking) return
        uiTracking = on
        if (on) Choreographer.getInstance().postFrameCallback(vsync) else Choreographer.getInstance().removeFrameCallback(vsync)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val delay = update()
            if (delay > 0) handler.postDelayed(this, delay) // 0 = contador apagado: sin sondeo hasta refresh() (R08)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        hide()
        setMeasuring(false)
        trackUi(false)
    }

    /** Los ajustes cambiaron: se rehace la vista y se vuelve a tomar la línea base. */
    fun refresh() {
        hide()
        resetBaseline()
        if (running) { handler.removeCallbacks(tick); handler.post(tick) }
    }

    /** Sube la ventana por encima de las que se añadieron después. */
    fun bringToFront() {
        if (view != null) { hide(); resetBaseline() }
    }

    private fun resetBaseline() { lastT = 0L; badTicks = 0 }

    private fun setMeasuring(on: Boolean) {
        PerfStats.counting = on
        try { VulkanEngineBridge.setPerfEnabled(on) } catch (_: Throwable) {}
    }

    /** Un paso del sondeo. Devuelve cuánto esperar hasta el siguiente; 0 = no seguir (contador apagado, lo reanuda [refresh]). */
    private fun update(): Long {
        // R08: con el contador apagado en Ajustes no hay nada que vigilar (antes se miraba cada 400 ms toda la sesión). Encenderlo pasa
        // por applyPerfSettings -> refresh(), que vuelve a arrancar el sondeo.
        if (!PerfSettings.isFpsCounter(context)) {
            hide()
            resetBaseline()
            setMeasuring(false)
            trackUi(false)
            return 0L
        }
        val interval = PerfSettings.INTERVALS_MS[PerfSettings.getFpsInterval(context)]
        val visible = PerfSettings.isFpsCounter(context) && ShaderCaptureService.hasControls &&
                !ShaderOverlayAccessibilityService.isOverlaySuspended &&
                (!PerfSettings.isFpsOnlyWithMenu(context) || menuOpen())
        setMeasuring(visible)
        trackUi(visible && PerfSettings.isFpsShowUi(context))
        if (!visible) {
            hide()
            resetBaseline()
            return 400L // reaccionar pronto a que se abra el menú o se encienda
        }

        val now = SystemClock.elapsedRealtime()
        val arrived = PerfStats.arrived.get()
        val counters = try { VulkanEngineBridge.getPerfCounters() } catch (_: Throwable) { longArrayOf(0, 0) }
        val out = counters.getOrElse(0) { 0L }
        val micros = counters.getOrElse(1) { 0L }
        val tv = ensureView() ?: return interval
        if (lastT == 0L) {
            lastT = now; lastIn = arrived; lastOut = out; lastMicros = micros; lastUi = uiFrames
            tv.text = "..."
            return interval
        }
        val dt = max(1L, now - lastT) / 1000.0
        val glass = ShaderCaptureService.sessionMode == SessionMode.GLASS
        val inFps = (arrived - lastIn) / dt
        val outFps = (out - lastOut) / dt
        val dOut = out - lastOut
        val ms = if (dOut > 0) (micros - lastMicros) / dOut / 1000.0 else 0.0
        val uiFps = (uiFrames - lastUi) / dt
        lastT = now; lastIn = arrived; lastOut = out; lastMicros = micros; lastUi = uiFrames

        // Sostenido: tres lecturas seguidas con la salida claramente por debajo de lo que se esperaba (con tope, lo del tope)
        val cap = PerfSettings.getFpsCap(context)
        val expected = if (cap > 0) min(inFps, cap.toDouble()) else inFps
        val behind = !glass && inFps >= 5 && outFps < expected * 0.8
        badTicks = if (behind) badTicks + 1 else 0
        val warn = PerfSettings.isFpsLostWarning(context) && badTicks >= 3

        val sb = StringBuilder()
        if (PerfSettings.isFpsShowUi(context)) sb.append("UI ").append(uiFps.roundToInt()).append("  ")
        if (glass) sb.append("OUT ").append(outFps.roundToInt())
        else sb.append("IN ").append(inFps.roundToInt()).append("  OUT ").append(outFps.roundToInt())
        if (PerfSettings.getFpsDetail(context) == 1) {
            sb.append('\n').append(String.format(java.util.Locale.US, "%.1f ms", ms))
            if (!glass) sb.append("  DROP ").append(max(0.0, inFps - outFps).roundToInt())
        }
        tv.text = sb
        tv.setTextColor(if (warn) WARN_COLOR else CrtUi.color(tv.context, R.attr.crtPhosphor))
        return interval
    }

    private fun ensureView(): TextView? {
        view?.let { return it }
        val ui = CrtUi.themed(context)
        val size = when (PerfSettings.getFpsSize(context)) { 1 -> R.dimen.crt_text_body; 2 -> R.dimen.crt_text_display; else -> R.dimen.crt_text_caption }
        val opacity = PerfSettings.getFpsOpacity(context) / 100f
        val tv = CrtUi.text(ui, CrtUi.Text.LABEL).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, CrtUi.dimen(ui, size).toFloat())
            val h = CrtUi.dp(ui, 6f)
            val v = CrtUi.dp(ui, 3f)
            setPadding(h, v, h, v)
            setBackgroundColor(Color.argb((170 * opacity).toInt(), 0, 0, 0)) // legible sobre cualquier imagen
            alpha = max(0.3f, opacity)
        }
        val corner = PerfSettings.getFpsCorner(context)
        val margin = CrtUi.dp(ui, 8f)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (corner == PerfSettings.TOP_LEFT || corner == PerfSettings.TOP_RIGHT) Gravity.TOP else Gravity.BOTTOM) or
                    (if (corner == PerfSettings.TOP_LEFT || corner == PerfSettings.BOTTOM_LEFT) Gravity.START else Gravity.END)
            x = margin
            y = margin
        }
        return try {
            windowManager.addView(tv, lp)
            view = tv
            tv
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar el contador de FPS: ${e.message}")
            null
        }
    }

    private fun hide() {
        view?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    companion object {
        private const val TAG = "FpsCounter"
        private val WARN_COLOR = Color.rgb(255, 80, 80)
    }
}
