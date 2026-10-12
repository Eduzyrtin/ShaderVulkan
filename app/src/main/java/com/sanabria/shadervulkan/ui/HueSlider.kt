package com.sanabria.shadervulkan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.sanabria.shadervulkan.R
import kotlin.math.roundToInt

/**
 * Slider de tono: una barra con el arcoíris completo (los colores representativos de cada paleta, CrtTheme.swatches) y un cursor de
 * bloque, como el de los sliders de parámetros. [onChange] avisa mientras se arrastra (para la vista previa) y [onCommit] al soltar
 * o al dejar de pulsar el mando (para guardar y repintar la app, que cuesta una recreación).
 *
 * Con el mando (Thor) se enfoca y izquierda/derecha cambia de tono de uno en uno; mantenerlo pulsado recorre la barra.
 */
class HueSlider @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onChange: ((index: Int, fromUser: Boolean) -> Unit)? = null
    var onCommit: ((index: Int) -> Unit)? = null

    private val steps = CrtTheme.hueSteps(ctx)
    private val swatches = CrtTheme.swatches(ctx).copyOf(steps)
    private val density = resources.displayMetrics.density

    /** Tono mostrado (0 until steps). Con la paleta neutra no hay ninguno elegido: el cursor se dibuja apagado. */
    var index = 0
        set(value) {
            field = value.coerceIn(0, steps - 1)
            invalidate()
        }
    var neutral = false
        set(value) { field = value; invalidate() }

    private val trackH = 14 * density
    private val thumbW = 8 * density
    private val thumbH = 32 * density
    private val sidePad = 12 * density

    private val track = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }

    init {
        isFocusable = true
        isClickable = true
        minimumHeight = (48 * density).toInt()
        contentDescription = context.getString(R.string.hue_slider_description)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthSpec),
            resolveSize((48 * density).toInt(), heightSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        track.set(sidePad, h / 2f - trackH / 2, w - sidePad, h / 2f + trackH / 2)
        trackPaint.shader = LinearGradient(track.left, 0f, track.right, 0f, swatches, null, Shader.TileMode.CLAMP)
        // Scanlines suaves por encima del arcoíris, para que la barra sea del mismo tubo que el resto
        shadePaint.color = 0x22000000
    }

    private fun xOf(i: Int): Float = track.left + track.width() * i / (steps - 1)

    override fun onDraw(canvas: Canvas) {
        val hot = CrtTheme.color(context, R.attr.crtHot)
        val ink = CrtTheme.color(context, R.attr.crtInk)
        val r = 2 * density
        canvas.drawRoundRect(track, r, r, trackPaint)
        var y = track.top + 2 * density
        while (y < track.bottom) {
            canvas.drawRect(track.left, y, track.right, y + density, shadePaint)
            y += 3 * density
        }

        if (isFocused) {
            focusRing.color = hot
            canvas.drawRoundRect(track.left - 4 * density, track.top - 4 * density, track.right + 4 * density, track.bottom + 4 * density, r, r, focusRing)
        }

        val cx = xOf(index)
        val half = thumbW / 2
        thumbFill.color = if (neutral) CrtTheme.color(context, R.attr.crtDim) else hot
        thumbEdge.color = ink
        canvas.drawRect(cx - half, height / 2f - thumbH / 2, cx + half, height / 2f + thumbH / 2, thumbFill)
        canvas.drawRect(cx - half, height / 2f - thumbH / 2, cx + half, height / 2f + thumbH / 2, thumbEdge)
    }

    private fun indexAt(x: Float): Int =
        (((x - track.left) / track.width()) * (steps - 1)).roundToInt().coerceIn(0, steps - 1)

    private fun moveTo(i: Int) {
        if (i == index && !neutral) return
        neutral = false
        index = i
        onChange?.invoke(index, true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true) // dentro de un ScrollView
                moveTo(indexAt(e.x))
            }
            MotionEvent.ACTION_MOVE -> moveTo(indexAt(e.x))
            MotionEvent.ACTION_UP -> {
                moveTo(indexAt(e.x))
                performClick()
                onCommit?.invoke(index)
            }
            MotionEvent.ACTION_CANCEL -> onCommit?.invoke(index)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        moveTo(if (neutral) index else (index + delta).coerceIn(0, steps - 1))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            onCommit?.invoke(index)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}
