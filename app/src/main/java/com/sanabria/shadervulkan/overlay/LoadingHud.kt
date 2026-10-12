package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * LoadingHud — Panel flotante "LOADING_SHADER xx%" con estética de terminal CRT.
 * Consulta el progreso nativo cada 150 ms mientras hay una carga (o justo después de pedirla, [kick]) y cada 500 ms en reposo
 * (R08: antes despertaba el hilo principal cada 150 ms durante toda la sesión); aparece solo si hay una carga en curso y se retira
 * solo al terminar. No es táctil ni toma el foco, así que no estorba a la app capturada.
 */
class LoadingHud(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var bar: ProgressBar? = null
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val pct = try { VulkanEngineBridge.getLoadProgress() } catch (_: Throwable) { -1 }
            if (pct < 0) {
                removeView()
                // Carga terminada: si falló algún pase (o el preset no vale) se dice cuál, en vez de un fallo mudo
                val problem = try { VulkanEngineBridge.consumeLoadStatus() } catch (_: Throwable) { "" }
                if (problem.isNotBlank()) {
                    Log.w(TAG, "SHADER_ERROR: $problem")
                    Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                }
            } else {
                ensureView()
                title?.text = context.getString(R.string.loading_shader_pct, pct)
                detail?.text = try { VulkanEngineBridge.getLoadLabel() } catch (_: Throwable) { "" }
                bar?.progress = pct
            }
            val fast = pct >= 0 || SystemClock.uptimeMillis() < fastUntil
            handler.postDelayed(this, if (fast) POLL_MS else IDLE_POLL_MS)
        }
    }

    // Tras pedir una carga, unos segundos de sondeo rápido: la carga nativa empieza un poco después (lectura del preset)
    private var fastUntil = 0L

    /** Empieza a vigilar la carga (idempotente). */
    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    /** Se acaba de pedir una carga o algo que recompila (zona, giro): se mira ya y a menudo durante un rato (R08). */
    fun kick() {
        if (!running) return
        fastUntil = SystemClock.uptimeMillis() + KICK_FAST_MS
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /** Fuerza el panel por encima de ventanas añadidas después (se recrea en el siguiente sondeo). */
    fun bringToFront() {
        if (running) removeView()
    }

    /** Deja de vigilar y retira el panel. */
    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        removeView()
    }

    private fun ensureView() {
        if (root != null) return
        val ui = CrtUi.themed(context)

        val titleView = CrtUi.text(ui, CrtUi.Text.TITLE, context.getString(R.string.loading_shader_pct, 0))
        val detailView = CrtUi.text(ui, CrtUi.Text.CAPTION).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, CrtUi.dp(ui, 2f), 0, CrtUi.dimen(ui, R.dimen.crt_space_s))
        }
        val barView = CrtUi.progressBar(ui)

        val panel = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            CrtUi.asScreen(this)
            val h = CrtUi.dimen(ui, R.dimen.crt_space_l)
            val v = CrtUi.dimen(ui, R.dimen.crt_space_m)
            setPadding(h, v, h, v + CrtUi.dp(ui, 2f))
            addView(titleView)
            addView(detailView)
            addView(barView)
        }

        val lp = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.82f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = CrtUi.dp(ui, 72f)
        }

        try {
            windowManager.addView(panel, lp)
            root = panel
            title = titleView
            detail = detailView
            bar = barView
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar el HUD de carga: ${e.message}")
        }
    }

    private fun removeView() {
        root?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        root = null
        title = null
        detail = null
        bar = null
    }

    companion object {
        private const val TAG = "LoadingHud"
        private const val POLL_MS = 150L
        private const val IDLE_POLL_MS = 500L
        private const val KICK_FAST_MS = 3000L
    }
}
