package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderItem
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.ui.AutoScroll
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * Selector de shader del menú flotante (T65): los presets internos (assets/shaders) y, si hay, la carpeta externa elegida en
 * la pantalla principal (navegable). Elegir uno lo guarda como activo y se lo pide al servicio con ACTION_LOAD_SHADER, igual
 * que la lista de MainActivity: sirve con la sesión en Captura o en Glass, sin cerrar nada ni pedir permiso. Con
 * `applyToSession = false` (pantalla de apps automáticas, T62) solo elige: no cambia el shader activo ni toca la sesión.
 */
class ShaderPicker(
    private val context: Context,
    private val applyToSession: Boolean = true,
    private val zoneB: Boolean = false,
    /** Solo devuelve el elegido: no lo guarda ni lo carga (lo usa el editor de conjuntos, T45). */
    private val pickOnly: Boolean = false
) {

    private var dialog: AlertDialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    /** @param onPicked se llama tras pedir la carga, para que el menú se rehaga con los parámetros del shader nuevo. */
    fun show(onPicked: (ShaderItem) -> Unit) {
        dismiss()
        val dialogCtx = CrtUi.dialogContext(context)
        val items = mutableListOf<ShaderItem>()
        val activeId = if (zoneB) ShaderPreferences.getZoneBShaderId(context) else ShaderPreferences.getActiveShaderId(context)
        val rootUri = ShaderPreferences.getShaderFolderUri(context)
        // Con carpeta externa elegida, el selector se abre ya dentro de ella (sus presets son los que se quieren casi siempre);
        // ".." en su raíz lleva a los presets internos
        var folder: DocumentFile? = rootUri?.let {
            try { DocumentFile.fromTreeUri(context, Uri.parse(it)) } catch (_: Exception) { null }
        }

        val list = ListView(dialogCtx).apply {
            divider = null
            selector = ColorDrawable(ColorUtils.setAlphaComponent(CrtUi.color(dialogCtx, R.attr.crtFaint), 90))
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[position]
                val tv = (convertView as? TextView) ?: CrtUi.text(dialogCtx, CrtUi.Text.BODY).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val h = CrtUi.dimen(dialogCtx, R.dimen.crt_space_s)
                    setPadding(h, CrtUi.dimen(dialogCtx, R.dimen.crt_space_s), h, CrtUi.dimen(dialogCtx, R.dimen.crt_space_s))
                    minHeight = CrtUi.dimen(dialogCtx, R.dimen.crt_touch_compact)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                AutoScroll.attach(tv)
                tv.text = when {
                    item.isFolder -> "<DIR> ${item.name}"
                    item.id == activeId -> "> ${item.name}"
                    else -> "  ${item.name}"
                }
                return tv
            }
        }
        list.adapter = adapter

        fun fill() {
            items.clear()
            val dir = folder
            if (dir == null) {
                try {
                    context.assets.list("shaders")?.filter { it.endsWith(".slangp", ignoreCase = true) }?.sorted()?.forEach { file ->
                        // id == path, igual que MainActivity.loadShadersFromAssets (clave de perfiles y ruta de carga)
                        items.add(ShaderItem("shaders/$file", file.substringBeforeLast('.'), ".slangp", "shaders/$file"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo listar assets/shaders: ${e.message}")
                }
                if (rootUri != null) {
                    items.add(0, ShaderItem(ShaderItem.EXTERNAL_ROOT_ID, context.getString(R.string.list_external_folder), "", rootUri, "", isFolder = true))
                }
            } else {
                items.add(ShaderItem(ShaderItem.FOLDER_UP_ID, "..", "", "", "", isFolder = true))
                try {
                    val files = dir.listFiles().sortedBy { it.name?.lowercase() }
                    files.filter { it.isDirectory }.forEach {
                        items.add(ShaderItem("folder_${it.uri}", it.name ?: "", "", it.uri.toString(), "", isFolder = true))
                    }
                    files.filter { !it.isDirectory && it.name?.endsWith(".slangp", ignoreCase = true) == true }.forEach {
                        items.add(ShaderItem(it.uri.toString(), it.name!!.substringBeforeLast('.'), ".slangp", it.uri.toString(), ""))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo listar la carpeta externa: ${e.message}")
                }
            }
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }

        // Para volver con ".." desde una subcarpeta hay que saber la anterior
        val stack = ArrayDeque<DocumentFile>()
        list.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            when {
                item.id == ShaderItem.EXTERNAL_ROOT_ID -> {
                    folder = DocumentFile.fromTreeUri(context, Uri.parse(item.path))
                    stack.clear()
                    fill()
                }
                item.id == ShaderItem.FOLDER_UP_ID -> {
                    folder = stack.removeLastOrNull()
                    fill()
                }
                item.isFolder -> {
                    folder?.let { stack.addLast(it) }
                    folder = DocumentFile.fromTreeUri(context, Uri.parse(item.path)) ?: folder
                    fill()
                }
                else -> {
                    dismiss()
                    if (pickOnly) {
                        // El que llama decide qué hacer con él
                    } else if (zoneB) {
                        // Preset propio para la zona B (T18): no cambia el shader activo (el de la A)
                        ShaderPreferences.setZoneBShader(context, item.id, item.name)
                        ShaderPreferences.setEditZone(context, 1)
                        ShaderCaptureService.requestShaderB(context, item.path)
                    } else if (applyToSession) {
                        ShaderPreferences.setActiveShaderId(context, item.id, item.name)
                        ShaderCaptureService.requestShader(context, item.path)
                    }
                    onPicked(item)
                }
            }
        }
        fill()

        val content = LinearLayout(dialogCtx).apply {
            orientation = LinearLayout.VERTICAL
            val maxH = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH))
        }
        val d = AlertDialog.Builder(dialogCtx, R.style.CrtAlertDialog)
            .setTitle(R.string.dialog_pick_shader_title)
            .setView(CrtUi.dialogContent(content))
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        if (applyToSession) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
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
        private const val TAG = "ShaderPicker"
    }
}
