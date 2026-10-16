package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.model.ShaderStack
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.ui.AutoScroll
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * Editor de conjuntos de presets (T45): lista los shaders que forman el activo de la zona en edición, en orden de ejecución, y
 * deja anteponer otro (se ejecuta antes) o anexarlo (después), subir, bajar y quitar piezas. Los cambios se acumulan y solo se
 * cargan al pulsar APLICAR (cada carga recompila todos los pases). Con `overlay = true` la ventana va sobre las demás
 * (menú flotante); desde la pantalla principal es un diálogo normal.
 */
class StackEditor(private val context: Context, private val overlay: Boolean) {

    private var dialog: AlertDialog? = null
    private val picker = ShaderPicker(context, applyToSession = overlay, pickOnly = true)
    private val guide = GuideDialog(context, overlay) // ayuda del sitio (T63)

    val isShowing: Boolean get() = dialog?.isShowing == true

    /** @param onApplied se llama tras pedir la carga del conjunto nuevo. */
    fun show(onApplied: () -> Unit) {
        dismiss()
        val dialogCtx = CrtUi.dialogContext(context)
        val original = ShaderPreferences.getEditShaderId(context)?.let { ShaderStack.parts(it) } ?: emptyList()
        val parts = original.toMutableList()
        val gap = CrtUi.dimen(dialogCtx, R.dimen.crt_space_s)

        val list = LinearLayout(dialogCtx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var applyBtn: android.widget.Button
        lateinit var rebuild: () -> Unit

        fun add(path: String, atStart: Boolean) {
            if (atStart) parts.add(0, path) else parts.add(path)
            rebuild()
        }
        fun pick(atStart: Boolean) {
            picker.show { item -> add(item.path, atStart) }
        }

        val prependBtn = CrtUi.button(dialogCtx, context.getString(R.string.btn_stack_prepend)).apply { setOnClickListener { pick(true) } }
        val appendBtn = CrtUi.button(dialogCtx, context.getString(R.string.btn_stack_append)).apply { setOnClickListener { pick(false) } }

        rebuild = {
            list.removeAllViews()
            parts.forEachIndexed { i, path ->
                val row = LinearLayout(dialogCtx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = CrtUi.text(dialogCtx, CrtUi.Text.BODY, "${i + 1}. ${ShaderStack.partName(path)}").apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                AutoScroll.attach(name)
                row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                fun small(label: String, enabled: Boolean, kind: CrtUi.Btn = CrtUi.Btn.TOGGLE, action: () -> Unit) {
                    val b = CrtUi.button(dialogCtx, label, kind).apply {
                        isEnabled = enabled
                        alpha = if (enabled) 1f else 0.35f
                        minWidth = CrtUi.dimen(dialogCtx, R.dimen.crt_touch_compact)
                        setOnClickListener { action() }
                    }
                    row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap / 2 })
                }
                small("^", i > 0) { parts.add(i - 1, parts.removeAt(i)); rebuild() }
                small("v", i < parts.size - 1) { parts.add(i + 1, parts.removeAt(i)); rebuild() }
                small("X", parts.size > 1, CrtUi.Btn.WARN) { parts.removeAt(i); rebuild() }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap / 2 })
            }
            if (parts.isEmpty()) list.addView(CrtUi.text(dialogCtx, CrtUi.Text.CAPTION, context.getString(R.string.stack_empty)))
            val full = parts.size >= ShaderStack.MAX_PARTS
            prependBtn.isEnabled = !full
            appendBtn.isEnabled = !full
            applyBtn.isEnabled = parts.isNotEmpty() && parts != original
            applyBtn.alpha = if (applyBtn.isEnabled) 1f else 0.5f
        }

        val content = LinearLayout(dialogCtx).apply {
            orientation = LinearLayout.VERTICAL
            addView(CrtUi.text(dialogCtx, CrtUi.Text.CAPTION, context.getString(R.string.stack_help)))
            addView(CrtUi.divider(dialogCtx))
            addView(ScrollView(dialogCtx).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(CrtUi.divider(dialogCtx))
            addView(LinearLayout(dialogCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(prependBtn, LinearLayout.LayoutParams(0, -2, 1f))
                addView(appendBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                addView(CrtUi.button(dialogCtx, "?", CrtUi.Btn.TOGGLE).apply { setOnClickListener { guide.show(GuideDialog.COMBINE) } },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
            })
        }

        val d = AlertDialog.Builder(dialogCtx, R.style.CrtAlertDialog)
            .setTitle(R.string.dialog_stack_title)
            .setView(CrtUi.dialogContent(content))
            .setPositiveButton(R.string.btn_stack_apply, null) // se reasigna abajo: APLICAR no debe cerrar si no hay nada que aplicar
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        if (overlay) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        d.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        d.setOnDismissListener { picker.dismiss(); guide.dismiss(); if (dialog === d) dialog = null }
        dialog = d
        d.show()
        applyBtn = d.getButton(AlertDialog.BUTTON_POSITIVE)
        applyBtn.setOnClickListener {
            if (!applyBtn.isEnabled) return@setOnClickListener
            apply(parts)
            d.dismiss()
            onApplied()
        }
        rebuild()
    }

    /** Carga el conjunto como shader de la zona en edición (A, o la B con preset propio). */
    private fun apply(parts: List<String>) {
        val id = ShaderStack.make(parts) ?: return
        val name = ShaderStack.displayName(parts)
        if (ShaderPreferences.editsZoneB(context)) {
            ShaderPreferences.setZoneBShader(context, id, name)
            ShaderCaptureService.requestShaderB(context, id)
        } else {
            ShaderPreferences.setActiveShaderId(context, id, name)
            ShaderCaptureService.requestShader(context, id)
        }
    }

    fun dismiss() {
        picker.dismiss()
        guide.dismiss()
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }
}
