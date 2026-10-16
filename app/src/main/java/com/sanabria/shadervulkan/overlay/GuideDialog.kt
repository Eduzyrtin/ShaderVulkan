package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.sanabria.shadervulkan.BuildConfig
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * Guía integrada (T63). Una tarjeta corta por tema; el texto de cada tema vive solo en strings.xml (guide_<tema>_title / _body) y se
 * reutiliza en la ayuda del sitio donde surge la duda ("?"), en el índice de Ajustes, en la bienvenida y en la explicación de
 * Accesibilidad que va antes de pedir el permiso (la misma que exige Google Play, T96). Solo hay una tarjeta abierta a la vez.
 * Con `overlay = true` la ventana va sobre las demás (menú flotante, editor de conjuntos).
 */
class GuideDialog(private val context: Context, private val overlay: Boolean = false) {

    class Topic(val id: String, @param:StringRes val title: Int, @param:StringRes val body: Int)

    private var dialog: AlertDialog? = null

    /** Una tarjeta con un tema. */
    fun show(topic: Topic) {
        present(topic.title, topic.body, positive = R.string.btn_got_it, onPositive = null, negative = null)
    }

    /** Índice: una entrada por tema; tocarla abre su tarjeta. */
    fun showIndex() {
        val ctx = CrtUi.dialogContext(context)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        TOPICS.forEach { topic ->
            list.addView(CrtUi.button(ctx, context.getString(topic.title)).apply {
                setOnClickListener { show(topic) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = CrtUi.dimen(ctx, R.dimen.crt_space_xs) })
        }
        val scroll = ScrollView(ctx).apply { addView(list) }
        build(R.string.guide_index_title, scroll, positive = R.string.btn_got_it, onPositive = null, negative = null)
    }

    /** Bienvenida mínima: tres ideas, con "saltar" y "abrir la guía". */
    fun showWelcome() {
        present(R.string.guide_welcome_title, R.string.guide_welcome_body, positive = R.string.guide_open,
            onPositive = { showIndex() }, negative = R.string.btn_skip)
        ShaderPreferences.setWelcomeSeen(context, true)
    }

    /** Explicación de Accesibilidad antes de mandar al usuario a los ajustes del sistema; [onAccept] lo lleva allí. */
    fun confirmAccessibility(onAccept: () -> Unit) {
        // T95: el APK que se instala a mano (sabor github) llega con los ajustes restringidos de Android 13+: se explica cómo quitarlos
        val restricted = BuildConfig.SIDELOAD && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val base = context.getString(R.string.guide_access_confirm)
        val body = if (restricted) base + "\n\n" + context.getString(R.string.access_restricted_hint) else base
        presentText(R.string.guide_access_title, body, positive = R.string.btn_accept, onPositive = onAccept, negative = R.string.btn_cancel)
    }

    private fun present(@StringRes title: Int, @StringRes body: Int, @StringRes positive: Int, onPositive: (() -> Unit)?,
                        @StringRes negative: Int?) = presentText(title, context.getString(body), positive, onPositive, negative)

    private fun presentText(@StringRes title: Int, body: String, @StringRes positive: Int, onPositive: (() -> Unit)?,
                            @StringRes negative: Int?) {
        val ctx = CrtUi.dialogContext(context)
        val text = CrtUi.text(ctx, CrtUi.Text.BODY, body).apply {
            setLineSpacing(CrtUi.dp(ctx, 3f).toFloat(), 1f)
        }
        val scroll = ScrollView(ctx).apply { addView(text) }
        build(title, scroll, positive, onPositive, negative)
    }

    private fun build(@StringRes title: Int, content: android.view.View, @StringRes positive: Int, onPositive: (() -> Unit)?, @StringRes negative: Int?) {
        dismiss()
        val ctx = CrtUi.dialogContext(context)
        val builder = AlertDialog.Builder(ctx, R.style.CrtAlertDialog)
            .setTitle(title)
            .setView(CrtUi.dialogContent(content))
            .setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
        if (negative != null) builder.setNegativeButton(negative, null)
        val d = builder.create()
        if (overlay) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        d.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    fun dismiss() {
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }

    companion object {
        val MODES = Topic("modes", R.string.guide_modes_title, R.string.guide_modes_body)
        val ACCESS = Topic("access", R.string.guide_access_title, R.string.guide_access_body)
        val ZONE = Topic("zone", R.string.guide_zone_title, R.string.guide_zone_body)
        val COMBINE = Topic("combine", R.string.guide_combine_title, R.string.guide_combine_body)
        val FPS = Topic("fps", R.string.guide_fps_title, R.string.guide_fps_body)
        val APPS = Topic("apps", R.string.guide_apps_title, R.string.guide_apps_body)
        val PARAMS = Topic("params", R.string.guide_params_title, R.string.guide_params_body)
        val QUICK = Topic("quick", R.string.guide_quick_title, R.string.guide_quick_body)

        /** Los temas del índice, en el orden en que se explican. */
        val TOPICS = listOf(MODES, ACCESS, ZONE, COMBINE, PARAMS, FPS, APPS, QUICK)
    }
}
