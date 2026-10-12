package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.sanabria.shadervulkan.ui.CrtTheme
import androidx.core.graphics.ColorUtils
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ScreenLayout
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.model.ShaderZone
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.ui.CrtUi
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editor de la zona del shader (T06): una ventana táctil a pantalla completa sobre la app, con un rectángulo que se mueve y
 * se redimensiona con tiradores. Mientras está abierto el servicio suspende el overlay del shader para ver la app real y
 * encajar el rectángulo (p. ej. sobre la pantallita de una carcasa de Game Boy). Se guarda una zona POR ORIENTACIÓN.
 *
 * Modo dual (T16): "+ ZONA B" añade un segundo rectángulo (A y B, etiquetados). Se toca uno para elegirlo y LOCK actúa sobre el
 * elegido; no pueden solaparse (el que se arrastra se para en el borde del otro y se pega a él cerca). "IGUALAR" da a B el
 * tamaño de A (el caso de la DS) y "QUITAR B" vuelve a una sola zona.
 *
 * Al pulsar OK la zona se guarda, el selector pasa a "Manual" y se aplica (el overlay cambia de tamaño y el shader se
 * recompila si hace falta); mientras se arrastra no se toca el motor.
 */
class ZoneEditor(private val context: Context) {

    private var windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var onClose: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    /**
     * Abre el editor en una pantalla física (T17): la 0 (la de siempre, con zonas A/B del modo dual) o la segunda, donde se
     * edita su zona propia (una sola, sin dual). Con las dos pantallas en uso, el botón PANTALLA salta de una a otra.
     */
    fun show(displayId: Int = Display.DEFAULT_DISPLAY, onClose: () -> Unit) {
        if (root != null) return
        this.onClose = onClose

        val second = displayId != Display.DEFAULT_DISPLAY
        val wctx = ScreenLayout.windowContext(context, displayId)
        windowManager = wctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (sw, sh) = ShaderZone.screenSize(windowManager)
        val landscape = sw >= sh
        val density = wctx.resources.displayMetrics.density

        // Rectángulo inicial: la zona actual; si no hay (pantalla completa o Manual sin dibujar), uno centrado con la forma de la
        // resolución elegida (10:9 en Game Boy…); sin resolución con aspecto propio, uno libre al 70 %
        val (virtW, virtH) = ShaderPreferences.getVirtualResolution(context)
        val (virtWB, virtHB) = ShaderPreferences.getVirtualResolutionB(context)
        val resIdx = ShaderPreferences.getVirtualResolutionIndex(context)
        var dual = !second && ShaderPreferences.isDual(context)
        val current = if (second) ShaderZone.zoneForSecond(context, sw, sh) else ShaderZone.currentRect(context, sw, sh)
        val resRect = ShaderZone.rectForAspect(ShaderZone.aspectForResolution(resIdx, virtW, virtH), sw, sh)
        val start = current ?: resRect ?: Rect((sw * 0.15f).roundToInt(), (sh * 0.15f).roundToInt(),
            (sw * 0.85f).roundToInt(), (sh * 0.85f).roundToInt())
        // Si la zona inicial viene de un aspecto o de la resolución, los tiradores mantienen la forma (se suelta con el botón LOCK)
        val storedA = if (second) ShaderPreferences.getSecondZone(context, landscape) != null
                      else ShaderPreferences.getManualZone(context, landscape) != null
        val aspectLocked = if (dual) !storedA else if (current != null) (if (second) !storedA else !ShaderZone.isManual(context)) else resRect != null
        var gridAdapt = ShaderPreferences.isGridAdapt(context)

        val ui = CrtUi.themed(wctx)
        val font = CrtUi.font(ui)
        val gap = CrtUi.dimen(ui, R.dimen.crt_space_s)

        val zoneA = Zone("A", RectF(start), if (aspectLocked) start.width().toFloat() / start.height() else 0f, virtW, virtH)
        val zones = mutableListOf(zoneA)
        if (dual) {
            val (a, b) = ShaderZone.dualRects(context, sw, sh)
            zoneA.rect.set(RectF(a))
            val storedB = ShaderPreferences.getManualZoneB(context, landscape) != null
            zoneA.lockRatio = if (storedA) 0f else a.width().toFloat() / a.height()
            zones.add(Zone("B", RectF(b), if (storedB) 0f else b.width().toFloat() / b.height(), virtWB, virtHB))
        }

        val zoneView = ZoneView(ui, sw, sh, zones, font, resIdx)
        zoneView.gridAdapt = gridAdapt

        val bothScreens = ScreenLayout.effective(context) == ScreenLayout.BOTH
        val hint = CrtUi.text(ui, CrtUi.Text.LABEL,
            ui.getString(if (landscape) R.string.zone_hint_landscape else R.string.zone_hint_portrait)).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, gap)
        }
        val cancelBtn = CrtUi.button(ui, ui.getString(R.string.btn_cancel))
        val fullBtn = CrtUi.button(ui, ui.getString(R.string.btn_full), CrtUi.Btn.WARN)
        val lockBtn = CrtUi.button(ui, "", CrtUi.Btn.TOGGLE)
        val okBtn = CrtUi.button(ui, ui.getString(R.string.btn_ok), CrtUi.Btn.PRIMARY)

        fun refreshLockBtn() {
            lockBtn.isSelected = zoneView.lockRatio > 0f
            lockBtn.setText(if (zoneView.lockRatio > 0f) R.string.zone_lock_on else R.string.zone_lock_off)
        }
        refreshLockBtn()
        zoneView.onSelected = { refreshLockBtn() }

        val buttons = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(cancelBtn, fullBtn, lockBtn, okBtn).forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = gap * 3 / 4 })
            }
        }
        // REJILLA FIJA: el shader usa la rejilla exacta de la resolución elegida, la zona tenga la forma que tenga (el píxel puede
        // salir rectangular). REJILLA ADAPTADA: la rejilla sigue a la forma de la zona y el píxel conserva su forma (cuadrado en
        // Game Boy, GBA y Custom). Solo tiene sentido con una resolución fija: con Auto la rejilla ya es la de la zona.
        val gridBtn = CrtUi.button(ui, "")
        fun refreshGridBtn() {
            gridBtn.setText(if (gridAdapt) R.string.zone_grid_adapted else R.string.zone_grid_fixed)
        }
        refreshGridBtn()
        gridBtn.setOnClickListener {
            gridAdapt = !gridAdapt
            zoneView.gridAdapt = gridAdapt
            refreshGridBtn()
            zoneView.invalidate()
        }
        val gridRow = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
            addView(gridBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            visibility = if (virtW > 0f && virtH > 0f) View.VISIBLE else View.GONE
        }

        // Modo dual (T16): + ZONA B / QUITAR B, e IGUALAR cuando hay dos zonas
        val dualBtn = CrtUi.button(ui, "")
        val equalBtn = CrtUi.button(ui, ui.getString(R.string.zone_equalize))
        val dualRow = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
            addView(dualBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(equalBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap * 3 / 4 })
        }
        fun refreshDualUi() {
            dualBtn.setText(if (dual) R.string.zone_remove_b else R.string.zone_add_b)
            equalBtn.visibility = if (dual) View.VISIBLE else View.GONE
            hint.setText(when {
                dual -> R.string.zone_hint_dual
                landscape -> R.string.zone_hint_landscape
                else -> R.string.zone_hint_portrait
            })
        }
        refreshDualUi()
        if (second) dualRow.visibility = View.GONE // la segunda pantalla tiene una sola zona

        // PANTALLA (T17): con shader en las dos pantallas, salta el editor de una a otra (lo no guardado de esta se descarta)
        val screenBtn = CrtUi.button(ui, ui.getString(if (second) R.string.zone_screen_bottom else R.string.zone_screen_top))
        val screenRow = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, gap)
            addView(screenBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            visibility = if (bothScreens) View.VISIBLE else View.GONE
        }
        screenBtn.setOnClickListener {
            val other = if (second) Display.DEFAULT_DISPLAY else (ScreenLayout.secondDisplay(context)?.displayId ?: return@setOnClickListener)
            switchTo(other)
        }

        // Ajuste fino: FINO abre MOVER/TAM y cuatro flechas que mueven o cambian el tamaño de la zona elegida de 1 en 1 px
        // (mantener pulsado repite). El imán del arrastre sigue igual; esto es para el último píxel.
        val fineBtn = CrtUi.button(ui, ui.getString(R.string.zone_fine), CrtUi.Btn.TOGGLE)
        val modeBtn = CrtUi.button(ui, ui.getString(R.string.zone_fine_move), CrtUi.Btn.TOGGLE)
        var fineResize = false
        fun arrow(label: String, move: Pair<Int, Int>) = CrtUi.button(ui, label).also { b ->
            repeatWhileHeld(b) {
                if (fineResize) zoneView.resizeBy(move.first, move.second) else zoneView.nudge(move.first, move.second)
            }
        }
        val arrows = listOf(arrow("<", -1 to 0), arrow(">", 1 to 0), arrow("^", 0 to -1), arrow("v", 0 to 1))
        val fineRow = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
            (listOf(fineBtn, modeBtn) + arrows).forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i < 2) 1.6f else 1f).apply { if (i > 0) marginStart = gap / 2 })
            }
        }
        fun refreshFineUi() {
            val on = fineBtn.isSelected
            (listOf<View>(modeBtn) + arrows).forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            modeBtn.setText(if (fineResize) R.string.zone_fine_size else R.string.zone_fine_move)
            modeBtn.isSelected = fineResize
            zoneView.showPos = on
            zoneView.invalidate()
        }
        fineBtn.setOnClickListener { fineBtn.isSelected = !fineBtn.isSelected; refreshFineUi() }
        modeBtn.setOnClickListener { fineResize = !fineResize; refreshFineUi() }
        refreshFineUi()

        val bar = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            CrtUi.asScreen(this)
            val p = CrtUi.dimen(ui, R.dimen.crt_space_m)
            setPadding(p, p, p, p)
            addView(screenRow)
            addView(hint)
            addView(buttons)
            addView(gridRow)
            addView(dualRow)
            addView(fineRow)
        }
        // El panel de botones no debe tapar los rectángulos: abajo, o arriba si llegan muy abajo
        val barParams = FrameLayout.LayoutParams(
            min(sw - (24 * density).toInt(), (460 * density).toInt()),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        fun placeBar() {
            barParams.gravity = if (zoneView.lowestBottom() > sh * 0.70f) Gravity.TOP or Gravity.CENTER_HORIZONTAL
                                else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            barParams.topMargin = (56 * density).toInt()
            barParams.bottomMargin = (56 * density).toInt()
            bar.layoutParams = barParams
        }
        placeBar()
        zoneView.onChanged = { placeBar() }

        dualBtn.setOnClickListener {
            if (dual) {
                dual = false
                zoneView.removeSecond()
            } else {
                dual = true
                // B con la forma inicial de la resolución; si no cabe junto a la A de ahora, las dos vuelven a la forma inicial
                val (da, db) = ShaderZone.defaultDualRects(context, sw, sh)
                val a = RectF(zoneView.zones[0].rect)
                val keepA = !RectF.intersects(a, RectF(db))
                if (!keepA) {
                    zoneView.zones[0].rect.set(RectF(da))
                    zoneView.zones[0].lockRatio = da.width().toFloat() / da.height()
                }
                zoneView.addSecond(Zone("B", RectF(db), db.width().toFloat() / db.height(), virtWB, virtHB))
            }
            refreshDualUi()
            refreshLockBtn()
            placeBar()
            zoneView.invalidate()
        }
        equalBtn.setOnClickListener {
            if (!zoneView.equalize()) {
                // Un Toast saldría detrás de esta ventana: el aviso va en el texto de ayuda, que vuelve solo
                hint.setText(R.string.zone_equalize_no_fit)
                hint.postDelayed({ refreshDualUi() }, 3000)
            }
            zoneView.invalidate()
        }

        cancelBtn.setOnClickListener { close() }
        fullBtn.setOnClickListener {
            if (second) {
                // Segunda pantalla entera (guardado a propósito: sin zona guardada valdría el aspecto del selector)
                ShaderPreferences.setSecondZone(context, landscape, floatArrayOf(0f, 0f, 1f, 1f))
            } else {
                // Esta orientación sin zona: pantalla completa (y sin segunda zona)
                ShaderPreferences.setManualZone(context, landscape, null)
                ShaderPreferences.setManualZoneB(context, landscape, null)
                ShaderPreferences.setDual(context, false)
                ShaderPreferences.setAspectRatioIndex(context, ShaderZone.MANUAL_INDEX)
            }
            VulkanEngineBridge.refreshZone()
            close()
        }
        lockBtn.setOnClickListener {
            val z = zoneView.zones[zoneView.selected]
            z.lockRatio = if (z.lockRatio > 0f) 0f else z.rect.width() / z.rect.height()
            refreshLockBtn()
            zoneView.invalidate()
        }
        okBtn.setOnClickListener {
            fun fractions(r: Rect) = if (r.left <= 0 && r.top <= 0 && r.right >= sw && r.bottom >= sh) null else ShaderZone.fractionsOf(r, sw, sh)
            val rects = zoneView.zones.map { it.currentRect(sw, sh) }
            val isDual = dual && rects.size > 1
            if (second) {
                // Zona propia de la segunda pantalla; no toca el aspecto, el dual ni la zona de la pantalla principal
                ShaderPreferences.setSecondZone(context, landscape, ShaderZone.fractionsOf(rects[0], sw, sh))
                ShaderPreferences.setGridAdapt(context, gridAdapt)
                Log.i(TAG, "Zona de la segunda pantalla (${if (landscape) "horizontal" else "vertical"}): ${rects[0]} de ${sw}x$sh")
                VulkanEngineBridge.refreshZone()
                close()
                return@setOnClickListener
            }
            if (isDual) {
                // Con dos zonas se guardan las dos tal cual (ninguna ocupa la pantalla entera: se solaparían)
                ShaderPreferences.setManualZone(context, landscape, ShaderZone.fractionsOf(rects[0], sw, sh))
                ShaderPreferences.setManualZoneB(context, landscape, ShaderZone.fractionsOf(rects[1], sw, sh))
            } else {
                ShaderPreferences.setManualZone(context, landscape, fractions(rects[0]))
                ShaderPreferences.setManualZoneB(context, landscape, null)
            }
            ShaderPreferences.setDual(context, isDual)
            ShaderPreferences.setAspectRatioIndex(context, ShaderZone.MANUAL_INDEX)
            ShaderPreferences.setGridAdapt(context, gridAdapt)
            Log.i(TAG, "Zonas manuales (${if (landscape) "horizontal" else "vertical"}): $rects de ${sw}x$sh, dual=$dual")
            VulkanEngineBridge.refreshZone()
            close()
        }

        val container = FrameLayout(wctx).apply {
            addView(zoneView, FrameLayout.LayoutParams(-1, -1))
            addView(bar)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(container, lp)
            root = container
            Log.i(TAG, "Editor de zona abierto (${sw}x$sh, ${if (landscape) "horizontal" else "vertical"}), zonas iniciales ${zones.map { it.rect }}")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el editor de zona: ${e.message}")
            this.onClose?.invoke()
            this.onClose = null
        }
    }

    /** Ejecuta [action] al tocar el botón y, si se mantiene pulsado, la repite (despacio al principio y más rápido después). */
    private fun repeatWhileHeld(b: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var n = 0
        val tick = object : Runnable {
            override fun run() {
                action()
                n++
                handler.postDelayed(this, if (n > 12) 25L else 90L)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; n = 0; action(); handler.postDelayed(tick, 400L) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(tick) }
            }
            true
        }
        // Si el editor se cierra con el dedo puesto, que no siga repitiendo
        b.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(tick) }
        })
    }

    /** Pasa el editor a otra pantalla sin cerrar la sesión de edición (el aviso de cierre se conserva para cuando acabe). */
    private fun switchTo(displayId: Int) {
        val view = root ?: return
        val cb = onClose
        root = null
        try { windowManager.removeView(view) } catch (e: Exception) { Log.e(TAG, "removeView: ${e.message}") }
        onClose = null
        show(displayId, cb ?: {})
    }

    /** Cierra el editor sin guardar (p. ej. al girar la pantalla: la zona dibujada en una orientación no vale en la otra). */
    fun cancel() = close()

    private fun close() {
        val view = root ?: return
        root = null
        try { windowManager.removeView(view) } catch (e: Exception) { Log.e(TAG, "removeView: ${e.message}") }
        val cb = onClose
        onClose = null
        cb?.invoke()
    }

    /** Una zona del editor: su rectángulo, su proporción bloqueada (0 = libre) y la rejilla del shader que le toca. */
    private class Zone(val label: String, val rect: RectF, var lockRatio: Float, val virtW: Float, val virtH: Float) {
        /** Rectángulo en píxeles enteros, dentro de la pantalla. */
        fun currentRect(sw: Int, sh: Int): Rect {
            val l = rect.left.roundToInt().coerceIn(0, sw - 1)
            val t = rect.top.roundToInt().coerceIn(0, sh - 1)
            return Rect(l, t, rect.right.roundToInt().coerceIn(l + 1, sw), rect.bottom.roundToInt().coerceIn(t + 1, sh))
        }
    }

    /**
     * Vista con los rectángulos. Coordenadas de pantalla (la ventana ocupa toda la pantalla, también bajo el notch). Los gestos
     * actúan sobre el rectángulo elegido ([selected]); tocar el interior del otro lo elige. [Zone.lockRatio] > 0 mantiene
     * ancho/alto al redimensionar; 0 = libre.
     */
    private class ZoneView(
        ctx: Context, private val sw: Int, private val sh: Int, val zones: MutableList<Zone>, font: Typeface,
        private val resIdx: Int
    ) : View(ctx) {

        var selected = 0
            private set
        val rect: RectF get() = zones[selected].rect
        var lockRatio: Float
            get() = zones[selected].lockRatio
            set(v) { zones[selected].lockRatio = v }
        var gridAdapt = false
        var onChanged: (() -> Unit)? = null
        var onSelected: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val handleR = 11 * density
        private val touchR = 44 * density
        private val minSize = 72 * density
        private val snap = 28 * density   // a esta distancia de un borde de la pantalla (o del otro rectángulo), el rectángulo se pega a él

        // Posición sin imán mientras se arrastra el interior (si no, el imán no dejaría separarse del borde)
        private var rawL = 0f
        private var rawT = 0f

        private enum class Drag { NONE, MOVE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM }
        private var drag = Drag.NONE
        private var lastX = 0f
        private var lastY = 0f

        // Colores y tamaños de la interfaz CRT: filo de fósforo con glow, rejilla tenue, tiradores cuadrados (a píxeles) y textos con
        // sombra oscura, que se leen encima de cualquier juego
        private val hot = CrtTheme.color(ctx, R.attr.crtHot)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hot; style = Paint.Style.STROKE; strokeWidth = 2f * density
            setShadowLayer(4f * density * CrtTheme.number(ctx, R.attr.crtGlowM) / 9f, 0f, 0f, CrtTheme.color(ctx, R.attr.crtGlowHot))
        }
        // La zona no elegida: filo tenue y sin glow
        private val dimBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(CrtTheme.color(ctx, R.attr.crtPhosphor), 150); style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(CrtTheme.color(ctx, R.attr.crtPhosphor), 90); style = Paint.Style.STROKE; strokeWidth = 1f * density
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CrtTheme.color(ctx, R.attr.crtPanelSolid); style = Paint.Style.FILL }
        private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hot; style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hot; textSize = resources.getDimension(R.dimen.crt_text_body); typeface = font; textAlign = Paint.Align.CENTER
            setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hot; textSize = resources.getDimension(R.dimen.crt_text_display); typeface = font; textAlign = Paint.Align.LEFT
            setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
        }
        private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CrtTheme.color(ctx, R.attr.crtWarn); textSize = resources.getDimension(R.dimen.crt_text_label); typeface = font; textAlign = Paint.Align.CENTER
            setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
        }
        private val outsideDim = ColorUtils.setAlphaComponent(CrtTheme.color(ctx, R.attr.crtBgEdge), 170)

        fun lowestBottom(): Float = zones.maxOf { it.rect.bottom }

        fun addSecond(zone: Zone) {
            if (zones.size < 2) zones.add(zone)
            selected = 1
            onSelected?.invoke()
        }

        fun removeSecond() {
            while (zones.size > 1) zones.removeAt(zones.size - 1)
            selected = 0
            onSelected?.invoke()
        }

        /** B con el tamaño de A (conserva el centro de B, dentro de la pantalla). false si así se solaparía con A. */
        fun equalize(): Boolean {
            if (zones.size < 2) return false
            val a = zones[0].rect
            val b = zones[1].rect
            val w = a.width(); val h = a.height()
            val l = (b.centerX() - w / 2f).coerceIn(0f, sw - w)
            val t = (b.centerY() - h / 2f).coerceIn(0f, sh - h)
            val candidate = RectF(l, t, l + w, t + h)
            if (RectF.intersects(candidate, a)) return false
            b.set(candidate)
            zones[1].lockRatio = if (zones[0].lockRatio > 0f) w / h else 0f
            onChanged?.invoke()
            return true
        }

        /** Modo FINO: muestra la posición de cada zona junto a su tamaño. */
        var showPos = false

        /** Ajuste fino: mueve la zona elegida [dx],[dy] píxeles (sin imán). false si se saldría de la pantalla o chocaría con la otra. */
        fun nudge(dx: Int, dy: Int): Boolean {
            val r = rect
            val w = r.width().roundToInt(); val h = r.height().roundToInt()
            val l = r.left.roundToInt() + dx; val t = r.top.roundToInt() + dy
            if (l < 0 || t < 0 || l + w > sw || t + h > sh) return false
            val cand = RectF(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat())
            if (overlapsOther(cand)) return false
            r.set(cand); rawL = cand.left; rawT = cand.top
            invalidate(); onChanged?.invoke()
            return true
        }

        /**
         * Ajuste fino: cambia el tamaño [dw],[dh] píxeles dejando fija la esquina de arriba a la izquierda. Con la proporción
         * bloqueada, el otro lado sigue al que se toca (redondeado al píxel).
         */
        fun resizeBy(dw: Int, dh: Int): Boolean {
            val r = rect
            var w = r.width().roundToInt() + dw; var h = r.height().roundToInt() + dh
            if (lockRatio > 0f) {
                if (dw != 0) h = (w / lockRatio).roundToInt() else if (dh != 0) w = (h * lockRatio).roundToInt()
            }
            val l = r.left.roundToInt(); val t = r.top.roundToInt()
            if (w < minSize || h < minSize || l + w > sw || t + h > sh) return false
            val cand = RectF(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat())
            if (overlapsOther(cand)) return false
            r.set(cand)
            invalidate(); onChanged?.invoke()
            return true
        }

        private fun others(): List<RectF> = zones.filterIndexed { i, _ -> i != selected }.map { it.rect }

        private fun overlapsOther(r: RectF): Boolean = others().any { RectF.intersects(r, it) }

        override fun onDraw(canvas: Canvas) {
            // Fuera de las zonas, la pantalla real oscurecida: dentro, lo que verá el shader
            canvas.save()
            for (z in zones) canvas.clipOutRect(z.rect)
            canvas.drawColor(outsideDim)
            canvas.restore()

            zones.forEachIndexed { i, z ->
                val r = z.rect
                val isSel = i == selected
                canvas.drawRect(r, if (isSel) borderPaint else dimBorderPaint)
                val w3 = r.width() / 3f
                val h3 = r.height() / 3f
                for (k in 1..2) {
                    canvas.drawLine(r.left + w3 * k, r.top, r.left + w3 * k, r.bottom, gridPaint)
                    canvas.drawLine(r.left, r.top + h3 * k, r.right, r.top + h3 * k, gridPaint)
                }
                if (zones.size > 1) canvas.drawText(z.label, r.left + 8 * density, r.top + labelPaint.textSize + 4 * density, labelPaint)

                val cr = z.currentRect(sw, sh)
                if (showPos) canvas.drawText(resources.getString(R.string.zone_pos, cr.left, cr.top), r.centerX(), r.centerY() - textPaint.textSize * 1.2f, textPaint)
                canvas.drawText(resources.getString(if (z.lockRatio > 0f) R.string.zone_size_locked else R.string.zone_size, cr.width(), cr.height()), r.centerX(), r.centerY() + textPaint.textSize / 3f, textPaint)
                // Con qué resolución trabaja el shader dentro de la zona (la de la pantalla es solo el tamaño de la ventana)
                val grid = ShaderZone.gridFor(resIdx, z.virtW, z.virtH, cr.width(), cr.height(), gridAdapt)
                val inner = if (grid != null) resources.getString(if (grid.adapted) R.string.zone_inner_adapted else R.string.zone_inner_fixed, grid.gridW, grid.gridH)
                            else resources.getString(R.string.zone_inner_auto, cr.width(), cr.height())
                canvas.drawText(inner, r.centerX(), r.centerY() + textPaint.textSize * 1.6f, textPaint)
                if (grid != null && grid.distorted) {
                    canvas.drawText(resources.getString(R.string.zone_distorted), r.centerX(), r.centerY() + textPaint.textSize * 2.9f, warnPaint)
                }
            }

            // Tiradores de esquina; los de borde solo con proporción libre. Solo en la zona elegida
            for ((x, y) in corners()) {
                canvas.drawRect(x - handleR, y - handleR, x + handleR, y + handleR, handleFill)
                canvas.drawRect(x - handleR, y - handleR, x + handleR, y + handleR, handleLine)
            }
            if (lockRatio <= 0f) {
                val cx = rect.centerX(); val cy = rect.centerY(); val s = handleR * 0.8f
                for ((x, y) in listOf(cx to rect.top, cx to rect.bottom, rect.left to cy, rect.right to cy)) {
                    canvas.drawRect(x - s, y - s, x + s, y + s, handleFill)
                    canvas.drawRect(x - s, y - s, x + s, y + s, handleLine)
                }
            }
        }

        private fun corners() = listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = hit(e.x, e.y)
                    if (drag == Drag.NONE) {
                        // Tocar el interior de la otra zona la elige (y ya se puede arrastrar)
                        val other = zones.indices.firstOrNull { it != selected && zones[it].rect.contains(e.x, e.y) }
                        if (other != null) {
                            selected = other
                            onSelected?.invoke()
                            drag = Drag.MOVE
                            invalidate()
                        }
                    }
                    lastX = e.x; lastY = e.y
                    rawL = rect.left; rawT = rect.top
                    return drag != Drag.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drag == Drag.NONE) return false
                    val dx = e.x - lastX; val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    val before = RectF(rect)
                    apply(snapToEdge(e.x, sw), snapToEdge(e.y, sh), dx, dy)
                    // Sin solaparse con la otra zona: el redimensionado se para en su borde (el movimiento se desliza, ver apply)
                    if (drag != Drag.MOVE && overlapsOther(rect)) rect.set(before)
                    invalidate()
                    onChanged?.invoke()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = Drag.NONE; return true }
            }
            return true
        }

        private fun hit(x: Float, y: Float): Drag {
            val cs = listOf(Drag.TL to (rect.left to rect.top), Drag.TR to (rect.right to rect.top),
                            Drag.BL to (rect.left to rect.bottom), Drag.BR to (rect.right to rect.bottom))
            val nearest = cs.minByOrNull { hypot(x - it.second.first, y - it.second.second) }!!
            if (hypot(x - nearest.second.first, y - nearest.second.second) < touchR) return nearest.first
            if (lockRatio <= 0f) {
                val cx = rect.centerX(); val cy = rect.centerY()
                if (hypot(x - cx, y - rect.top) < touchR && rect.height() > touchR * 2) return Drag.TOP
                if (hypot(x - cx, y - rect.bottom) < touchR && rect.height() > touchR * 2) return Drag.BOTTOM
                if (hypot(x - rect.left, y - cy) < touchR && rect.width() > touchR * 2) return Drag.LEFT
                if (hypot(x - rect.right, y - cy) < touchR && rect.width() > touchR * 2) return Drag.RIGHT
            }
            return if (rect.contains(x, y)) Drag.MOVE else Drag.NONE
        }

        /** Posición del dedo dentro de la pantalla; a menos de [snap] de un borde se pega a él (con el protector de pantalla no se llega). */
        private fun snapToEdge(v: Float, size: Int): Float = when {
            v <= snap -> 0f
            v >= size - snap -> size.toFloat()
            else -> v
        }

        private fun apply(x: Float, y: Float, dx: Float, dy: Float) {
            when (drag) {
                Drag.MOVE -> {
                    val w = rect.width(); val h = rect.height()
                    rawL = (rawL + dx).coerceIn(0f, sw - w)
                    rawT = (rawT + dy).coerceIn(0f, sh - h)
                    // Imán: un lado del rectángulo cerca de un borde de la pantalla se pega a él
                    var nl = when { rawL <= snap -> 0f; rawL + w >= sw - snap -> sw - w; else -> rawL }
                    var nt = when { rawT <= snap -> 0f; rawT + h >= sh - snap -> sh - h; else -> rawT }
                    // ... y a los bordes de la otra zona, si se tocan en el otro eje
                    for (o in others()) {
                        if (nt < o.bottom && nt + h > o.top) {
                            if (abs(nl + w - o.left) < snap) nl = o.left - w else if (abs(nl - o.right) < snap) nl = o.right
                        }
                        if (nl < o.right && nl + w > o.left) {
                            if (abs(nt + h - o.top) < snap) nt = o.top - h else if (abs(nt - o.bottom) < snap) nt = o.bottom
                        }
                    }
                    // Sin solaparse: si la posición pedida choca, se desliza por el eje que sí cabe (o se queda donde estaba)
                    val ol = rect.left; val ot = rect.top
                    fun fits(l: Float, t: Float) = !overlapsOther(RectF(l, t, l + w, t + h))
                    when {
                        fits(nl, nt) -> rect.set(nl, nt, nl + w, nt + h)
                        fits(nl, ot) -> rect.set(nl, ot, nl + w, ot + h)
                        fits(ol, nt) -> rect.set(ol, nt, ol + w, nt + h)
                    }
                }
                Drag.LEFT -> rect.left = x.coerceIn(0f, rect.right - minSize)
                Drag.RIGHT -> rect.right = x.coerceIn(rect.left + minSize, sw.toFloat())
                Drag.TOP -> rect.top = y.coerceIn(0f, rect.bottom - minSize)
                Drag.BOTTOM -> rect.bottom = y.coerceIn(rect.top + minSize, sh.toFloat())
                Drag.TL, Drag.TR, Drag.BL, Drag.BR -> resizeCorner(x, y)
                Drag.NONE -> {}
            }
        }

        /** La esquina opuesta queda fija; con proporción bloqueada, ancho/alto se mantienen. */
        private fun resizeCorner(x: Float, y: Float) {
            val ax = if (drag == Drag.TL || drag == Drag.BL) rect.right else rect.left     // ancla (esquina opuesta)
            val ay = if (drag == Drag.TL || drag == Drag.TR) rect.bottom else rect.top
            val signX = if (x >= ax) 1f else -1f
            val signY = if (y >= ay) 1f else -1f
            var w = max(abs(x - ax), minSize)
            var h = max(abs(y - ay), minSize)
            if (lockRatio > 0f) {
                if (w / h > lockRatio) w = h * lockRatio else h = w / lockRatio
                // No salir de la pantalla: se reduce manteniendo la proporción
                val maxW = if (signX > 0) sw - ax else ax
                val maxH = if (signY > 0) sh - ay else ay
                val k = min(1f, min(maxW / w, maxH / h))
                w *= k; h *= k
            } else {
                w = min(w, if (signX > 0) sw - ax else ax)
                h = min(h, if (signY > 0) sh - ay else ay)
            }
            val nx = ax + signX * w
            val ny = ay + signY * h
            rect.set(min(ax, nx), min(ay, ny), max(ax, nx), max(ay, ny))
        }
    }

    companion object {
        private const val TAG = "ZoneEditor"
    }
}
