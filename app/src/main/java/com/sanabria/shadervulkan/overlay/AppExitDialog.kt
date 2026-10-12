package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * Aviso al salir de una app con perfil (T61): el shader que se activó solo se pausa, se cierra o se deja como está. Es una
 * ventana de accesibilidad como el selector de shader, así que sale encima de cualquier app.
 */
class AppExitDialog(private val context: Context) {

    private var dialog: AlertDialog? = null

    fun show(appLabel: String, onPause: () -> Unit, onClose: () -> Unit, onKeep: () -> Unit) {
        dismiss()
        val dialogCtx = CrtUi.dialogContext(context)
        val message = CrtUi.text(dialogCtx, CrtUi.Text.BODY, context.getString(R.string.exit_dialog_message, appLabel))
        val d = AlertDialog.Builder(dialogCtx, R.style.CrtAlertDialog)
            .setTitle(R.string.exit_dialog_title)
            .setView(CrtUi.dialogContent(message))
            .setPositiveButton(R.string.btn_exit_pause) { _, _ -> onPause() }
            .setNegativeButton(R.string.btn_exit_close) { _, _ -> onClose() }
            .setNeutralButton(R.string.btn_exit_keep) { _, _ -> onKeep() }
            .setCancelable(false) // sin gesto de atrás en una ventana de accesibilidad: se elige una de las tres
            .create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        d.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        try {
            d.show()
        } catch (e: Exception) {
            Log.w("AppExitDialog", "No se pudo mostrar el aviso: ${e.message}")
            dialog = null
        }
    }

    fun dismiss() {
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }
}
