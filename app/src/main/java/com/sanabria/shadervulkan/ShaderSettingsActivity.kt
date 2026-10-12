package com.sanabria.shadervulkan

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sanabria.shadervulkan.adapters.ParameterAdapter
import com.sanabria.shadervulkan.model.ParamFavorites
import com.sanabria.shadervulkan.model.ShaderParameter
import com.sanabria.shadervulkan.model.ShaderParamStore
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.ui.CrtActivity
import com.sanabria.shadervulkan.ui.CrtUi
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class ShaderSettingsActivity : CrtActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var spinnerProfiles: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCrtTheme() // enableEdgeToEdge() ya crea la ventana: la paleta tiene que estar antes
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shader_settings)

        val gutter = resources.getDimensionPixelSize(R.dimen.crt_gutter)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_settings_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(
                left = systemBars.left + gutter,
                top = systemBars.top + gutter,
                right = systemBars.right + gutter,
                bottom = systemBars.bottom + gutter
            )
            insets
        }

        VulkanEngineBridge.syncEditOutput(this) // los parámetros son los de la zona en edición (T18)

        showToast(getString(R.string.toast_loading_settings))

        findViewById<TextView>(R.id.txt_settings_shader).apply {
            text = "MODULE: ${ShaderPreferences.getEditShaderName(this@ShaderSettingsActivity)?.uppercase() ?: "NONE"}" +
                (if (ShaderPreferences.editsZoneB(this@ShaderSettingsActivity)) getString(R.string.zone_tag_b) else "")
            com.sanabria.shadervulkan.ui.AutoScroll.attach(this)
        }

        recyclerView = findViewById(R.id.recycler_parameters)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        spinnerProfiles = findViewById(R.id.spinner_profiles)

        setupProfileSpinner()
        loadParameters()

        findViewById<Button>(R.id.btn_close_settings).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_reset_parameters).setOnClickListener {
            resetParameters()
        }

        findViewById<Button>(R.id.btn_save_profile).setOnClickListener {
            showSaveProfileDialog()
        }

        findViewById<Button>(R.id.btn_delete_profile).setOnClickListener {
            deleteCurrentProfile()
        }

        findViewById<Button>(R.id.btn_rename_profile).setOnClickListener {
            renameCurrentProfile()
        }

        findViewById<Button>(R.id.btn_export_preset).setOnClickListener {
            exportCurrentSettingsAsSlangp()
        }
    }

    private fun setupProfileSpinner() {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        val profileNames: MutableList<String> = ShaderParamStore.getProfileNames(this, shaderId).toMutableList()
        
        if (profileNames.isEmpty()) {
            profileNames.add("Default")
            // Aseguramos que exista al menos uno: una foto del estado actual (antes un perfil vacío con resolución y
            // aspecto a 0, que al aplicarlo cambiaba la resolución sin que el usuario la hubiera tocado)
            VulkanEngineBridge.saveCurrentAsProfile(this, shaderId, "Default")
        }

        val adapter = ArrayAdapter<String>(this, R.layout.crt_spinner_item, profileNames)
        adapter.setDropDownViewResource(R.layout.crt_spinner_dropdown_item)
        spinnerProfiles.adapter = adapter

        val activeProfile = ShaderParamStore.getActiveProfileName(this, shaderId) ?: "Default"
        val activePos = profileNames.indexOf(activeProfile)
        if (activePos >= 0) spinnerProfiles.setSelection(activePos)

        spinnerProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedProfile = profileNames.getOrNull(position) ?: return
                // La selección inicial o programática llega aquí con el perfil que ya es el activo: no se hace nada. (Antes
                // había una bandera que se quedaba armada si el spinner no emitía el evento y se tragaba la primera
                // elección real del usuario.)
                if (selectedProfile == (ShaderParamStore.getActiveProfileName(this@ShaderSettingsActivity, shaderId) ?: "Default")) return
                changeProfile(selectedProfile)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun changeProfile(profileName: String) {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        ShaderParamStore.setActiveProfile(this, shaderId, profileName)

        // Reemplazo completo del estado: valores del preset + los del perfil + resolución y aspecto
        if (!VulkanEngineBridge.applyProfile(this, shaderId, profileName)) {
            VulkanEngineBridge.resetShaderParameters() // perfil sin datos: valores del preset
        }

        showToast(getString(R.string.toast_profile_changed, profileName))

        // Recargar la UI para reflejar los nuevos valores actuales
        recyclerView.postDelayed({
            loadParameters()
        }, 300)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSaveProfileDialog() {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        
        val activeName = ShaderParamStore.getActiveProfileName(this, shaderId) ?: "Default"
        val input = EditText(this).apply {
            hint = getString(R.string.hint_profile_name)
            setSingleLine()
            // Con el nombre del perfil activo, guardar sobrescribe ese perfil; con otro nombre, crea uno nuevo
            setText(activeName)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_save_profile_title)
            .setMessage(R.string.dialog_save_profile_message)
            .setView(CrtUi.dialogContent(input))
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveCurrentSettingsToProfile(name)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
            .also { CrtUi.styleMessage(it) }
    }

    private fun saveCurrentSettingsToProfile(profileName: String) {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        if (VulkanEngineBridge.getShaderParameters() == null) return

        VulkanEngineBridge.saveCurrentAsProfile(this, shaderId, profileName) // foto completa; queda como perfil activo
        showToast(getString(R.string.toast_profile_saved, profileName))
        setupProfileSpinner() // Refrescar lista
    }

    private fun exportCurrentSettingsAsSlangp() {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        val currentParams = VulkanEngineBridge.getShaderParameters() ?: return
        
        val input = EditText(this).apply {
            hint = getString(R.string.hint_export_name)
            setSingleLine()
            setText("MyCustomPreset")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_export_title)
            .setMessage(R.string.dialog_export_message)
            .setView(CrtUi.dialogContent(input))
            .setPositiveButton(R.string.btn_export) { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isNotEmpty()) {
                    performExport(shaderId, fileName, currentParams)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
            .also { CrtUi.styleMessage(it) }
    }

    /**
     * Exporta un preset simple de RetroArch: `#reference` al preset original + los valores actuales de los parámetros.
     * El archivo se crea EN LA MISMA CARPETA que el original, así la referencia es solo su nombre (ruta relativa, válida en
     * RetroArch y en nuestro parser, T40). Una URI SAF no sirve como ruta de `#reference`.
     */
    private fun performExport(originalShaderPath: String, newName: String, params: Array<ShaderParameter>) {
        try {
            val treeUriString = ShaderPreferences.getShaderFolderUri(this)
            if (treeUriString == null || !originalShaderPath.startsWith("content://")) {
                // Los presets de assets no existen fuera de la app: no hay nada a lo que referenciar
                showToast(getString(R.string.toast_export_only_external))
                return
            }
            val treeUri = Uri.parse(treeUriString)

            // Carpeta del preset original = su documento padre dentro del árbol concedido
            val origDocId = DocumentsContract.getDocumentId(Uri.parse(originalShaderPath))
            val slash = origDocId.lastIndexOf('/')
            val colon = origDocId.indexOf(':')
            val origName = origDocId.substring(maxOf(slash, colon) + 1)
            val parentDocId = when {
                slash >= 0 -> origDocId.substring(0, slash)
                colon >= 0 -> origDocId.substring(0, colon + 1) // raíz del volumen ("primary:")
                else -> null
            }
            val parentFolder = parentDocId?.let {
                DocumentFile.fromTreeUri(this, DocumentsContract.buildDocumentUriUsingTree(treeUri, it))
            }
            if (parentFolder == null) {
                showToast(getString(R.string.toast_export_no_folder))
                return
            }

            val sb = StringBuilder()
            sb.append("#reference \"$origName\"\n\n")
            // Overrides de parámetros (los de solo información no se pueden cambiar)
            params.filterNot { it.isInfo }.forEach { param ->
                sb.append("${param.name} = \"${String.format(java.util.Locale.US, "%.6f", param.current)}\"\n")
            }

            // application/octet-stream: con text/plain el proveedor de almacenamiento añade ".txt" (foo.slangp.txt)
            val newFile = parentFolder.createFile("application/octet-stream", "$newName.slangp")
            if (newFile == null) {
                showToast(getString(R.string.toast_export_create_failed))
                return
            }
            contentResolver.openOutputStream(newFile.uri)?.use { it.write(sb.toString().toByteArray()) }
            showToast(getString(R.string.toast_export_done, newFile.name))
        } catch (e: Exception) {
            showToast(getString(R.string.toast_error, e.message))
            e.printStackTrace()
        }
    }

    /** Renombrar el perfil elegido (T60). Default no se renombra; el nombre nuevo no puede existir ya. */
    private fun renameCurrentProfile() {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        val oldName = spinnerProfiles.selectedItem as? String ?: return
        if (oldName == "Default") {
            showToast(getString(R.string.toast_default_unrenamable))
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.hint_profile_name)
            setSingleLine()
            setText(oldName)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_profile_title)
            .setView(CrtUi.dialogContent(input))
            .setPositiveButton(R.string.btn_rename) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() || newName == oldName -> {}
                    !ShaderParamStore.renameProfile(this, shaderId, oldName, newName) -> showToast(getString(R.string.toast_profile_exists, newName))
                    else -> {
                        com.sanabria.shadervulkan.model.AppProfileStore.renameProfileRefs(this, shaderId, oldName, newName)
                        showToast(getString(R.string.toast_profile_renamed, newName))
                        setupProfileSpinner()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
            .also { CrtUi.styleMessage(it) }
    }

    private fun deleteCurrentProfile() {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return
        val profileName = spinnerProfiles.selectedItem as? String ?: return
        
        if (profileName == "Default") {
            showToast(getString(R.string.toast_default_undeletable))
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_profile_title)
            .setMessage(getString(R.string.dialog_delete_profile_message, profileName))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                ShaderParamStore.deleteProfile(this, shaderId, profileName)
                showToast(getString(R.string.toast_profile_deleted))
                // El perfil borrado era el que estaba aplicado: pasar a Default (o al primero que quede) y APLICARLO. Antes
                // solo se refrescaba el spinner y el motor se quedaba con los valores del perfil eliminado.
                val remaining = ShaderParamStore.getProfileNames(this, shaderId)
                val next = if ("Default" in remaining) "Default" else remaining.firstOrNull()
                if (next != null) changeProfile(next) else VulkanEngineBridge.resetShaderParameters()
                setupProfileSpinner() // si no queda ninguno, crea Default con el estado actual
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
            .also { CrtUi.styleMessage(it) }
    }

    private fun loadParameters() {
        val params = VulkanEngineBridge.getShaderParameters()?.toList() ?: emptyList()
        
        if (params.isEmpty()) {
            // Si está vacío, reintentamos una vez tras 400ms por si el hilo de fondo sigue leyendo el archivo
            recyclerView.postDelayed({
                val retryParams = VulkanEngineBridge.getShaderParameters()?.toList() ?: emptyList()
                if (retryParams.isEmpty()) {
                    Toast.makeText(this, R.string.toast_no_params, Toast.LENGTH_LONG).show()
                } else {
                    updateAdapter(retryParams)
                }
            }, 400)
        } else {
            updateAdapter(params)
        }
    }

    private fun updateAdapter(params: List<ShaderParameter>) {
        val favId = ShaderPreferences.getEditShaderId(this)
        val favorites = favId?.let { ParamFavorites.get(this, it).toMutableSet() } ?: mutableSetOf()
        val toggleFavorite: ((String) -> Boolean)? = favId?.let { id -> { name -> ParamFavorites.toggle(this, id, name) } }
        recyclerView.adapter = ParameterAdapter(params, favorites, toggleFavorite) { name, value ->
            // Solo se aplica en el motor. Los perfiles se guardan a mano (botón de guardar): mover un slider ya no
            // modifica el perfil activo, así "guardar como nuevo" no deja el original cambiado.
            VulkanEngineBridge.updateShaderParameter(name, value)
        }
    }

    private fun resetParameters() {
        val shaderId = ShaderPreferences.getEditShaderId(this) ?: return

        ShaderParamStore.clearAllProfiles(this, shaderId)
        // Valores del preset al instante, sin recargar ni recompilar el shader
        VulkanEngineBridge.resetShaderParameters()
        setupProfileSpinner() // recrea Default con el estado ya restablecido

        Toast.makeText(this, R.string.toast_settings_reset, Toast.LENGTH_SHORT).show()
        recyclerView.postDelayed({ loadParameters() }, 300)
    }
}
