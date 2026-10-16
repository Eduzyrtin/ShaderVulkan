package com.sanabria.shadervulkan

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.sanabria.shadervulkan.model.PerfSettings
import com.sanabria.shadervulkan.overlay.AboutDialog
import com.sanabria.shadervulkan.overlay.GuideDialog
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.services.BatterySaver
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService
import com.sanabria.shadervulkan.ui.CrtActivity
import com.sanabria.shadervulkan.ui.CrtTheme
import com.sanabria.shadervulkan.ui.CrtUi
import com.sanabria.shadervulkan.ui.HueSlider

/**
 * Ajustes de la app (T60): apariencia (tono de color con atajos, vista previa, glow y grano) y comportamiento (pausa con la barra de
 * estado y botón flotante). Los ajustes del preset activo están en ShaderSettingsActivity.
 *
 * El color: el slider y los atajos eligen una paleta (CrtTheme). Mientras se arrastra solo cambia la vista previa, inflada con un
 * contexto que lleva la paleta candidata; al soltar se guarda y la pantalla se recrea con ella. MainActivity y ShaderSettingsActivity
 * se recrean al volver a primer plano (CrtActivity compara la firma de la paleta).
 */
class AppSettingsActivity : CrtActivity() {

    private lateinit var hueSlider: HueSlider
    private lateinit var txtTone: TextView
    private lateinit var previewContainer: FrameLayout
    private val presetButtons = mutableListOf<Pair<Button, Int>>() // botón -> índice de paleta
    private val glowButtons = mutableListOf<Pair<Button, Int>>()   // botón -> nivel de glow

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCrtTheme() // enableEdgeToEdge() ya crea la ventana: la paleta tiene que estar antes
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)

        val gutter = resources.getDimensionPixelSize(R.dimen.crt_gutter)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_settings_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left + gutter, top = bars.top + gutter, right = bars.right + gutter, bottom = bars.bottom + gutter)
            insets
        }

        hueSlider = findViewById(R.id.hue_slider)
        txtTone = findViewById(R.id.txt_tone_name)
        previewContainer = findViewById(R.id.preview_container)

        val current = CrtTheme.paletteIndex(this)
        val neutral = CrtTheme.neutralIndex(this)
        hueSlider.neutral = current == neutral
        hueSlider.index = if (current == neutral) resources.getInteger(R.integer.crt_preset_classic) else current
        showPalette(current)

        hueSlider.onChange = { index, _ -> showPalette(index) }
        hueSlider.onCommit = { index -> commitPalette(index) }

        buildPresets()
        buildGlowButtons()

        val look = findViewById<LinearLayout>(R.id.look_container)
        addToggleRow(look, R.string.label_grain, R.string.grain_description, ShaderPreferences.isCrtGrain(this)) {
            ShaderPreferences.setCrtGrain(this, it) // MainActivity lo recoge en onResume, sin recrear nada
        }

        val behaviour = findViewById<LinearLayout>(R.id.behaviour_container)
        addGuideButtons(behaviour)
        addToggleRow(behaviour, R.string.label_auto_pause, R.string.auto_pause_description, ShaderPreferences.isAutoPauseOnShade(this)) {
            ShaderPreferences.setAutoPauseOnShade(this, it)
        }
        addToggleRow(behaviour, R.string.label_floating_button, R.string.floating_button_description, ShaderPreferences.isFloatingButton(this)) {
            ShaderPreferences.setFloatingButton(this, it)
            // Si hay sesión, el cambio se ve al momento
            if (ShaderCaptureService.hasControls) {
                if (it) ShaderOverlayAccessibilityService.showControlButton() else ShaderOverlayAccessibilityService.hideControlButton()
            }
        }

        addLanguageRow(behaviour)

        addPerformanceSection(behaviour)

        // Perfiles por app (T61/T62): interruptor general y acceso a la lista de apps
        addToggleRow(behaviour, R.string.label_app_profiles, R.string.app_profiles_description, ShaderPreferences.isAppProfilesEnabled(this)) {
            ShaderPreferences.setAppProfilesEnabled(this, it)
        }
        addGuideLink(behaviour, GuideDialog.APPS)
        behaviour.addView(CrtUi.button(this, getString(R.string.btn_app_profiles)).apply {
            setOnClickListener { startActivity(Intent(this@AppSettingsActivity, AppProfilesActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_s)
            bottomMargin = CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_s)
        })

        findViewById<Button>(R.id.btn_close_app_settings).setOnClickListener { finish() }
    }

    /** Lo que depende del tono candidato: la vista previa, el nombre y el atajo marcado. No guarda nada. */
    private fun showPalette(index: Int) {
        previewContainer.removeAllViews()
        val previewCtx = CrtTheme.previewContext(this, index)
        previewContainer.addView(LayoutInflater.from(previewCtx).inflate(R.layout.view_palette_preview, previewContainer, false))

        val preset = CrtTheme.PRESETS.firstOrNull { resources.getInteger(it.palette) == index }
        txtTone.setText(preset?.label ?: R.string.tone_custom)
        presetButtons.forEach { (button, palette) -> button.isSelected = palette == index }
    }

    private fun commitPalette(index: Int) {
        if (index == CrtTheme.paletteIndex(this)) return
        ShaderPreferences.setCrtPalette(this, index)
        recreateForTheme()
    }

    /** Los atajos con nombre, en filas de 4: un cuadrito con el color de la paleta y su nombre. */
    private fun buildPresets() {
        val container = findViewById<LinearLayout>(R.id.presets_container)
        val swatches = CrtTheme.swatches(this)
        val perRow = 4
        val gap = CrtUi.dimen(this, R.dimen.crt_space_xs)
        CrtTheme.PRESETS.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until perRow) {
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                    if (rowIndex > 0) topMargin = gap
                }
                val preset = chunk.getOrNull(i)
                if (preset == null) {
                    row.addView(android.widget.Space(this), params)
                    continue
                }
                val palette = resources.getInteger(preset.palette)
                val chip = GradientDrawable().apply {
                    setColor(swatches[palette])
                    setSize(CrtUi.dp(this@AppSettingsActivity, 10f), CrtUi.dp(this@AppSettingsActivity, 10f))
                }
                val button = CrtUi.button(this, getString(preset.label), CrtUi.Btn.TOGGLE).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_text_caption).toFloat())
                    setCompoundDrawablesRelativeWithIntrinsicBounds(chip, null, null, null)
                    compoundDrawablePadding = CrtUi.dp(this@AppSettingsActivity, 6f)
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                    isSelected = palette == CrtTheme.paletteIndex(this@AppSettingsActivity)
                    setOnClickListener { commitPalette(palette) }
                }
                presetButtons += button to palette
                row.addView(button, params)
            }
            container.addView(row)
        }
    }

    private fun buildGlowButtons() {
        val level = ShaderPreferences.getCrtGlow(this)
        listOf(R.id.btn_glow_off to 0, R.id.btn_glow_soft to 1, R.id.btn_glow_strong to 2).forEach { (id, value) ->
            val button = findViewById<Button>(id)
            button.isSelected = value == level
            button.setOnClickListener {
                if (value == ShaderPreferences.getCrtGlow(this)) return@setOnClickListener
                ShaderPreferences.setCrtGlow(this, value)
                recreateForTheme() // la firma del tema cambia: la pantalla se repinta con el glow nuevo
            }
            glowButtons += button to value
        }
    }

    /**
     * Idioma de la app: SISTEMA (el del móvil) o uno de los nueve. Usa el idioma por app de AppCompat: Android lo recuerda y recrea
     * las pantallas abiertas al cambiarlo. Los nombres van en su propio idioma, para encontrarlos aunque la app esté en otro.
     */
    private fun addLanguageRow(container: LinearLayout) {
        val gap = CrtUi.dimen(this, R.dimen.crt_space_xs)
        val options = listOf(
            "" to getString(R.string.language_system), "en" to "English", "es" to "Español",
            "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "pt" to "Português",
            "ca" to "Català", "eu" to "Euskara", "gl" to "Galego"
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-')
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_s)
            setPadding(0, pad, 0, pad)
        }
        panel.addView(CrtUi.text(this, CrtUi.Text.LABEL, getString(R.string.label_language)))
        panel.addView(CrtUi.text(this, CrtUi.Text.CAPTION, getString(R.string.language_description)))
        options.chunked(4).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 4) {
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = gap
                    if (i > 0) marginStart = gap
                }
                val option = chunk.getOrNull(i)
                if (option == null) { row.addView(android.widget.Space(this), params); continue }
                row.addView(CrtUi.button(this, option.second, CrtUi.Btn.TOGGLE).apply {
                    isSelected = option.first == current
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener {
                        if (option.first == current) return@setOnClickListener
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(option.first))
                    }
                }, params)
            }
            panel.addView(row)
        }
        container.addView(panel)
    }

    /** Guía (T63): el índice de temas y la bienvenida, al principio de Comportamiento. */
    private fun addGuideButtons(container: LinearLayout) {
        val gap = CrtUi.dimen(this, R.dimen.crt_space_xs)
        val guide = GuideDialog(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(CrtUi.button(this, getString(R.string.btn_guide)).apply { setOnClickListener { guide.showIndex() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(CrtUi.button(this, getString(R.string.btn_welcome)).apply { setOnClickListener { guide.showWelcome() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
        // Acerca de / Licencias (T94)
        row.addView(CrtUi.button(this, getString(R.string.btn_about)).apply { setOnClickListener { AboutDialog(this@AppSettingsActivity).show() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
        container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_s)
            bottomMargin = CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_s)
        })
    }

    /** Un botón "? TEMA" bajo un ajuste: abre la tarjeta de ayuda de ese tema (T63). */
    private fun addGuideLink(container: LinearLayout, topic: GuideDialog.Topic) {
        container.addView(CrtUi.button(this, "? " + getString(topic.title), CrtUi.Btn.TOGGLE).apply {
            setOnClickListener { GuideDialog(this@AppSettingsActivity).show(topic) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Rendimiento y uso (T66): el contador de FPS (esquina, tamaño, opacidad, qué muestra, intervalo, solo con el menú abierto, aviso
     * de frames perdidos), el tope de FPS de salida y el aspecto del botón flotante. Todo actúa al momento, sin reiniciar la sesión.
     */
    private fun addPerformanceSection(container: LinearLayout) {
        val svc = ShaderOverlayAccessibilityService
        fun perf() = svc.applyPerfSettings()
        container.addView(CrtUi.text(this, CrtUi.Text.TITLE, getString(R.string.settings_sec_perf)).apply {
            setPadding(0, CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_m), 0, 0)
        })
        addToggleRow(container, R.string.label_fps_counter, R.string.fps_counter_description, PerfSettings.isFpsCounter(this)) {
            PerfSettings.setFpsCounter(this, it); perf()
        }
        addGuideLink(container, GuideDialog.FPS)
        val corners = listOf(R.string.fps_corner_tl, R.string.fps_corner_tr, R.string.fps_corner_bl, R.string.fps_corner_br).map { getString(it) }
        addChoiceRow(container, R.string.label_fps_corner, null, corners, PerfSettings.getFpsCorner(this)) { PerfSettings.setFpsCorner(this, it); perf() }
        val sizes = listOf(R.string.size_small, R.string.size_medium, R.string.size_large).map { getString(it) }
        addChoiceRow(container, R.string.label_fps_size, null, sizes, PerfSettings.getFpsSize(this)) { PerfSettings.setFpsSize(this, it); perf() }
        val opacities = listOf(30, 50, 70, 100)
        addChoiceRow(container, R.string.label_fps_opacity, null, opacities.map { "$it%" },
            opacities.indexOfFirst { it >= PerfSettings.getFpsOpacity(this) }.let { if (it < 0) opacities.lastIndex else it }) {
            PerfSettings.setFpsOpacity(this, opacities[it]); perf()
        }
        addChoiceRow(container, R.string.label_fps_detail, null, listOf(getString(R.string.fps_detail_basic), getString(R.string.fps_detail_full)),
            PerfSettings.getFpsDetail(this)) { PerfSettings.setFpsDetail(this, it); perf() }
        addChoiceRow(container, R.string.label_fps_interval, null, listOf("0.5 s", "1 s"), PerfSettings.getFpsInterval(this)) {
            PerfSettings.setFpsInterval(this, it); perf()
        }
        addToggleRow(container, R.string.label_fps_ui, R.string.fps_ui_description, PerfSettings.isFpsShowUi(this)) {
            PerfSettings.setFpsShowUi(this, it); perf()
        }
        addToggleRow(container, R.string.label_fps_only_menu, R.string.fps_only_menu_description, PerfSettings.isFpsOnlyWithMenu(this)) {
            PerfSettings.setFpsOnlyWithMenu(this, it); perf()
        }
        addToggleRow(container, R.string.label_fps_lost, R.string.fps_lost_description, PerfSettings.isFpsLostWarning(this)) {
            PerfSettings.setFpsLostWarning(this, it); perf()
        }
        val caps = PerfSettings.FPS_CAPS
        addChoiceRow(container, R.string.label_fps_cap, R.string.fps_cap_description,
            caps.map { if (it == 0) getString(R.string.fps_cap_none) else it.toString() }, caps.indexOf(PerfSettings.getFpsCap(this)).coerceAtLeast(0)) {
            PerfSettings.setFpsCap(this, caps[it]); perf()
        }
        // Captura reducida (R12): la zona se vuelve a calcular y, si cambia el tamaño necesario, el servicio recrea la captura
        addChoiceRow(container, R.string.label_capture_scale, R.string.capture_scale_description,
            listOf(getString(R.string.capture_scale_full), getString(R.string.capture_scale_quality), getString(R.string.capture_scale_performance)),
            PerfSettings.getCaptureScale(this)) {
            PerfSettings.setCaptureScale(this, it); com.sanabria.shadervulkan.native.VulkanEngineBridge.refreshZone()
        }
        // Modo batería (T71)
        addToggleRow(container, R.string.label_battery_mode, R.string.battery_mode_description, PerfSettings.isBatteryMode(this)) {
            PerfSettings.setBatteryMode(this, it); BatterySaver.restart(this)
        }
        val thresholds = PerfSettings.BATTERY_THRESHOLDS
        addChoiceRow(container, R.string.label_battery_threshold, null, thresholds.map { "$it%" },
            thresholds.indexOf(PerfSettings.getBatteryThreshold(this)).coerceAtLeast(0)) {
            PerfSettings.setBatteryThreshold(this, thresholds[it]); BatterySaver.restart(this)
        }
        addChoiceRow(container, R.string.label_battery_action, null,
            listOf(getString(R.string.battery_action_reduce), getString(R.string.battery_action_cap), getString(R.string.battery_action_pause)),
            PerfSettings.getBatteryAction(this)) {
            PerfSettings.setBatteryAction(this, it); BatterySaver.restart(this)
        }
        addChoiceRow(container, R.string.label_button_size, null, sizes, PerfSettings.getButtonSize(this)) {
            PerfSettings.setButtonSize(this, it); svc.refreshButtonStyle()
        }
        val btnOpacities = listOf(10, 27, 50, 100)
        addChoiceRow(container, R.string.label_button_opacity, null, btnOpacities.map { "$it%" },
            btnOpacities.indexOfFirst { it >= PerfSettings.getButtonRestOpacity(this) }.let { if (it < 0) btnOpacities.lastIndex else it }) {
            PerfSettings.setButtonRestOpacity(this, btnOpacities[it]); svc.refreshButtonStyle()
        }
    }

    /** Fila de opciones excluyentes (hasta 5 botones en una línea): etiqueta, descripción opcional y botones TOGGLE. */
    private fun addChoiceRow(container: LinearLayout, @StringRes label: Int, @StringRes description: Int?, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val gap = CrtUi.dimen(this, R.dimen.crt_space_xs)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = CrtUi.dimen(this@AppSettingsActivity, R.dimen.crt_space_s)
            setPadding(0, pad, 0, pad)
        }
        panel.addView(CrtUi.text(this, CrtUi.Text.LABEL, getString(label)))
        if (description != null) panel.addView(CrtUi.text(this, CrtUi.Text.CAPTION, getString(description)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<Button>()
        options.forEachIndexed { i, text ->
            val b = CrtUi.button(this, text, CrtUi.Btn.TOGGLE).apply {
                isSelected = i == selected
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    buttons.forEachIndexed { j, other -> other.isSelected = j == i }
                    onPick(i)
                }
            }
            buttons += b
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = gap
                if (i > 0) marginStart = gap
            })
        }
        panel.addView(row)
        container.addView(panel)
    }

    /** Fila con interruptor ON/OFF (item_setting_toggle): se pinta al momento y avisa del nuevo valor. */
    private fun addToggleRow(container: LinearLayout, @StringRes label: Int, @StringRes description: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_setting_toggle, container, false)
        row.findViewById<TextView>(R.id.txt_setting_label).setText(label)
        row.findViewById<TextView>(R.id.txt_setting_desc).setText(description)
        val toggle = row.findViewById<Button>(R.id.btn_setting_toggle)
        fun paint(on: Boolean) {
            toggle.isSelected = on
            toggle.setText(if (on) R.string.state_on else R.string.state_off)
        }
        paint(initial)
        toggle.setOnClickListener {
            val on = !toggle.isSelected
            paint(on)
            onChange(on)
        }
        container.addView(row)
    }
}
