package com.sanabria.shadervulkan

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.sanabria.shadervulkan.model.AppProfileStore
import com.sanabria.shadervulkan.model.AppProfileStore.Mode
import com.sanabria.shadervulkan.model.ShaderParamStore
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.overlay.ShaderPicker
import com.sanabria.shadervulkan.ui.AutoScroll
import com.sanabria.shadervulkan.ui.CrtActivity
import com.sanabria.shadervulkan.ui.CrtUi
import java.util.concurrent.Executors

/**
 * Apps automáticas (T62): las apps instaladas con su icono y lo que hace el shader en cada una (Captura, Glass, sin shader o nada).
 * Tocar una abre su perfil: modo, shader y perfil de ajustes del shader. Los perfiles también se crean desde el menú flotante
 * (GUARDAR APP), que guarda de golpe lo que se está viendo; esta pantalla sirve para revisarlos y cambiarlos.
 */
class AppProfilesActivity : CrtActivity() {

    private class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

    private val apps = mutableListOf<AppEntry>()
    private lateinit var adapter: BaseAdapter
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCrtTheme()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.crt_background)
            foreground = AppCompatResources.getDrawable(this@AppProfilesActivity, R.drawable.bmp_scanlines)
        }
        val gutter = resources.getDimensionPixelSize(R.dimen.crt_gutter)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left + gutter, top = bars.top + gutter, right = bars.right + gutter, bottom = bars.bottom + gutter)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_crt_header)
            val pad = CrtUi.dimen(this@AppProfilesActivity, R.dimen.crt_space_m)
            setPadding(0, 0, 0, pad)
            addView(CrtUi.text(this@AppProfilesActivity, CrtUi.Text.TITLE, getString(R.string.apps_title)))
            addView(CrtUi.text(this@AppProfilesActivity, CrtUi.Text.CAPTION, getString(R.string.apps_subtitle)))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = true
        }
        empty = CrtUi.text(this, CrtUi.Text.BODY, getString(R.string.apps_loading)).apply {
            gravity = Gravity.CENTER
            setPadding(0, CrtUi.dimen(this@AppProfilesActivity, R.dimen.crt_space_l), 0, 0)
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = rowFor(apps[position], convertView)
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> edit(apps[position]) }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(CrtUi.button(this, getString(R.string.btn_return)).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = CrtUi.dimen(this@AppProfilesActivity, R.dimen.crt_space_m) })
        setContentView(root)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (apps.isNotEmpty()) sortApps()
    }

    /** Las apps con icono de lanzador (las que el usuario abre), en un hilo aparte: cargar los iconos tarda. */
    private fun loadApps() {
        Executors.newSingleThreadExecutor().execute {
            val pm = packageManager
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        AppEntry(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm))
                    } catch (_: Exception) { null }
                }
            runOnUiThread {
                apps.clear()
                // Primera fila: el perfil por defecto (T60), para toda app que no tenga el suyo
                apps.add(AppEntry(AppProfileStore.DEFAULT_KEY, getString(R.string.apps_default_label), null))
                apps.addAll(found)
                empty.visibility = if (apps.size <= 1) View.VISIBLE else View.GONE
                empty.setText(R.string.apps_none)
                sortApps()
            }
        }
    }

    /** Las que tienen perfil, primero; luego por nombre. */
    private fun sortApps() {
        val withProfile = AppProfileStore.all(this).map { it.packageName }.toSet()
        apps.sortWith(compareBy<AppEntry>({ it.packageName != AppProfileStore.DEFAULT_KEY }, { it.packageName !in withProfile }, { it.label.lowercase() }))
        adapter.notifyDataSetChanged()
    }

    private fun summary(profile: AppProfileStore.AppProfile?): String = when {
        profile == null -> getString(R.string.apps_no_profile)
        profile.mode == Mode.OFF -> getString(R.string.apps_mode_off)
        else -> "${modeLabel(profile.mode)} - ${profile.shaderName}"
    }

    private fun modeLabel(mode: Mode) = getString(when (mode) {
        Mode.CAPTURE -> R.string.apps_mode_capture
        Mode.GLASS -> R.string.apps_mode_glass
        Mode.OFF -> R.string.apps_mode_off
    })

    private fun rowFor(app: AppEntry, convertView: View?): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = CrtUi.dimen(this@AppProfilesActivity, R.dimen.crt_space_s)
            setPadding(pad, pad, pad, pad)
            minimumHeight = CrtUi.dimen(this@AppProfilesActivity, R.dimen.crt_touch_compact)
            addView(ImageView(this@AppProfilesActivity).apply { id = R.id.app_row_icon }, LinearLayout.LayoutParams(CrtUi.dp(this@AppProfilesActivity, 36f), CrtUi.dp(this@AppProfilesActivity, 36f)))
            addView(LinearLayout(this@AppProfilesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(CrtUi.text(this@AppProfilesActivity, CrtUi.Text.BODY).apply { id = R.id.app_row_label; AutoScroll.attach(this) })
                addView(CrtUi.text(this@AppProfilesActivity, CrtUi.Text.CAPTION).apply { id = R.id.app_row_summary; AutoScroll.attach(this) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = pad })
        }
        val profile = AppProfileStore.get(this, app.packageName)
        row.findViewById<ImageView>(R.id.app_row_icon).setImageDrawable(app.icon)
        row.findViewById<TextView>(R.id.app_row_label).text = app.label
        row.findViewById<TextView>(R.id.app_row_summary).apply {
            text = summary(profile)
            alpha = if (profile == null) 0.6f else 1f
        }
        return row
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Editar el perfil de una app
    // ---------------------------------------------------------------------------------------------------------------------

    private fun edit(app: AppEntry) {
        val existing = AppProfileStore.get(this, app.packageName)
        // Estado que se está editando: se parte del perfil o, si no hay, del shader activo ahora
        var mode: Mode? = existing?.mode ?: (if (ShaderPreferences.getRenderMode(this) == 1) Mode.GLASS else Mode.CAPTURE)
        if (existing == null) mode = null // nada marcado: el usuario elige
        var shaderId: String? = existing?.shaderId ?: ShaderPreferences.getActiveShaderId(this)
        var shaderName: String = existing?.shaderName ?: ShaderPreferences.getActiveShaderName(this) ?: ""
        var profileName: String? = existing?.profileName ?: shaderId?.let { ShaderParamStore.getActiveProfileName(this, it) }

        val dialogCtx = CrtUi.dialogContext(this)
        val gap = CrtUi.dimen(dialogCtx, R.dimen.crt_space_s)
        val modeButtons = listOf(
            R.string.apps_mode_capture to Mode.CAPTURE, R.string.apps_mode_glass to Mode.GLASS, R.string.apps_mode_off to Mode.OFF
        ).map { (label, m) -> CrtUi.button(dialogCtx, getString(label), CrtUi.Btn.TOGGLE) to m }
        val shaderBtn = CrtUi.button(dialogCtx, "").also { AutoScroll.attach(it) }
        val profileBtn = CrtUi.button(dialogCtx, "").also { AutoScroll.attach(it) }
        val hint = CrtUi.text(dialogCtx, CrtUi.Text.CAPTION)

        fun refresh() {
            modeButtons.forEach { (b, m) -> b.isSelected = mode == m }
            val needsShader = mode == Mode.CAPTURE || mode == Mode.GLASS
            shaderBtn.visibility = if (needsShader) View.VISIBLE else View.GONE
            profileBtn.visibility = if (needsShader && shaderId != null) View.VISIBLE else View.GONE
            shaderBtn.text = getString(R.string.apps_pick_shader, shaderName.ifEmpty { "-" })
            profileBtn.text = getString(R.string.apps_pick_profile, profileName ?: getString(R.string.apps_profile_active))
            hint.setText(when (mode) {
                Mode.CAPTURE -> R.string.apps_hint_capture
                Mode.GLASS -> R.string.apps_hint_glass
                Mode.OFF -> R.string.apps_hint_off
                null -> R.string.apps_hint_none
            })
        }
        modeButtons.forEach { (b, m) -> b.setOnClickListener { mode = m; refresh() } }
        shaderBtn.setOnClickListener {
            ShaderPicker(this, applyToSession = false).show { item ->
                shaderId = item.id
                shaderName = item.name
                profileName = ShaderParamStore.getActiveProfileName(this, item.id) // los perfiles son de cada shader
                refresh()
            }
        }
        profileBtn.setOnClickListener {
            val id = shaderId ?: return@setOnClickListener
            val names = listOf<String?>(null) + ShaderParamStore.getProfileNames(this, id)
            AlertDialog.Builder(dialogCtx, R.style.CrtAlertDialog)
                .setTitle(R.string.apps_profile_title)
                .setItems(names.map { it ?: getString(R.string.apps_profile_active) }.toTypedArray()) { _, which ->
                    profileName = names[which]
                    refresh()
                }
                .create().apply { window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog) }
                .show()
        }

        val content = LinearLayout(dialogCtx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(dialogCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                modeButtons.forEachIndexed { i, (b, _) -> addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = gap / 2 }) }
            })
            addView(shaderBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(profileBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        refresh()

        val builder = AlertDialog.Builder(dialogCtx, R.style.CrtAlertDialog)
            .setTitle(app.label)
            .setView(CrtUi.dialogContent(content))
            .setPositiveButton(R.string.btn_save, null) // se valida al pulsar, sin cerrar el diálogo si falta algo
            .setNegativeButton(R.string.btn_cancel, null)
        if (existing != null) builder.setNeutralButton(R.string.btn_app_remove) { _, _ ->
            AppProfileStore.delete(this, app.packageName)
            adapter.notifyDataSetChanged(); sortApps()
        }
        val d = builder.create()
        d.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val m = mode
            val id = shaderId
            when {
                m == null -> { /* nada elegido: no se guarda nada */ d.dismiss() }
                m != Mode.OFF && id == null -> android.widget.Toast.makeText(this, R.string.toast_app_no_shader, android.widget.Toast.LENGTH_SHORT).show()
                else -> {
                    AppProfileStore.save(this, AppProfileStore.AppProfile(
                        packageName = app.packageName, mode = m, shaderId = id ?: existing?.shaderId ?: "", shaderName = shaderName,
                        profileName = profileName,
                        screens = existing?.screens ?: com.sanabria.shadervulkan.model.ScreenLayout.TOP,
                        zone2Portrait = existing?.zone2Portrait, zone2Landscape = existing?.zone2Landscape,
                        zoneBShaderId = existing?.zoneBShaderId, zoneBShaderName = existing?.zoneBShaderName, zoneBProfile = existing?.zoneBProfile
                    ))
                    sortApps()
                    d.dismiss()
                }
            }
        }
    }
}
