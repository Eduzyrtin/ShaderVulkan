package com.sanabria.shadervulkan.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.sanabria.shadervulkan.R
import kotlin.math.roundToInt

/**
 * Estilo CRT para las vistas creadas en código (menú flotante, HUD de carga, editor de zona, diálogos). Textos y botones
 * salen de los mismos estilos que los layouts (themes.xml, dimens.xml, atributos de color de la paleta de CrtTheme), así que el overlay y las pantallas de la
 * app no pueden divergir: para cambiar el aspecto se tocan los recursos, no este fichero.
 */
object CrtUi {

    enum class Text(val style: Int) {
        BODY(R.style.CrtText),
        TITLE(R.style.CrtText_Title),
        LABEL(R.style.CrtText_Label),
        CAPTION(R.style.CrtText_Caption),
    }

    /** Variantes de botón: estilo para crearlo y, para cambiarlo en caliente, fondo, color de texto (lista de estados) y atributo del color del glow (0 = sin glow). */
    enum class Btn(val style: Int, val background: Int, val textColor: Int, @AttrRes val glow: Int) {
        NORMAL(R.style.CrtButton_Small, R.drawable.bg_crt_button, R.color.crt_text_button, R.attr.crtGlow),
        PRIMARY(R.style.CrtButton_Primary, R.drawable.bg_crt_button_primary, R.color.crt_text_on_hot, 0),
        ALERT(R.style.CrtButton_Alert, R.drawable.bg_crt_button_alert, R.color.crt_text_alert, R.attr.crtGlowAlert),
        WARN(R.style.CrtButton_Warn, R.drawable.bg_crt_button_warn, R.color.crt_text_warn, R.attr.crtGlowWarn),
        TOGGLE(R.style.CrtButton_Toggle, R.drawable.bg_crt_toggle, R.color.crt_text_toggle, 0),
    }

    /**
     * Contexto con el tema de la app y la paleta elegida en Ajustes. Los servicios (de donde salen los overlays) no aplican el tema
     * del manifest: sin esto los Spinner, EditText y diálogos creados en código saldrían con el aspecto por defecto del sistema, y
     * sin la paleta con los colores clásicos. Se pide uno nuevo cada vez que se abre una ventana: así recoge los cambios de Ajustes.
     */
    fun themed(ctx: Context): Context =
        ContextThemeWrapper(ctx, R.style.Theme_ShaderVulkan).also { CrtTheme.applyTo(it) }

    /** Contexto para un AlertDialog creado en un servicio: [themed] más el tema de diálogo. */
    fun dialogContext(ctx: Context): Context = ContextThemeWrapper(themed(ctx), R.style.CrtAlertDialog)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dimen(ctx: Context, @DimenRes id: Int): Int = ctx.resources.getDimensionPixelSize(id)
    /** Color de un atributo del tema (?attr/crtX): el que dé la paleta aplicada a [ctx]. */
    fun color(ctx: Context, @AttrRes attr: Int): Int = CrtTheme.color(ctx, attr)

    fun font(ctx: Context): Typeface =
        try { ResourcesCompat.getFont(ctx, R.font.crt_font) } catch (_: Exception) { null } ?: Typeface.MONOSPACE

    fun text(ctx: Context, kind: Text, value: CharSequence = ""): TextView =
        TextView(ctx, null, 0, kind.style).apply { text = value }

    /** Botón compacto (40 dp, texto de etiqueta) de la variante pedida: el tamaño que usan los overlays. */
    fun button(ctx: Context, label: CharSequence, kind: Btn = Btn.NORMAL): Button =
        Button(ctx, null, 0, kind.style).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(ctx, R.dimen.crt_text_label).toFloat())
            minHeight = dimen(ctx, R.dimen.crt_touch_compact)
            minimumHeight = minHeight
            // Los interruptores van en filas de 4-5: menos margen y una sola línea
            val h = dimen(ctx, if (kind == Btn.TOGGLE) R.dimen.crt_space_xs else R.dimen.crt_space_m)
            setPadding(h, paddingTop, h, paddingBottom)
            maxLines = if (kind == Btn.TOGGLE) 1 else 2
        }

    /** Cambia la variante de un botón ya creado (p. ej. RUN ↔ STOP según el estado de la sesión). */
    fun applyButton(button: TextView, kind: Btn) {
        val ctx = button.context
        button.background = ContextCompat.getDrawable(ctx, kind.background)
        button.setTextColor(ContextCompat.getColorStateList(ctx, kind.textColor))
        if (kind.glow != 0) button.setShadowLayer(CrtTheme.number(ctx, R.attr.crtGlowM), 0f, 0f, color(ctx, kind.glow))
        else button.setShadowLayer(0f, 0f, 0f, 0)
    }

    /** Línea separadora de fósforo tenue. */
    fun divider(ctx: Context, marginV: Int = dimen(ctx, R.dimen.crt_space_s)): View =
        View(ctx).apply {
            setBackgroundColor(color(ctx, R.attr.crtFaint))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dimen(ctx, R.dimen.crt_stroke))
                .apply { setMargins(0, marginV, 0, marginV) }
        }

    /** Fondo de ventana flotante (trozo de tubo con glow, grano, viñeta y marco) con las scanlines por encima del contenido. */
    fun asScreen(view: View) {
        view.background = ContextCompat.getDrawable(view.context, R.drawable.bg_crt_screen)
        view.foreground = ContextCompat.getDrawable(view.context, R.drawable.bmp_scanlines)
    }

    /** Barra de progreso por bloques (crt_progress). */
    fun progressBar(ctx: Context): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.crt_progress)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dimen(ctx, R.dimen.crt_progress_height))
        }

    /** SeekBar con el aspecto del slider de los ajustes: pista fina y un bloque de cursor como mando. */
    fun seekBar(ctx: Context): SeekBar =
        SeekBar(ctx).apply {
            progressTintList = ColorStateList.valueOf(color(ctx, R.attr.crtPhosphor))
            // El fondo de la pista se pinta con alfa 0.3 (disabledAlpha): con el fósforo entero queda como la pista tenue del slider
            progressBackgroundTintList = ColorStateList.valueOf(color(ctx, R.attr.crtPhosphor))
            thumb = GradientDrawable().apply {
                setColor(color(ctx, R.attr.crtHot))
                setSize(dp(ctx, 6f), dp(ctx, 22f))
                cornerRadius = dp(ctx, 1f).toFloat()
            }
            splitTrack = true
            minimumHeight = dimen(ctx, R.dimen.crt_touch_compact)
            // La pista alineada con el texto de encima (el relleno por defecto de SeekBar es de 16 dp)
            setPadding(dp(ctx, 4f), paddingTop, dp(ctx, 4f), paddingBottom)
        }

    /**
     * El mensaje de un AlertDialog (layout de appcompat) lleva un estilo fijo de 16 sp con el color principal, que el tema no
     * puede cambiar: tras show() se le aplica el cuerpo de diálogo de la app (CrtDialogBody).
     */
    fun styleMessage(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextAppearance(R.style.CrtDialogBody)
            setLineSpacing(dp(context, 2f).toFloat(), 1f)
        }
    }

    /** Envuelve el contenido de un diálogo con el margen lateral estándar (si no, el campo de texto toca los bordes). */
    fun dialogContent(view: View): View {
        val ctx = view.context
        val h = dp(ctx, 24f)
        return FrameLayout(ctx).apply {
            setPadding(h, dimen(ctx, R.dimen.crt_space_s), h, 0)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
