package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Barras negras por lado (T08): pinta de negro, encima de la pantalla real, los lados de FUERA de la zona del shader que el
 * usuario elija (izquierda, derecha, arriba, abajo). Ventanas de sistema sin toques (los gestos pasan a la app de debajo) que solo
 * existen mientras haya alguna barra que pintar.
 *
 * R17: una ventana opaca por barra, del tamaño de la barra. Antes era una sola ventana translúcida a pantalla completa que pintaba
 * rectángulos: el compositor mezclaba una capa entera en cada refresco y seguía componiendo lo que las barras tapaban. Opacas, lo de
 * debajo de cada barra no se compone.
 *
 * Sigue al overlay del shader: se hace transparente igual que él cuando se suspende (barra de estado, pausa, editor de zona).
 */
class BlackBars(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = ArrayList<View>()
    private val params = ArrayList<WindowManager.LayoutParams>()
    private var suspended = false

    /**
     * Se llama al crear ventanas de barras. Entre ventanas del mismo tipo la última añadida queda encima, así que quien tenga
     * ventanas que deben verse sobre las barras (botón y menú flotantes) las vuelve a subir aquí.
     */
    var onAdded: (() -> Unit)? = null

    /** Pone las barras en estos rectángulos (coordenadas de pantalla); sin rectángulos, las ventanas se quitan. */
    fun update(rects: List<Rect>) {
        // Sobran ventanas: fuera las del final
        while (views.size > rects.size) removeAt(views.size - 1)
        // Las que ya existen se mueven y se redimensionan
        for (i in views.indices) {
            val lp = params[i]
            if (place(lp, rects[i])) {
                try { windowManager.updateViewLayout(views[i], lp) } catch (e: Exception) { Log.e(TAG, "updateViewLayout: ${e.message}") }
            }
        }
        // Faltan ventanas: se añaden
        var added = false
        for (i in views.size until rects.size) {
            val lp = newParams()
            place(lp, rects[i])
            val v = View(context).apply { setBackgroundColor(Color.BLACK) }
            try {
                windowManager.addView(v, lp)
                views.add(v)
                params.add(lp)
                added = true
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo añadir una barra negra: ${e.message}")
                break
            }
        }
        if (added) {
            Log.i(TAG, "Barras negras: ${rects.size} (${rects.joinToString { "${it.width()}x${it.height()}" }})")
            onAdded?.invoke()
        }
    }

    private fun newParams() = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.RGBX_8888 // R17: opaca (como el overlay en Captura, R03)
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = if (suspended) 0f else 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        } else {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Posición y tamaño de la ventana = el rectángulo. Devuelve true si cambió algo. */
    private fun place(lp: WindowManager.LayoutParams, r: Rect): Boolean {
        if (lp.x == r.left && lp.y == r.top && lp.width == r.width() && lp.height == r.height()) return false
        lp.x = r.left
        lp.y = r.top
        lp.width = r.width()
        lp.height = r.height()
        return true
    }

    /** Transparentes mientras el overlay del shader está suspendido. */
    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        for (i in views.indices) {
            val lp = params[i]
            lp.alpha = if (value) 0f else 1f
            try { windowManager.updateViewLayout(views[i], lp) } catch (e: Exception) { Log.e(TAG, "setSuspended: ${e.message}") }
        }
    }

    fun remove() {
        while (views.isNotEmpty()) removeAt(views.size - 1)
    }

    private fun removeAt(i: Int) {
        val v = views.removeAt(i)
        params.removeAt(i)
        try { windowManager.removeView(v) } catch (e: Exception) { Log.e(TAG, "removeView: ${e.message}") }
    }

    companion object {
        private const val TAG = "BlackBars"
    }
}
