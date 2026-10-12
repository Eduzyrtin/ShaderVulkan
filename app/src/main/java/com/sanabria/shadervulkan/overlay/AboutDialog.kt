package com.sanabria.shadervulkan.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.util.TypedValue
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.sanabria.shadervulkan.BuildConfig
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * Acerca de / Licencias (T94). La tarjeta lleva la versión, el aviso de la licencia propia (GPLv3 o posterior) y de dónde vienen las
 * piezas de terceros; LICENCIAS abre la lista y cada entrada el texto completo, que viaja en assets/licenses/ (los mismos ficheros que
 * los de la raíz del repositorio y de cpp/third_party). Mismo estilo que la guía (T63).
 */
class AboutDialog(private val context: Context) {

    private class License(@StringRes val label: Int, val asset: String)

    private var dialog: AlertDialog? = null

    fun show() {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        val ctx = CrtUi.dialogContext(context)
        val text = CrtUi.text(ctx, CrtUi.Text.BODY, context.getString(R.string.about_body, version)).apply {
            setLineSpacing(CrtUi.dp(ctx, 3f).toFloat(), 1f)
        }
        // T94/T95: debajo del texto, el enlace al código fuente (lo exige la GPL; sale cuando hay dirección) y, solo en el sabor github,
        // el de apoyo al proyecto
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(text) }
        val gap = CrtUi.dimen(ctx, R.dimen.crt_space_s)
        fun link(@StringRes label: Int, url: String) {
            if (url.isBlank()) return
            column.addView(CrtUi.button(ctx, context.getString(label)).apply { setOnClickListener { open(url) } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        link(R.string.btn_source_code, BuildConfig.SOURCE_CODE_URL)
        link(R.string.btn_support, BuildConfig.SUPPORT_URL)
        build(R.string.about_title, ScrollView(ctx).apply { addView(column) }, licensesButton = true)
    }

    private fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun showLicenses() {
        val ctx = CrtUi.dialogContext(context)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        LICENSES.forEach { lic ->
            list.addView(CrtUi.button(ctx, context.getString(lic.label)).apply {
                setOnClickListener { showText(lic) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = CrtUi.dimen(ctx, R.dimen.crt_space_xs) })
        }
        build(R.string.licenses_title, ScrollView(ctx).apply { addView(list) }, licensesButton = false)
    }

    private fun showText(lic: License) {
        val body = try {
            context.assets.open("licenses/${lic.asset}").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
        val ctx = CrtUi.dialogContext(context)
        // Los textos legales vienen maquetados a 80 columnas: letra pequeña y de ancho fijo
        val text = CrtUi.text(ctx, CrtUi.Text.CAPTION, body).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(ctx).apply {
            addView(android.widget.HorizontalScrollView(ctx).apply { addView(text) })
        }
        build(lic.label, scroll, licensesButton = false, back = true)
    }

    private fun build(@StringRes title: Int, content: android.view.View, licensesButton: Boolean, back: Boolean = false) {
        dismiss()
        val ctx = CrtUi.dialogContext(context)
        val builder = AlertDialog.Builder(ctx, R.style.CrtAlertDialog)
            .setTitle(title)
            .setView(CrtUi.dialogContent(content))
            .setPositiveButton(R.string.btn_got_it, null)
        if (licensesButton || back) builder.setNeutralButton(R.string.btn_licenses) { _, _ -> showLicenses() }
        val d = builder.create()
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

    private companion object {
        val LICENSES = listOf(
            License(R.string.license_app, "gpl-3.0.txt"),
            License(R.string.license_glslang, "glslang.txt"),
            License(R.string.license_spirv_cross, "spirv-cross.txt"),
            License(R.string.license_androidx, "apache-2.0.txt"),
        )
    }
}
