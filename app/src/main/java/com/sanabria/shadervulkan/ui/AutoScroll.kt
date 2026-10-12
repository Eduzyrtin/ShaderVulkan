package com.sanabria.shadervulkan.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.ceil

/**
 * Marquesina para textos que no caben (nombres de shader largos en botones, listas y cabeceras). En reposo el texto sale cortado con
 * "…"; pasado un momento se desliza hasta enseñar el final, espera, vuelve al principio y repite. Si cabe, no hace nada.
 *
 * Es propia (no `ellipsize="marquee"`) porque el marquee de Android solo corre con el foco o `isSelected`, que en los botones
 * tipo interruptor es el estado "encendido", y porque así cada vista decide cuándo anima.
 *
 * Uso: `AutoScroll.attach(textView)` una vez; el texto se puede cambiar después (se reinicia solo).
 */
object AutoScroll {
    private const val REST_BEFORE_MS = 1400L    // quieto (con "…") antes de empezar a deslizar
    private const val HOLD_END_MS = 900L        // quieto con el final a la vista
    private const val REST_AFTER_MS = 1800L     // quieto y cortado otra vez antes de repetir
    private const val SPEED_DP_PER_S = 38f

    fun attach(tv: TextView) {
        if (tv.getTag(TAG_KEY) != null) return
        tv.isSingleLine = true
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setTag(TAG_KEY, Controller(tv))
    }

    private val TAG_KEY = com.sanabria.shadervulkan.R.id.auto_scroll_controller

    private class Controller(private val tv: TextView) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener, TextWatcher {
        private val handler = Handler(Looper.getMainLooper())
        private var animator: ValueAnimator? = null
        private val begin = Runnable { startSlide() }

        init {
            tv.addOnAttachStateChangeListener(this)
            tv.addOnLayoutChangeListener(this)
            tv.addTextChangedListener(this)
            if (tv.isAttachedToWindow) schedule(REST_BEFORE_MS)
        }

        private fun schedule(delay: Long) {
            handler.removeCallbacks(begin)
            handler.postDelayed(begin, delay)
        }

        private fun rest() {
            animator?.cancel()
            animator = null
            handler.removeCallbacks(begin)
            tv.setHorizontallyScrolling(false)
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.scrollTo(0, 0)
        }

        private fun startSlide() {
            if (!tv.isAttachedToWindow || tv.visibility != View.VISIBLE) return
            val avail = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
            val overflow = ceil(tv.paint.measureText(tv.text.toString()) - avail).toInt()
            if (avail <= 0 || overflow <= 2) return // cabe: no hace falta
            // Sin "…" y sin ajuste de línea, el texto entero queda disponible para deslizarlo
            tv.ellipsize = null
            tv.setHorizontallyScrolling(true)
            val dpToPx = tv.resources.displayMetrics.density
            animator = ValueAnimator.ofInt(0, overflow).apply {
                duration = (overflow / (SPEED_DP_PER_S * dpToPx) * 1000f).toLong().coerceAtLeast(500L)
                interpolator = LinearInterpolator()
                addUpdateListener { tv.scrollTo(it.animatedValue as Int, 0) }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(a: Animator) { cancelled = true }
                    override fun onAnimationEnd(a: Animator) {
                        if (cancelled) return
                        handler.postDelayed({ rest(); schedule(REST_AFTER_MS) }, HOLD_END_MS)
                    }
                })
                start()
            }
        }

        override fun onViewAttachedToWindow(v: View) = schedule(REST_BEFORE_MS)
        override fun onViewDetachedFromWindow(v: View) = rest()

        // Cambió el tamaño (giro, relayout): se vuelve a medir desde cero
        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
            if (r - l != oldR - oldL) { rest(); schedule(REST_BEFORE_MS) }
        }

        // Texto nuevo (filas reutilizadas de una lista, shader cambiado): vuelta al principio
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { rest(); schedule(REST_BEFORE_MS) }
    }
}
