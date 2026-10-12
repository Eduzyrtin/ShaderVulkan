package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ParamFavorites
import com.sanabria.shadervulkan.model.PerfSettings
import com.sanabria.shadervulkan.model.ScreenLayout
import com.sanabria.shadervulkan.model.ShaderParamStore
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.model.ShaderZone
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.services.SessionMode
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.ui.AutoScroll
import com.sanabria.shadervulkan.ui.CrtUi
import kotlin.math.abs

class OverlayControlManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var buttonView: DotView? = null
    private var params: WindowManager.LayoutParams? = null

    private var menuView: View? = null
    private val shaderPicker = ShaderPicker(context)
    private val shaderPickerB = ShaderPicker(context, zoneB = true) // preset propio de la zona B (T18)
    private val stackEditor = StackEditor(context, overlay = true) // combinar presets (T45)

    private var initialX = 0
    private var initialY = 0
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var moved = false

    companion object {
        private const val TAG = "OverlayControlButton"
        private const val PAGE_PARAMS = 0
        private const val PAGE_OPTIONS = 1
        /** Pestaña con la que se abre el menú (la última usada) y secciones plegadas por el usuario; duran lo que el proceso. */
        private var lastPage = PAGE_PARAMS
        private val sectionOpen = mutableMapOf<String, Boolean>()
        private const val SIZE_DP = 40   // zona táctil mínima (transparente): cómoda para el dedo
        private val DOT_DP = intArrayOf(10, 16, 24) // lo único que se ve: un puntito (pequeño, mediano, grande; T66)
        private const val CLICK_THRESHOLD_PX = 12
    }

    /**
     * El botón flotante: un punto gris translúcido, sin icono ni color. La app es visual y nada debe interponerse entre el
     * shader y quien lo mira; el área táctil es mayor que el punto para poder pulsarlo sin esfuerzo.
     */
    private class DotView(ctx: Context, var diameter: Float, var opacity: Float) : View(ctx) {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(50, 0, 0, 0)     // un filo oscuro para que no se pierda sobre imágenes claras
        }
        var active = false
            set(value) { field = value; invalidate() }

        override fun onDraw(canvas: android.graphics.Canvas) {
            // Encendido: un poco más visible que apagado (T66: la opacidad la elige el usuario; 0,27 es el aspecto de siempre)
            fill.color = Color.argb((255 * opacity * (if (active) 1f else 0.6f)).toInt().coerceIn(0, 255), 255, 255, 255)
            val r = diameter / 2f
            canvas.drawCircle(width / 2f, height / 2f, r, fill)
            canvas.drawCircle(width / 2f, height / 2f, r, ring)
        }
    }

    private fun dotPx(): Float = DOT_DP[PerfSettings.getButtonSize(context)] * context.resources.displayMetrics.density

    /** La zona táctil siempre es mayor que el punto, para poder pulsarlo sin esfuerzo. */
    private fun touchZonePx(): Int {
        val d = context.resources.displayMetrics.density
        return maxOf(SIZE_DP * d, dotPx() + 16 * d).toInt()
    }

    /** Tamaño y opacidad del botón (T66) cambiados: se aplican sin cerrar el menú ni mover el botón. */
    fun refreshStyle() {
        val iv = buttonView as? DotView ?: return
        iv.diameter = dotPx()
        iv.opacity = PerfSettings.getButtonRestOpacity(context) / 100f
        iv.invalidate()
        val p = params ?: return
        val zone = touchZonePx()
        if (p.width != zone) {
            p.width = zone
            p.height = zone
            try { windowManager.updateViewLayout(iv, p) } catch (e: Exception) { Log.e(TAG, "refreshStyle: ${e.message}") }
        }
    }

    /** true mientras el menú flotante está abierto (el contador de FPS puede mostrarse solo entonces). */
    val isMenuShowing: Boolean get() = menuView != null

    fun showControlButton() {
        Log.d(TAG, "intentando mostrar botón... buttonView=$buttonView")
        // Ajustes puede haberlo desactivado: sin botón no hay forma de abrir el menú (quedan la notificación y la app)
        if (!ShaderPreferences.isFloatingButton(context)) {
            hideControlButton()
            return
        }
        if (buttonView != null) {
            bringToFront()
            return
        }

        val sizePx = touchZonePx()
        Log.d(TAG, "Tamaño botón: ${sizePx}px")

        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ShaderPreferences.getControlButtonX(context)
            y = ShaderPreferences.getControlButtonY(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
        params = lp

        val iv = DotView(context, dotPx(), PerfSettings.getButtonRestOpacity(context) / 100f)
        updateIcon(iv)
        buttonView = iv

        iv.setOnTouchListener { v, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) moved = true
                    p.x = initialX + dx
                    p.y = initialY + dy
                    windowManager.updateViewLayout(v, p)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        showMenu()
                    } else {
                        ShaderPreferences.setControlButtonPosition(context, p.x, p.y)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(iv, lp)
            Log.i(TAG, "Botón flotante añadido")
        } catch (e: Exception) {
            Log.e(TAG, "Error añadiendo botón flotante: ${e.message}")
        }
    }

    fun hideControlButton() {
        hideMenu()
        buttonView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "removeView: ${e.message}") }
            buttonView = null
        }
    }

    fun bringToFront() {
        // Primero el menú y después el botón, para que el botón quede encima de todo
        menuView?.let { menu ->
            try {
                val menuLp = menu.layoutParams as? WindowManager.LayoutParams
                if (menu.parent != null && menuLp != null) {
                    windowManager.removeView(menu)
                    windowManager.addView(menu, menuLp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error subiendo el menú al frente: ${e.message}")
            }
        }
        val view = buttonView ?: return
        val lp = params ?: return
        try {
            if (view.parent != null) {
                // VOLVEMOS A REMOVE/ADD: Es la única forma garantizada de cambiar el Z-order
                // en ventanas del mismo tipo (TYPE_ACCESSIBILITY_OVERLAY).
                windowManager.removeView(view)
                windowManager.addView(view, lp)
                Log.d(TAG, "Z-order forzado al frente (remove/add)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en bringToFront: ${e.message}")
        }
    }

    fun onConfigurationChanged(newConfig: Configuration? = null) {
        if (menuView != null) {
            hideMenu()
            showMenu()
        }
    }

    // Aviso dentro del menú: un Toast saldría DETRÁS de esta ventana (las ventanas de accesibilidad van por encima de los Toast).
    private var messageView: TextView? = null
    private var pendingMessage: String? = null
    private val hideMessage = Runnable { messageView?.visibility = View.GONE }

    private fun say(text: String) {
        val tv = messageView ?: return
        tv.text = text
        tv.visibility = View.VISIBLE
        tv.removeCallbacks(hideMessage)
        tv.postDelayed(hideMessage, 4000)
    }

    /** Rehace el menú (cambia lo que se ve) y enseña un aviso ya dentro del nuevo. */
    private fun rebuild(message: String? = null) {
        pendingMessage = message
        hideMenu()
        showMenu()
    }

    /**
     * Menú flotante: un trozo de tubo CRT con los mismos estilos que la app (ui/CrtUi.kt), en dos páginas: PARAMS (los parámetros
     * del shader, a toda altura) y OPTIONS (perfil, sesión, zona, pantallas y app, en secciones plegables). En horizontal, las
     * etiquetas van encima de cada fila, que las columnas son estrechas.
     */
    private fun showMenu() {
        if (menuView != null) return

        VulkanEngineBridge.syncEditOutput(context) // los parámetros que se van a listar son los de la zona en edición (T18)

        val ui = CrtUi.themed(context)
        val metrics = context.resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels

        val width = (metrics.widthPixels * 0.88f).toInt()
        val height = if (isLandscape) (metrics.heightPixels * 0.85f).toInt() else (metrics.heightPixels * 0.72f).toInt()

        val lp = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val gap = CrtUi.dimen(ui, R.dimen.crt_space_s)
        val labelW = CrtUi.dp(ui, 76f)

        val root = FrameLayout(ui).apply {
            CrtUi.asScreen(this)
            val p = CrtUi.dimen(ui, R.dimen.crt_space_l)
            setPadding(p, p, p, p)
        }

        val mainLayout = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
        }

        /** Fila de controles: etiqueta a la izquierda (encima en horizontal; "" = hueco para alinear) y botones a partes iguales. */
        fun row(label: String?, vararg views: View, top: Int = gap): View {
            val buttons = LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val stacked = isLandscape && !label.isNullOrEmpty()
            if (label != null && !stacked && !isLandscape) {
                buttons.addView(CrtUi.text(ui, CrtUi.Text.LABEL, label), LinearLayout.LayoutParams(labelW, -2))
            }
            views.forEachIndexed { i, v ->
                buttons.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = gap * 3 / 4 })
            }
            val container = if (stacked) LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                addView(CrtUi.text(ui, CrtUi.Text.LABEL, label!!))
                addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap / 2 })
            } else buttons
            container.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
            return container
        }

        // Cabecera
        val header = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val activeName = ShaderPreferences.getEditShaderName(context) ?: context.getString(R.string.menu_module_unknown)
        // Con preset propio en la zona B, el título dice qué zona se está editando
        val zoneTag = when {
            !ShaderZone.isDual(context) || ShaderPreferences.getZoneBShaderId(context) == null -> ""
            ShaderPreferences.editsZoneB(context) -> " [B]"
            else -> " [A]"
        }
        val glassTag = if (ShaderCaptureService.sessionMode == SessionMode.GLASS) " [" + context.getString(R.string.menu_glass_tag) + "]" else ""
        val title = CrtUi.text(ui, CrtUi.Text.TITLE, context.getString(R.string.menu_module, activeName.uppercase()) + zoneTag + glassTag)
        AutoScroll.attach(title) // un nombre largo se desliza en vez de cortarse
        val closeBtn = CrtUi.button(ui, context.getString(R.string.menu_close)).apply {
            minWidth = CrtUi.dimen(ui, R.dimen.crt_touch)
            setOnClickListener { hideMenu() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
        mainLayout.addView(header)

        // Pestañas: los parámetros son lo que más se toca y van primero; el resto de controles, en OPTIONS
        val tabParams = CrtUi.button(ui, context.getString(R.string.menu_tab_params), CrtUi.Btn.TOGGLE)
        val tabOptions = CrtUi.button(ui, context.getString(R.string.menu_tab_options), CrtUi.Btn.TOGGLE)
        mainLayout.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabParams, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tabOptions, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap * 3 / 4 })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        mainLayout.addView(CrtUi.divider(ui, CrtUi.dimen(ui, R.dimen.crt_space_m)))

        // ----- Página OPTIONS -----
        val shaderId = ShaderPreferences.getEditShaderId(context)
        val controlPanel = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }

        /** Sección plegable: cabecera "[-] TÍTULO" que muestra u oculta su cuerpo sin rehacer el menú. */
        fun section(key: String, titleText: String, defaultOpen: Boolean, build: (LinearLayout) -> Unit) {
            val body = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
            build(body)
            // El primer control de cada sección guarda la misma distancia a su cabecera (unas filas llevaban top = 0 y otras el hueco normal)
            (body.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.topMargin = gap
            fun isOpen() = sectionOpen[key] ?: defaultOpen
            val head = CrtUi.button(ui, "").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            fun paint() {
                head.text = (if (isOpen()) "[-] " else "[+] ") + titleText
                body.visibility = if (isOpen()) View.VISIBLE else View.GONE
            }
            paint()
            head.setOnClickListener { sectionOpen[key] = !isOpen(); paint() }
            controlPanel.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            controlPanel.addView(body, LinearLayout.LayoutParams(-1, -2))
        }

        if (shaderId == null) {
            controlPanel.addView(CrtUi.text(ui, CrtUi.Text.CAPTION, context.getString(R.string.toast_app_no_shader)))
        } else {
            val profiles = ShaderParamStore.getProfileNames(context, shaderId)
            val activeProfile = ShaderParamStore.getActiveProfileName(context, shaderId) ?: "Default"

            // SESSION: pausar y cerrar
            section("session", context.getString(R.string.menu_sec_session), true) { body ->
                val pauseBtn = CrtUi.button(ui, "").apply {
                    updatePauseBtnStyle(this)
                    setOnClickListener {
                        // Activa -> pausa; en pausa -> reanuda. El servicio es quien cambia el estado (y la notificación)
                        val action = if (ShaderCaptureService.isCapturing) ShaderCaptureService.ACTION_STOP else ShaderCaptureService.ACTION_START
                        context.startService(Intent(context, ShaderCaptureService::class.java).apply { this.action = action })
                        hideMenu()
                    }
                }
                // CLOSE (T64): el único cierre total desde el menú. Hay que pulsarlo dos veces (a los 3 s se desarma) para que un
                // toque accidental no cierre la sesión; PAUSE ya cubre lo de "quitar el shader un momento".
                val closeSessionBtn = CrtUi.button(ui, context.getString(R.string.btn_close_system), CrtUi.Btn.ALERT)
                val disarmClose = Runnable { closeSessionBtn.text = context.getString(R.string.btn_close_system) }
                closeSessionBtn.setOnClickListener {
                    if (closeSessionBtn.text == context.getString(R.string.btn_close_confirm)) {
                        closeSessionBtn.removeCallbacks(disarmClose)
                        hideMenu()
                        context.startService(Intent(context, ShaderCaptureService::class.java).apply { action = ShaderCaptureService.ACTION_STOP_FULL })
                    } else {
                        closeSessionBtn.text = context.getString(R.string.btn_close_confirm)
                        closeSessionBtn.postDelayed(disarmClose, 3000)
                    }
                }
                body.addView(row(null, pauseBtn, closeSessionBtn, top = 0))
            }

            // SHADER: cambiar de shader sin salir a la app (T65), perfiles y restablecer
            section("shader", context.getString(R.string.menu_sec_shader), true) { body ->
                // Modo dual (T18): qué zona se edita y, para la B, su preset propio
                val dualNow = ShaderZone.isDual(context)
                val editingB = dualNow && ShaderPreferences.getEditZone(context) == 1
                if (dualNow) {
                    val zA = CrtUi.button(ui, context.getString(R.string.edit_zone_a), CrtUi.Btn.TOGGLE).apply {
                        isSelected = !editingB
                        setOnClickListener { ShaderPreferences.setEditZone(context, 0); rebuild() }
                    }
                    val zB = CrtUi.button(ui, context.getString(R.string.edit_zone_b), CrtUi.Btn.TOGGLE).apply {
                        isSelected = editingB
                        setOnClickListener { ShaderPreferences.setEditZone(context, 1); rebuild() }
                    }
                    body.addView(row(context.getString(R.string.label_edit_zone), zA, zB, top = 0))
                }
                // El menú se cierra: al volver a abrirlo ya muestra los parámetros del nuevo
                val zoneBName = ShaderPreferences.getZoneBShaderName(context)
                val pickLabel = if (editingB) context.getString(R.string.btn_shader_pick_b, zoneBName ?: context.getString(R.string.shader_same_as_a))
                                else context.getString(R.string.btn_shader_pick, ShaderPreferences.getActiveShaderName(context) ?: activeName)
                val shaderBtn = CrtUi.button(ui, pickLabel).apply {
                    AutoScroll.attach(this)
                    setOnClickListener {
                        hideMenu()
                        (if (editingB) shaderPickerB else shaderPicker).show { /* la carga la pide el selector; el LoadingHud muestra el progreso */ }
                    }
                }
                if (editingB && zoneBName != null) {
                    // La B vuelve a seguir al preset de la A
                    val sameBtn = CrtUi.button(ui, context.getString(R.string.btn_zone_b_same), CrtUi.Btn.WARN).apply {
                        setOnClickListener { ShaderCaptureService.clearZoneB(context); rebuild() }
                    }
                    body.addView(row(null, shaderBtn, sameBtn))
                } else body.addView(row(null, shaderBtn))
                // Combinar presets (T45): añadir otro encima o debajo del activo
                val combineBtn = CrtUi.button(ui, context.getString(R.string.btn_combine)).apply {
                    setOnClickListener {
                        hideMenu()
                        stackEditor.show { /* la carga la pide el editor; el LoadingHud muestra el progreso */ }
                    }
                }
                body.addView(row(null, combineBtn))

                val spinner = Spinner(ui).apply {
                    adapter = ArrayAdapter(ui, R.layout.crt_spinner_item, profiles.ifEmpty { listOf("Default") }).apply {
                        setDropDownViewResource(R.layout.crt_spinner_dropdown_item)
                    }
                    val selection = profiles.indexOf(activeProfile)
                    if (selection >= 0) setSelection(selection)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                            val selected = profiles.getOrNull(pos) ?: return
                            if (selected != activeProfile) {
                                ShaderParamStore.setActiveProfile(context, shaderId, selected)
                                VulkanEngineBridge.applySavedShaderParameters(context, shaderId)
                                rebuild()
                            }
                        }
                        override fun onNothingSelected(p0: AdapterView<*>?) {}
                    }
                }
                body.addView(row(context.getString(R.string.label_profile), spinner))

                val overwriteBtn = CrtUi.button(ui, context.getString(R.string.btn_overwrite)).apply {
                    setOnClickListener {
                        // Foto completa del estado actual sobre el perfil activo
                        VulkanEngineBridge.saveCurrentAsProfile(context, shaderId, activeProfile)
                        say(context.getString(R.string.toast_profile_updated, activeProfile))
                    }
                }
                val saveAsBtn = CrtUi.button(ui, context.getString(R.string.btn_save_as)).apply {
                    setOnClickListener { showSaveProfileDialog(shaderId) }
                }
                body.addView(row("", overwriteBtn, saveAsBtn))

                val resetBtn = CrtUi.button(ui, context.getString(R.string.btn_restore_defaults), CrtUi.Btn.WARN).apply {
                    setOnClickListener {
                        // Valores del preset (los suyos y los de sus #reference), igual que RESET en SETTINGS
                        VulkanEngineBridge.resetShaderParameters()
                        rebuild(context.getString(R.string.toast_params_reset))
                    }
                }
                body.addView(row(null, resetBtn))
            }

            // ZONE: zona del shader, modo dual, barras negras y detección
            val dual = ShaderZone.isDual(context)
            section("zone", context.getString(R.string.menu_sec_zone), true) { body ->
                // Zona del shader (T06): dibujar a mano en qué parte de la pantalla se aplica el shader
                val zoneBtn = CrtUi.button(ui, context.getString(R.string.btn_zone_edit)).apply {
                    setOnClickListener {
                        hideMenu()
                        com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService.startZoneEditor()
                    }
                }
                body.addView(row(null, zoneBtn, top = 0))

                // Modo dual (T16): dos zonas de shader a la vez (DS, 3DS). Al encender, las zonas toman su forma inicial según la
                // resolución (el editor de zona las ajusta); la detección de bandas no vale con dos zonas, así que se apaga.
                val dualBtn = CrtUi.button(ui, context.getString(if (dual) R.string.btn_dual_on else R.string.btn_dual_off), CrtUi.Btn.TOGGLE).apply {
                    isSelected = dual
                    setOnClickListener {
                        val turningOn = !ShaderZone.isDual(context)
                        if (turningOn && ShaderZone.isAutoDetect(context)) {
                            ShaderPreferences.setAspectRatioIndex(context, ShaderZone.MANUAL_INDEX)
                        }
                        ShaderPreferences.setDual(context, turningOn)
                        if (turningOn) ShaderCaptureService.requestZoneBIfNeeded(context) else ShaderCaptureService.suspendZoneB(context)
                        if (turningOn) ShaderPreferences.setScreens(context, ScreenLayout.TOP) // las dos zonas usan las dos salidas en la pantalla principal
                        VulkanEngineBridge.refreshZone()
                        rebuild() // barras y detección son distintas con dos zonas
                    }
                }
                body.addView(row(null, dualBtn))

                // Barras negras por lado fuera de la zona (T08): cada interruptor pinta o deja de pintar ese lado de negro encima de
                // la pantalla real. Es por orientación y actúa al instante (no hay que recompilar nada).
                val sides = listOf(context.getString(R.string.side_left) to ShaderZone.BAR_LEFT, context.getString(R.string.side_right) to ShaderZone.BAR_RIGHT,
                                   context.getString(R.string.side_top) to ShaderZone.BAR_TOP, context.getString(R.string.side_bottom) to ShaderZone.BAR_BOTTOM)
                fun currentLandscape(): Boolean = ShaderZone.screenSize(windowManager).let { it.first >= it.second }
                // Con dos zonas, una sola opción: fondo negro fuera de las dos (por orientación)
                val blackBtn = CrtUi.button(ui, context.getString(R.string.btn_dual_black), CrtUi.Btn.TOGGLE).apply {
                    isSelected = ShaderPreferences.getBars(context, currentLandscape()) != 0
                    setOnClickListener {
                        val landscape = currentLandscape()
                        val on = ShaderPreferences.getBars(context, landscape) == 0
                        ShaderPreferences.setBars(context, landscape, if (on) ShaderZone.BAR_ALL else 0)
                        isSelected = on
                        VulkanEngineBridge.refreshZone()
                    }
                }
                val barButtons = sides.map { (label, bit) ->
                    CrtUi.button(ui, label, CrtUi.Btn.TOGGLE).apply {
                        isSelected = ShaderPreferences.getBars(context, currentLandscape()) and bit != 0
                        setOnClickListener {
                            val landscape = currentLandscape()
                            val mask = ShaderPreferences.getBars(context, landscape) xor bit
                            ShaderPreferences.setBars(context, landscape, mask)
                            isSelected = mask and bit != 0
                            VulkanEngineBridge.refreshZone()
                            val (sw, sh) = ShaderZone.screenSize(windowManager)
                            if (ShaderZone.currentRect(context, sw, sh) == null) say(context.getString(R.string.toast_bars_no_zone))
                        }
                    }
                }
                if (dual) body.addView(row(context.getString(R.string.label_bars), blackBtn))
                else body.addView(row(context.getString(R.string.label_bars), *barButtons.toTypedArray()))

                // Detección automática de bandas negras (T07): AUTO pone o quita el selector "Auto (detectar bandas)" (al quitarlo
                // vuelve al selector anterior); los lados son por orientación y marcan en cuáles puede recortar la detección.
                val autoBtn = CrtUi.button(ui, context.getString(R.string.btn_auto), CrtUi.Btn.TOGGLE).apply {
                    isSelected = ShaderZone.isAutoDetect(context)
                    setOnClickListener {
                        val turningOn = !ShaderZone.isAutoDetect(context)
                        if (turningOn) {
                            ShaderPreferences.setAspectBeforeAuto(context, ShaderPreferences.getAspectRatioIndex(context))
                            ShaderPreferences.setAspectRatioIndex(context, ShaderZone.DETECT_INDEX)
                        } else {
                            ShaderPreferences.setAspectRatioIndex(context, ShaderPreferences.getAspectBeforeAuto(context))
                        }
                        isSelected = turningOn
                        VulkanEngineBridge.refreshZone()
                    }
                }
                val detectButtons = sides.map { (label, bit) ->
                    CrtUi.button(ui, label, CrtUi.Btn.TOGGLE).apply {
                        isSelected = ShaderPreferences.getAutoSides(context, currentLandscape()) and bit != 0
                        setOnClickListener {
                            val landscape = currentLandscape()
                            val mask = ShaderPreferences.getAutoSides(context, landscape) xor bit
                            ShaderPreferences.setAutoSides(context, landscape, mask)
                            isSelected = mask and bit != 0
                            VulkanEngineBridge.refreshZone()
                            if (!ShaderZone.isAutoDetect(context)) say(context.getString(R.string.toast_detect_hint))
                        }
                    }
                }
                if (!dual) body.addView(row(context.getString(R.string.label_detect), autoBtn, *detectButtons.toTypedArray()))
            }

            // SCREENS (T17, Thor): el shader arriba, abajo o en las dos. Solo existe con Glass: la captura no llega a la otra
            // pantalla. Con una sola pantalla queda plegada (no oculta, por si la detección fallara).
            val hasSecond = ScreenLayout.available(context)
            section("screens", context.getString(R.string.label_screens), hasSecond) { body ->
                if (!hasSecond) body.addView(CrtUi.text(ui, CrtUi.Text.CAPTION, context.getString(R.string.screens_single_hint)))
                val current = ShaderPreferences.getScreens(context)
                val screenButtons = listOf(
                    R.string.screen_top to ScreenLayout.TOP, R.string.screen_bottom to ScreenLayout.BOTTOM, R.string.screen_both to ScreenLayout.BOTH
                ).map { (label, value) ->
                    CrtUi.button(ui, context.getString(label), CrtUi.Btn.TOGGLE).apply {
                        isSelected = current == value
                        setOnClickListener {
                            if (value != ScreenLayout.TOP && ShaderCaptureService.sessionMode != SessionMode.GLASS) {
                                say(context.getString(R.string.toast_screens_glass))
                                return@setOnClickListener
                            }
                            if (value != ScreenLayout.TOP && !hasSecond) {
                                say(context.getString(R.string.screens_single_hint))
                                return@setOnClickListener
                            }
                            ShaderPreferences.setScreens(context, value)
                            if (value != ScreenLayout.TOP && ShaderPreferences.isDual(context)) {
                                ShaderPreferences.setDual(context, false) // dos zonas y dos pantallas no se mezclan
                                VulkanEngineBridge.refreshZone()
                            }
                            com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService.screensChanged()
                            rebuild()
                        }
                    }
                }
                body.addView(row(null, *screenButtons.toTypedArray(), top = 0))
            }

            // PERFORMANCE (T66): contador de FPS y tope de FPS de salida; el resto de ajustes (tamaño, opacidad, intervalo...) están en SETTINGS
            section("perf", context.getString(R.string.menu_sec_perf), false) { body ->
                val perfSvc = com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService
                val counterOn = PerfSettings.isFpsCounter(context)
                val counterBtn = CrtUi.button(ui, context.getString(if (counterOn) R.string.btn_fps_on else R.string.btn_fps_off), CrtUi.Btn.TOGGLE).apply {
                    isSelected = counterOn
                    setOnClickListener {
                        val on = !PerfSettings.isFpsCounter(context)
                        PerfSettings.setFpsCounter(context, on)
                        isSelected = on
                        text = context.getString(if (on) R.string.btn_fps_on else R.string.btn_fps_off)
                        perfSvc.applyPerfSettings()
                    }
                }
                val corners = listOf(R.string.fps_corner_tl, R.string.fps_corner_tr, R.string.fps_corner_bl, R.string.fps_corner_br)
                val cornerBtn = CrtUi.button(ui, context.getString(corners[PerfSettings.getFpsCorner(context)])).apply {
                    setOnClickListener {
                        val next = (PerfSettings.getFpsCorner(context) + 1) % 4
                        PerfSettings.setFpsCorner(context, next)
                        text = context.getString(corners[next])
                        perfSvc.applyPerfSettings()
                    }
                }
                body.addView(row(context.getString(R.string.label_fps_counter), counterBtn, cornerBtn, top = 0))

                // Tope de FPS de salida: descarta frames de captura que llegan antes de tiempo (no se aplica en Glass)
                val capButtons = mutableListOf<Pair<Button, Int>>()
                PerfSettings.FPS_CAPS.forEach { cap ->
                    val b = CrtUi.button(ui, if (cap == 0) context.getString(R.string.fps_cap_none) else cap.toString(), CrtUi.Btn.TOGGLE).apply {
                        isSelected = PerfSettings.getFpsCap(context) == cap
                        setOnClickListener {
                            PerfSettings.setFpsCap(context, cap)
                            capButtons.forEach { (btn, c) -> btn.isSelected = c == cap }
                            perfSvc.applyPerfSettings()
                        }
                    }
                    capButtons += b to cap
                }
                body.addView(row(context.getString(R.string.label_fps_cap_menu), *capButtons.map { it.first }.toTypedArray()))
            }

            // APP (T61): guarda lo que se ve ahora (shader, ajustes, zona, modo, pantallas) para la app que está delante.
            // Al volver a abrirla, el shader se pone solo (Glass directo; Captura pide compartir pantalla).
            val svc = com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService
            val frontApp = svc.foregroundApp()
            // La sección existe siempre: el perfil por defecto (T60) no depende de la app que haya delante
            run {
                section("app", context.getString(R.string.label_app), true) { body ->
                    val hasDefault = svc.hasDefaultProfile()
                    if (frontApp != null) {
                        val (appLabel, hasProfile) = frontApp
                    body.addView(CrtUi.text(ui, CrtUi.Text.CAPTION, context.getString(R.string.label_app_front, appLabel)).also { AutoScroll.attach(it) })
                    val saveBtn = CrtUi.button(ui, context.getString(if (hasProfile) R.string.btn_app_update else R.string.btn_app_save)).apply {
                        setOnClickListener {
                            rebuild(com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService.saveAppProfile())
                        }
                    }
                    val buttons = mutableListOf<View>(saveBtn)
                    if (hasProfile) {
                        buttons += CrtUi.button(ui, context.getString(R.string.btn_app_remove), CrtUi.Btn.WARN).apply {
                            setOnClickListener {
                                rebuild(com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService.removeAppProfile())
                            }
                        }
                    }
                    body.addView(row(null, *buttons.toTypedArray()))
                    }
                    // Perfil por defecto (T60): lo que se aplica a toda app que no tenga el suyo, Inicio incluido
                    val defaultSave = CrtUi.button(ui, context.getString(if (hasDefault) R.string.btn_app_default_update else R.string.btn_app_default_save)).apply {
                        setOnClickListener { rebuild(svc.saveDefaultProfile()) }
                    }
                    val defaultButtons = mutableListOf<View>(defaultSave)
                    if (hasDefault) {
                        defaultButtons += CrtUi.button(ui, context.getString(R.string.btn_app_remove), CrtUi.Btn.WARN).apply {
                            setOnClickListener { rebuild(svc.removeDefaultProfile()) }
                        }
                    }
                    body.addView(row(context.getString(R.string.label_app_default), *defaultButtons.toTypedArray()))
                }
            }
        }

        val optionsPage = ScrollView(ui).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            addView(controlPanel, FrameLayout.LayoutParams(-1, -2))
        }

        // ----- Página PARAMS -----
        // Parámetros: mismo formato que en SETTINGS (nombre y valor en una línea, barra debajo)
        val paramsPage = ScrollView(ui).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        val paramsContainer = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, CrtUi.dp(ui, 6f), gap)
        }
        paramsContainer.addView(CrtUi.text(ui, CrtUi.Text.LABEL, context.getString(R.string.label_parameters)))

        val hot = CrtUi.color(ui, R.attr.crtHot)
        val hotGlow = CrtUi.color(ui, R.attr.crtGlowHot)
        val shaderParams = VulkanEngineBridge.getShaderParameters()
        if (shaderParams.isNullOrEmpty()) {
            paramsContainer.addView(CrtUi.text(ui, CrtUi.Text.CAPTION, context.getString(R.string.menu_no_params)).apply {
                setPadding(0, gap, 0, 0)
            })
        } else {
            // Favoritos (T74): el filtro muestra solo los marcados con la estrella
            val favs = shaderId?.let { ParamFavorites.get(context, it).toMutableSet() } ?: mutableSetOf()
            val onlyFav = shaderId != null && ParamFavorites.isOnlyFavorites(context, shaderId)
            if (shaderId != null) {
                paramsContainer.addView(CrtUi.button(ui, context.getString(R.string.btn_fav_only), CrtUi.Btn.TOGGLE).apply {
                    isSelected = onlyFav
                    setOnClickListener { ParamFavorites.setOnlyFavorites(context, shaderId, !onlyFav); rebuild() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap / 2 })
                if (onlyFav && shaderParams.none { !it.isInfo && it.name in favs }) {
                    paramsContainer.addView(CrtUi.text(ui, CrtUi.Text.CAPTION, context.getString(R.string.menu_no_favorites)).apply {
                        setPadding(0, gap, 0, 0)
                    })
                }
            }
            shaderParams.forEach { p ->
                if (onlyFav && (p.isInfo || p.name !in favs)) return@forEach
                // Etiquetas/separadores del shader (como las filas sin valor de RetroArch): se muestran, no se editan.
                if (p.isInfo) {
                    when {
                        p.isSpacer -> paramsContainer.addView(View(ui), LinearLayout.LayoutParams(-1, gap))
                        p.isDivider -> paramsContainer.addView(CrtUi.divider(ui))
                        else -> paramsContainer.addView(CrtUi.text(ui, CrtUi.Text.LABEL, p.description.trim()).apply {
                            setPadding(0, CrtUi.dimen(ui, R.dimen.crt_space_m), 0, CrtUi.dp(ui, 2f))
                        })
                    }
                    return@forEach
                }
                val nameRow = LinearLayout(ui).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, CrtUi.dp(ui, 10f), 0, 0)
                }
                val pName = CrtUi.text(ui, CrtUi.Text.BODY, p.title).apply { maxLines = 2 }
                val pValue = CrtUi.text(ui, CrtUi.Text.BODY, p.format(p.snap(p.current))).apply {
                    setTextColor(hot)
                    setShadowLayer(shadowRadius, 0f, 0f, hotGlow)
                    gravity = Gravity.END
                }
                if (shaderId != null) {
                    val star = CrtUi.text(ui, CrtUi.Text.BODY, if (p.name in favs) "[*]" else "[ ]").apply {
                        minWidth = CrtUi.dp(ui, 40f)
                        setOnClickListener {
                            val fav = ParamFavorites.toggle(context, shaderId, p.name)
                            if (fav) favs.add(p.name) else favs.remove(p.name)
                            text = if (fav) "[*]" else "[ ]"
                        }
                    }
                    nameRow.addView(star, LinearLayout.LayoutParams(-2, -2))
                }
                nameRow.addView(pName, LinearLayout.LayoutParams(0, -2, 1f))
                nameRow.addView(pValue, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })

                val seekBar = CrtUi.seekBar(ui).apply {
                    // Un tick de la barra = un paso del parámetro (los enteros 0,1,2 saltan de uno en uno).
                    max = p.stepCount
                    isEnabled = p.stepCount > 0

                    progress = p.indexOf(p.current)
                    var lastSent = p.snap(p.current)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                val valFloat = p.valueAt(progress)
                                pValue.text = p.format(valFloat)
                                if (valFloat != lastSent) { // evita re-renderizar mientras el dedo sigue en el mismo paso
                                    lastSent = valFloat
                                    VulkanEngineBridge.updateShaderParameter(p.name, valFloat)
                                    if (p.name == "GLASS_BLUR") com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService.refreshBlur()
                                }
                            }
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                paramsContainer.addView(nameRow)
                paramsContainer.addView(seekBar)
            }
        }
        paramsPage.addView(paramsContainer)

        // Las dos páginas comparten el hueco; la pestaña elegida se recuerda para la próxima vez
        val pages = FrameLayout(ui)
        pages.addView(paramsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(optionsPage, FrameLayout.LayoutParams(-1, -1))
        fun showPage(page: Int) {
            lastPage = page
            paramsPage.visibility = if (page == PAGE_PARAMS) View.VISIBLE else View.GONE
            optionsPage.visibility = if (page == PAGE_OPTIONS) View.VISIBLE else View.GONE
            tabParams.isSelected = page == PAGE_PARAMS
            tabOptions.isSelected = page == PAGE_OPTIONS
        }
        tabParams.setOnClickListener { showPage(PAGE_PARAMS) }
        tabOptions.setOnClickListener { showPage(PAGE_OPTIONS) }
        showPage(lastPage)
        mainLayout.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))

        // Aviso (ver say): al pie, oculto hasta que haga falta
        val message = CrtUi.text(ui, CrtUi.Text.LABEL).apply {
            setTextColor(hot)
            setShadowLayer(shadowRadius, 0f, 0f, hotGlow)
            visibility = View.GONE
            setPadding(0, gap, 0, 0)
        }
        mainLayout.addView(message, LinearLayout.LayoutParams(-1, -2))
        messageView = message

        root.addView(mainLayout)

        menuView = root
        try {
            windowManager.addView(root, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding menu: ${e.message}")
        }
        pendingMessage?.let { pendingMessage = null; say(it) }
    }

    /** Encendido: PAUSE (botón normal); en pausa: RESUME como acción principal. El rojo es solo de CLOSE (T64). */
    private fun updatePauseBtnStyle(btn: Button) {
        val on = ShaderCaptureService.isCapturing
        btn.setText(if (on) R.string.btn_pause else R.string.btn_resume)
        CrtUi.applyButton(btn, if (on) CrtUi.Btn.NORMAL else CrtUi.Btn.PRIMARY)
    }

    private fun showSaveProfileDialog(shaderId: String) {
        val dialogCtx = CrtUi.dialogContext(context)

        val input = EditText(dialogCtx).apply {
            hint = context.getString(R.string.hint_name)
            setSingleLine()
        }

        val dialog = AlertDialog.Builder(dialogCtx, R.style.CrtAlertDialog)
            .setTitle(R.string.dialog_input_profile_title)
            .setView(CrtUi.dialogContent(input))
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    VulkanEngineBridge.saveCurrentAsProfile(context, shaderId, name.trim())
                    rebuild(context.getString(R.string.toast_profile_updated, name.trim()))
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        // Sin oscurecer lo de detrás: se sigue viendo el shader mientras se escribe el nombre
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.show()
    }

    fun hideMenu() {
        messageView?.removeCallbacks(hideMessage)
        messageView = null
        menuView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "removeView: ${e.message}") }
            menuView = null
        }
    }

    private fun updateIcon(iv: DotView) {
        iv.active = ShaderCaptureService.isCapturing
    }

    /** El estado de la sesión cambió (p. ej. DETENER desde la notificación): el punto se repinta encendido o apagado. */
    fun refreshState() {
        buttonView?.let { updateIcon(it) }
    }
}
