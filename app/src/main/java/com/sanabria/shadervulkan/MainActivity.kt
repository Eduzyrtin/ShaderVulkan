package com.sanabria.shadervulkan

import android.Manifest
import android.app.Activity
import android.util.Log
import android.app.ActivityManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sanabria.shadervulkan.adapters.ShaderItemAdapter
import com.sanabria.shadervulkan.model.ConsoleResolutions
import com.sanabria.shadervulkan.model.ShaderItem
import com.sanabria.shadervulkan.overlay.GuideDialog
import com.sanabria.shadervulkan.overlay.StackEditor
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.model.ShaderZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.sanabria.shadervulkan.services.ServiceState
import com.sanabria.shadervulkan.services.SessionMode
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.google.android.material.slider.Slider
import com.sanabria.shadervulkan.native.NativeText
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService
import com.sanabria.shadervulkan.ui.CrtActivity
import com.sanabria.shadervulkan.ui.CrtTheme
import com.sanabria.shadervulkan.ui.CrtUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Random

class MainActivity : CrtActivity() {

    private companion object {
        const val LOAD_DIALOG_POLL_MS = 100L
        const val LOAD_DIALOG_GRACE_MS = 2500L
        const val LOAD_DIALOG_TIMEOUT_MS = 120_000L // red de seguridad: nunca dejar el diálogo bloqueado para siempre
        const val STATE_CURRENT_FOLDER = "current_folder" // URI de la carpeta abierta; "" = inicio (assets)
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var txtActiveShader: TextView
    private var allShaders: MutableList<ShaderItem> = mutableListOf()
    private var lastResolutionPosition = 0
    private var isInternalResolutionSelection = false
    private var resolutionUserInitiated = false // true desde que el usuario toca/activa el selector hasta la siguiente selección
    private var expectedInternalPosition = -1

    private var rootFolder: DocumentFile? = null
    private var currentFolder: DocumentFile? = null

    private val folderCache = mutableMapOf<String, List<ShaderItem>>()

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ShaderPreferences.setShaderFolderUri(this, uri.toString())

            rootFolder = DocumentFile.fromTreeUri(this, uri)
            currentFolder = rootFolder
            clearCache()
            refreshShaderList(forceRescan = true)
            showToast(getString(R.string.toast_folder_updated))
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            showToast(getString(R.string.toast_capture_denied))
            // Se había pedido pasar de Glass a Captura y se canceló: la sesión Glass sigue, así que el selector vuelve a Glass
            if (ShaderCaptureService.isRunning && ShaderCaptureService.sessionMode == SessionMode.GLASS &&
                ShaderPreferences.getRenderMode(this) != VulkanEngineBridge.MODE_GLASS) {
                ShaderPreferences.setRenderMode(this, VulkanEngineBridge.MODE_GLASS)
                setupModeSpinner()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkOverlayAndStart()
        } else {
            showToast(getString(R.string.toast_notifications_needed))
        }
    }

    private var awaitingAccessibilityPermission = false

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sanabria.shadervulkan.ACTION_EXIT_APP") {
                Log.i("MainActivity", "Recibida señal de salida total")
                // Cerramos todas las actividades y quitamos la app de recientes.
                // NO matamos el proceso manualmente para evitar que Android lo considere un crash y lo reinicie.
                finishAffinity()
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Un cambio de paleta o de glow también recrea la pantalla (CrtActivity): ahí el servicio ya tiene el shader cargado y no es un
        // arranque ni un giro, así que ni se recarga el shader (en crt-royale son ~15 s de compilación) ni se muestra el aviso de arranque
        val fromThemeChange = CrtActivity.consumeThemeRecreation()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Inicializar el puente nativo con el contexto de la aplicación para evitar leaks
        // El AssetManager de la APLICACIÓN, no el de la actividad: el motor nativo guarda el puntero y el hilo de carga de shaders lo usa
        // mientras dura la carga; el de una actividad se libera al destruirla (girar, cambiar de paleta) y el hilo abortaba con
        // "pthread_mutex_lock called on a destroyed mutex" en AAssetManager_open.
        VulkanEngineBridge.initVulkan(applicationContext, applicationContext.assets)
        // Sin sesión en marcha, el motor se prepara en el modo elegido (así SETTINGS ya enseña los parámetros GLASS_*)
        if (!ShaderCaptureService.isRunning) VulkanEngineBridge.setRenderMode(ShaderPreferences.getRenderMode(this))

        findViewById<View>(R.id.header_bg)?.let { hb ->
            val root = hb.parent as? View
            if (root != null) {
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    // El fondo (tubo) sigue llegando a los bordes; el contenido queda fuera de las barras, también de la de gestos
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
            }
        }
        recyclerView = findViewById(R.id.recycler_shaders_list)
        txtActiveShader = findViewById(R.id.txt_active_shader)
        com.sanabria.shadervulkan.ui.AutoScroll.attach(txtActiveShader)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(true)

        loadCacheFromFile()

        // Cargar el shader guardado como activo si existe
        val activeShaderId = ShaderPreferences.getActiveShaderId(this)
        if (activeShaderId != null && !fromThemeChange) {
            val intent = Intent(this, ShaderCaptureService::class.java).apply {
                action = ShaderCaptureService.ACTION_LOAD_SHADER
                putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, activeShaderId)
            }
            // En un arranque en frío la app aún puede contar como background dentro de onCreate
            // (BackgroundServiceStartNotAllowedException). Reintentamos tras el primer frame.
            try {
                startService(intent)
            } catch (e: IllegalStateException) {
                Log.w("MainActivity", "startService rechazado en onCreate, reintentando tras el primer frame: ${e.message}")
                window.decorView.post {
                    try {
                        startService(intent)
                    } catch (e2: IllegalStateException) {
                        Log.e("MainActivity", "startService rechazado de nuevo: ${e2.message}")
                    }
                }
            }
        }

        val uriString = ShaderPreferences.getShaderFolderUri(this)
        if (uriString != null) {
            rootFolder = DocumentFile.fromTreeUri(this, uriString.toUri())
            currentFolder = rootFolder
            // Al rotar se recrea la actividad: volver a la carpeta en la que estaba el usuario, no a la raíz
            savedInstanceState?.getString(STATE_CURRENT_FOLDER)?.let { saved ->
                currentFolder = when {
                    saved.isEmpty() -> null
                    saved == rootFolder?.uri?.toString() -> rootFolder
                    else -> DocumentFile.fromTreeUri(this, saved.toUri()) ?: rootFolder
                }
            }
        }

        updateActiveShaderLabel()
        refreshShaderList()
        applyGrain()

        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!fromThemeChange) {
            showToast(getString(if (isLand) R.string.toast_boot_landscape else R.string.toast_boot_portrait))
        }

        // Bienvenida mínima (T63): solo la primera vez; reabrible desde Ajustes
        if (savedInstanceState == null && !ShaderPreferences.isWelcomeSeen(this)) {
            window.decorView.post { if (!isFinishing) GuideDialog(this).showWelcome() }
        }

        findViewById<View>(R.id.btn_combine)?.setOnClickListener {
            // Combinar presets (T45): el conjunto pasa a ser el shader activo y se carga si hay sesión
            StackEditor(this, overlay = false).show { updateActiveShaderLabel() }
        }

        findViewById<View>(R.id.btn_select_folder)?.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        findViewById<View>(R.id.btn_refresh_list)?.setOnClickListener {
            clearCache()
            refreshShaderList(forceRescan = true)
            showToast(getString(R.string.toast_list_refreshed))
        }

        findViewById<View>(R.id.btn_shader_settings)?.setOnClickListener {
            val intent = Intent(this, ShaderSettingsActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.btn_app_settings)?.setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        setupToggleButton()
        setupModeSpinner()
        setupSettingsToggle()
        setupResolutionSpinner()
        setupAspectRatioSpinner()

        registerReceiver(exitReceiver, IntentFilter("com.sanabria.shadervulkan.ACTION_EXIT_APP"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0)

        VulkanEngineBridge.applySavedSettings(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentFolder != null && currentFolder?.uri != rootFolder?.uri) {
                    currentFolder = currentFolder?.parentFile
                    refreshShaderList()
                } else if (currentFolder?.uri == rootFolder?.uri) {
                    currentFolder = null
                    refreshShaderList()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT_FOLDER, currentFolder?.uri?.toString() ?: "")
    }

    override fun onPause() {
        ShaderCaptureService.stateListener = null
        stopGrain() // el temblor del grano no tiene sentido (ni batería que gastar) con la pantalla fuera de primer plano
        super.onPause()
    }

    override fun onDestroy() {
        dismissShaderLoadingDialog()
        try {
            unregisterReceiver(exitReceiver)
        } catch (e: Exception) {
            // Ignorar si no estaba registrado
        }
        super.onDestroy()
    }

    private fun updateActiveShaderLabel() {
        val name = ShaderPreferences.getActiveShaderName(this)
        txtActiveShader.text = getString(R.string.active_module, name?.uppercase() ?: getString(R.string.none))
    }

    override fun onResume() {
        super.onResume()
        updateActiveShaderLabel()
        refreshScaleResolution()
        // El shader activo pudo cambiarse desde el menú flotante (T65): la marca de la lista lo sigue
        val changed = allShaders.indices.filter { !allShaders[it].isFolder && allShaders[it].isActive != isShaderActive(allShaders[it].id) }
        allShaders.forEach { if (!it.isFolder) it.isActive = isShaderActive(it.id) }
        if (::recyclerView.isInitialized) changed.forEach { recyclerView.adapter?.notifyItemChanged(it) }
        applyGrain() // Ajustes puede haberlo encendido o apagado
        setupModeSpinner() // el modo puede haber cambiado desde el servicio (nueva sesión)
        // La zona puede haberse cambiado desde el editor del menú flotante (pasa el selector a "Manual")
        setupAspectRatioSpinner()

        // El botón principal sigue al estado de la sesión, que también cambia desde la notificación y el menú flotante
        val btnToggle = findViewById<Button>(R.id.btn_toggle_shader)
        if (btnToggle != null) updateToggleButtonState(btnToggle)
        ShaderCaptureService.stateListener = {
            findViewById<Button>(R.id.btn_toggle_shader)?.let { updateToggleButtonState(it) }
        }

        if (awaitingAccessibilityPermission) {
            awaitingAccessibilityPermission = false
            if (ShaderOverlayAccessibilityService.isConnected()) {
                startSessionForMode()
            }
        }
    }

    private fun setupAdapter() {
        val activeId = ShaderPreferences.getActiveShaderId(this)
        allShaders.forEach { it.isActive = (it.id == activeId) }

        recyclerView.adapter = ShaderItemAdapter(allShaders) { selectedShader ->
            onShaderItemSelected(selectedShader)
        }
    }

    private fun onShaderItemSelected(selectedShader: ShaderItem) {
        if (selectedShader.isFolder) {
            if (selectedShader.id == ShaderItem.FOLDER_UP_ID) {
                if (currentFolder?.uri == rootFolder?.uri) {
                    currentFolder = null
                } else {
                    currentFolder = currentFolder?.parentFile
                }
            } else {
                val folderUri = selectedShader.path.toUri()
                currentFolder = DocumentFile.fromTreeUri(this, folderUri)
            }
            refreshShaderList()
            return
        }

        ShaderPreferences.setActiveShaderId(this, selectedShader.id, selectedShader.name)
        // Solo se repinta lo que cambia: la fila que deja de estar activa y la nueva
        val changed = allShaders.indices.filter { allShaders[it].isActive != (allShaders[it].id == selectedShader.id) }
        allShaders.forEach { it.isActive = (it.id == selectedShader.id) }
        updateActiveShaderLabel()
        changed.forEach { recyclerView.adapter?.notifyItemChanged(it) }

        showToast(getString(R.string.toast_selected, selectedShader.name))

        // Aplicar ajustes específicos guardados para este shader (Res, Aspect, Params)
        VulkanEngineBridge.applySavedShaderParameters(this, selectedShader.id)

        // Refrescar Spinners para mostrar la resolución y aspecto del nuevo shader
        setupResolutionSpinner()
        setupAspectRatioSpinner()

        // Siempre notificamos al servicio para cargar el shader en el motor nativo
        val intent = Intent(this, ShaderCaptureService::class.java).apply {
            action = ShaderCaptureService.ACTION_LOAD_SHADER
            putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, selectedShader.path)
        }
        startService(intent)

        // Diálogo modal de carga: bloquea la lista hasta que el shader esté listo
        showShaderLoadingDialog(selectedShader.name)

        // Refrescar Spinners para mostrar la resolución y aspecto del nuevo shader
        window.decorView.postDelayed({
            setupResolutionSpinner()
            setupAspectRatioSpinner()
        }, 150)
    }

    private var loadingDialog: AlertDialog? = null
    private val loadingHandler = Handler(Looper.getMainLooper())
    private var loadingPoll: Runnable? = null

    /**
     * Diálogo CRT "LOADING_SHADER xx%" que impide tocar la lista mientras el motor lee y compila el preset.
     * Se cierra solo al terminar. Si el sistema no está capturando, el motor no compila (no hay surface), así que
     * solo se espera a la lectura/análisis del preset; con el sistema en marcha se espera también a la compilación.
     */
    private fun showShaderLoadingDialog(shaderName: String) {
        dismissShaderLoadingDialog()

        val themedContext = ContextThemeWrapper(this, R.style.CrtAlertDialog)

        val txtPct = CrtUi.text(themedContext, CrtUi.Text.TITLE, "0%")
        val txtDetail = CrtUi.text(themedContext, CrtUi.Text.CAPTION, shaderName).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, CrtUi.dp(themedContext, 2f), 0, CrtUi.dp(themedContext, 12f))
        }
        val bar = CrtUi.progressBar(themedContext)
        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(txtPct)
            addView(txtDetail)
            addView(bar)
        }

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle(R.string.dialog_loading_title)
            .setView(CrtUi.dialogContent(layout).apply { setPadding(paddingLeft, paddingTop, paddingRight, CrtUi.dp(themedContext, 20f)) })
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        dialog.show()
        loadingDialog = dialog

        val startMs = android.os.SystemClock.uptimeMillis()
        var seenBusy = false
        val poll = object : Runnable {
            override fun run() {
                val elapsed = android.os.SystemClock.uptimeMillis() - startMs
                val pct = try { VulkanEngineBridge.getLoadProgress() } catch (_: Throwable) { -1 }
                val serviceBusy = ShaderCaptureService.isLoadingShader
                val busy = serviceBusy || (pct >= 0 && ShaderCaptureService.isCapturing)
                if (busy) seenBusy = true

                // Margen inicial: startService es asíncrono y el servicio aún puede no haber encolado la carga
                val finished = !busy && (seenBusy || elapsed > LOAD_DIALOG_GRACE_MS)
                if (finished || elapsed > LOAD_DIALOG_TIMEOUT_MS) {
                    dismissShaderLoadingDialog()
                    val problem = try { NativeText.format(this@MainActivity, VulkanEngineBridge.consumeLoadStatus()) } catch (_: Throwable) { "" }
                    if (problem.isNotBlank()) showToast(problem)
                    setupResolutionSpinner()
                    setupAspectRatioSpinner()
                    return
                }

                val shown = pct.coerceAtLeast(0)
                txtPct.text = getString(R.string.percent_value, shown)
                bar.progress = shown
                val label = try { NativeText.format(this@MainActivity, VulkanEngineBridge.getLoadLabel()) } catch (_: Throwable) { "" }
                txtDetail.text = if (label.isNotBlank()) label else shaderName
                loadingHandler.postDelayed(this, LOAD_DIALOG_POLL_MS)
            }
        }
        loadingPoll = poll
        loadingHandler.post(poll)
    }

    private fun dismissShaderLoadingDialog() {
        loadingPoll?.let { loadingHandler.removeCallbacks(it) }
        loadingPoll = null
        try { loadingDialog?.dismiss() } catch (_: Exception) {}
        loadingDialog = null
    }

    private fun setupToggleButton() {
        val btnToggle = findViewById<Button>(R.id.btn_toggle_shader) ?: return
        updateToggleButtonState(btnToggle)

        // CLOSE es el único cierre total; el botón principal nunca cierra la app (T64)
        findViewById<Button>(R.id.btn_close_system)?.setOnClickListener { stopCaptureService() }

        // RESTART: vuelve a empezar la sesión con el shader que haya elegido ahora, como un RUN SYSTEM nuevo (Glass arranca directo;
        // Captura vuelve a pedir compartir pantalla). El servicio suelta la sesión vieja al recibir la nueva.
        findViewById<Button>(R.id.btn_restart_system)?.setOnClickListener {
            if (ShaderCaptureService.state != ServiceState.CLOSING) startCaptureWorkflow()
        }

        btnToggle.setOnClickListener {
            when (ShaderCaptureService.state) {
                // Pausa: la sesión y la app siguen vivas (se puede cambiar de shader y reanudar sin permiso)
                ServiceState.ACTIVE -> startService(Intent(this, ShaderCaptureService::class.java).apply {
                    action = ShaderCaptureService.ACTION_STOP
                })
                // La captura sigue viva: reanudar no necesita el permiso
                ServiceState.PAUSED -> startService(Intent(this, ShaderCaptureService::class.java).apply {
                    action = ShaderCaptureService.ACTION_START
                })
                ServiceState.CLOSING -> {}
                // Sin sesión o interrumpida: hace falta el permiso de captura
                ServiceState.IDLE, ServiceState.INTERRUPTED -> startCaptureWorkflow()
            }
        }
    }

    /**
     * El botón sigue al estado de la sesión (ShaderCaptureService.state), no a una preferencia guardada: encendido (barra de
     * fósforo) para arrancar o reanudar, en rojo para parar.
     */
    private fun updateToggleButtonState(btn: Button) {
        val state = ShaderCaptureService.state
        btn.text = when (state) {
            ServiceState.ACTIVE -> getString(R.string.btn_pause_system)
            ServiceState.PAUSED -> getString(R.string.btn_resume_system)
            else -> getString(R.string.btn_run_system)
        }
        // Pausar es la acción normal; el rojo queda solo para CLOSE
        CrtUi.applyButton(btn, if (state == ServiceState.ACTIVE) CrtUi.Btn.NORMAL else CrtUi.Btn.PRIMARY)
        // CLOSE aparece mientras hay algo que cerrar (sesión activa, en pausa o interrumpida)
        val hasSession = state != ServiceState.IDLE && state != ServiceState.CLOSING
        findViewById<Button>(R.id.btn_close_system)?.visibility = if (hasSession) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_restart_system)?.visibility = if (hasSession) View.VISIBLE else View.GONE
    }

    private fun startCaptureWorkflow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkOverlayAndStart()
        }
    }

    private fun checkOverlayAndStart() {
        if (!ShaderOverlayAccessibilityService.isConnected()) {
            awaitingAccessibilityPermission = true
            // Antes de mandar al usuario a Ajustes del sistema se explica para qué se pide el servicio (T63)
            GuideDialog(this).confirmAccessibility {
                showToast(getString(R.string.toast_enable_accessibility))
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            startSessionForMode()
        }
    }

    private fun requestMediaProjection() {
        ShaderCaptureService.showShareHint(this) // solo se admite compartir una app
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = MediaProjectionConfig.createConfigForUserChoice()
            manager.createScreenCaptureIntent(config)
        } else {
            manager.createScreenCaptureIntent()
        }
        mediaProjectionLauncher.launch(intent)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ShaderCaptureService::class.java).apply {
            action = ShaderCaptureService.ACTION_START
            putExtra(ShaderCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ShaderCaptureService.EXTRA_DATA, data)
            putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, ShaderPreferences.getActiveShaderId(this@MainActivity))
        }
        startForegroundService(intent)
        // El botón se actualiza solo: el servicio avisa del cambio de estado (stateListener)
    }

    private fun stopCaptureService() {
        val intent = Intent(this, ShaderCaptureService::class.java).apply {
            action = ShaderCaptureService.ACTION_STOP_FULL
        }
        startService(intent)
        // También podemos usar stopService(intent), pero ACTION_STOP_FULL es más explícito
        // con la lógica interna del servicio.
    }

    private fun refreshShaderList(forceRescan: Boolean = false) {
        allShaders.clear()

        if (currentFolder == null) {
            loadShadersFromAssets()
            val uriString = ShaderPreferences.getShaderFolderUri(this)
            if (uriString != null) {
                allShaders.add(0, ShaderItem(
                    id = ShaderItem.EXTERNAL_ROOT_ID,
                    name = getString(R.string.list_external_folder),
                    extension = "",
                    path = uriString,
                    notes = getString(R.string.list_external_folder_note),
                    isFolder = true
                ))
            }
        } else {
            loadCurrentFolderContents(forceRescan)
        }

        setupAdapter()
        updateListPath()
    }

    /** Dónde está la lista, a la derecha de PRESETS: los presets internos o la carpeta externa abierta. */
    private fun updateListPath() {
        val tv = findViewById<TextView>(R.id.txt_list_path) ?: return
        val folder = currentFolder
        tv.text = when {
            folder == null -> "built-in"
            folder.uri == rootFolder?.uri -> "/${folder.name ?: ""}"
            else -> "/${rootFolder?.name ?: ""}/.../${folder.name ?: ""}" // "…" no está en la fuente VGA
        }
    }

    private fun loadShadersFromAssets() {
        val assetManager = assets
        try {
            val files = assetManager.list("shaders") ?: return
            for (file in files) {
                if (file.endsWith(".slangp", ignoreCase = true)) {
                    val name = displayNameWithoutExtension(file)
                    // id == path: el id activo se usa como ruta al cargar (onCreate, ACTION_START) y como clave de perfiles
                    // (B17). Antes el id era "asset_shaders/x" y la ruta "shaders/x", y no coincidían.
                    allShaders.add(ShaderItem(
                        id = "shaders/$file",
                        name = name,
                        extension = ".slangp",
                        path = "shaders/$file",
                        isActive = isShaderActive("shaders/$file")
                    ))
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadCurrentFolderContents(forceRescan: Boolean = false) {
        val dir = currentFolder ?: return
        val uriKey = dir.uri.toString()

        // La entrada ".." se monta siempre al momento: la caché (también la guardada en disco) conservaría su texto en el
        // idioma que tuviera la app cuando se escaneó la carpeta
        val upNote = getString(if (dir.uri != rootFolder?.uri) R.string.list_folder_up_note else R.string.list_folder_home_note)
        val upItem = ShaderItem(ShaderItem.FOLDER_UP_ID, "..", "", "", upNote, isFolder = true)

        if (!forceRescan && folderCache.containsKey(uriKey)) {
            allShaders.add(upItem)
            allShaders.addAll(folderCache[uriKey]!!.filter { it.id != ShaderItem.FOLDER_UP_ID })
            return
        }

        val list = mutableListOf<ShaderItem>()
        list.add(upItem)

        val files = dir.listFiles().sortedBy { it.name?.lowercase() }

        for (file in files) {
            if (file.isDirectory) {
                list.add(ShaderItem("folder_${file.uri}", file.name ?: "", "", file.uri.toString(), isFolder = true))
            }
        }

        for (file in files) {
            if (!file.isDirectory && file.name?.endsWith(".slangp", ignoreCase = true) == true) {
                val name = displayNameWithoutExtension(file.name!!)
                list.add(ShaderItem(file.uri.toString(), name, ".slangp", file.uri.toString(), "External Shader", isActive = isShaderActive(file.uri.toString())))
            }
        }

        folderCache[uriKey] = list
        allShaders.addAll(list)
        saveCacheToFile()
    }

    private fun clearCache() {
        folderCache.clear()
        val file = File(filesDir, "shader_cache.json")
        if (file.exists()) file.delete()
    }

    private fun saveCacheToFile() {
        try {
            val rootObj = JSONObject()
            for ((uri, items) in folderCache) {
                val array = JSONArray()
                for (item in items) {
                    val itemObj = JSONObject()
                    itemObj.put("id", item.id)
                    itemObj.put("name", item.name)
                    itemObj.put("ext", item.extension)
                    itemObj.put("path", item.path)
                    itemObj.put("notes", item.notes)
                    itemObj.put("isFolder", item.isFolder)
                    array.put(itemObj)
                }
                rootObj.put(uri, array)
            }
            File(filesDir, "shader_cache.json").writeText(rootObj.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadCacheFromFile() {
        try {
            val file = File(filesDir, "shader_cache.json")
            if (!file.exists()) return
            val json = file.readText()
            val rootObj = JSONObject(json)
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val uri = keys.next()
                val array = rootObj.getJSONArray(uri)
                val list = mutableListOf<ShaderItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(ShaderItem(
                        obj.getString("id"),
                        ShaderItem.cleanName(obj.getString("name")),
                        obj.getString("ext"),
                        obj.getString("path"),
                        obj.getString("notes"),
                        isShaderActive(obj.getString("id")),
                        obj.getBoolean("isFolder")
                    ))
                }
                folderCache[uri] = list
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun displayNameWithoutExtension(name: String): String {
        val lastDot = name.lastIndexOf('.')
        return if (lastDot > 0) name.substring(0, lastDot) else name
    }

    private fun isShaderActive(shaderId: String): Boolean {
        return ShaderPreferences.getActiveShaderId(this) == shaderId
    }

    // Posiciones del selector de modo de CONTENT RES (la primera de las dos listas). La segunda, la de consolas, sale de
    // ConsoleResolutions y solo se ve en el modo CONSOLE.
    private val RES_MODE_AUTO = 0
    private val RES_MODE_SCALE = 1
    private val RES_MODE_CUSTOM = 2
    private val RES_MODE_CONSOLE = 3

    /** En qué modo de CONTENT RES está un índice de resolución guardado (0 Auto, 7 Scale, 8 Custom, el resto consolas). */
    private fun resModeFor(resIdx: Int): Int = when {
        resIdx == ConsoleResolutions.INDEX_SCALE -> RES_MODE_SCALE
        resIdx == ConsoleResolutions.INDEX_CUSTOM -> RES_MODE_CUSTOM
        ConsoleResolutions.isConsole(resIdx) -> RES_MODE_CONSOLE
        else -> RES_MODE_AUTO
    }

    /** Guarda y aplica una resolución virtual. [mode] es la posición que le corresponde en el selector de modo. */
    private fun applyResolution(w: Float, h: Float, resIdx: Int, mode: Int) {
        lastResolutionPosition = mode
        VulkanEngineBridge.setVirtualNativeResolution(w, h)
        ShaderPreferences.setVirtualResolution(this, w, h)
        ShaderPreferences.setVirtualResolutionIndex(this, resIdx)
        syncZoneWithResolution(mode)
        VulkanEngineBridge.refreshZone() // con aspecto "Auto" la zona depende de la resolución
        updateGridInfo()
    }

    /**
     * La zona del shader arranca a pantalla completa ("sin zona"); al elegir una consola pasa a "Auto (por resolución)" (applyConsole).
     * Al cambiar de modo de CONTENT RES se queda como estaba: solo si estaba en
     * "Auto (por resolución)" y se pasa a Auto o Scale (la pantalla tal cual) vuelve a pantalla completa. Cualquier otro aspecto
     * (4:3, zona dibujada, detección de bandas, el de una consola) lo elige el usuario y no se toca.
     */
    private fun syncZoneWithResolution(mode: Int) {
        val current = ShaderPreferences.getAspectRatioIndex(this)
        if (current != ShaderZone.AUTO_INDEX || (mode != RES_MODE_AUTO && mode != RES_MODE_SCALE)) return
        ShaderPreferences.setAspectRatioIndex(this, 0)
        setupAspectRatioSpinner()
    }

    private fun applyConsole(entry: ConsoleResolutions.Entry) {
        // Al elegir un sistema, su zona del shader toma por defecto el aspecto original de esa consola ("Auto (por resolución)",
        // que lee ConsoleResolutions.Entry.aspect); el usuario lo cambia después en SHADER ZONE si quiere
        ShaderPreferences.setAspectRatioIndex(this, ShaderZone.AUTO_INDEX)
        setupAspectRatioSpinner()
        applyResolution(entry.w.toFloat(), entry.h.toFloat(), entry.index, RES_MODE_CONSOLE)
        ShaderPreferences.setLastConsole(this, entry.index)
        showToast(getString(R.string.toast_res_console, entry.label(this)))
    }

    private fun setupResolutionSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_res_profile) ?: return
        // Android restaura automáticamente la posición del Spinner tras una rotación
        // (vía onSaveInstanceState/onRestoreInstanceState de la Activity), DESPUÉS de que
        // este método ya haya terminado su propia restauración desde ShaderPreferences y
        // haya bajado isInternalResolutionSelection a false. Esa restauración automática
        // llega como un evento "real" (no interno) y hacía saltar el diálogo de Scale/Custom
        // solo por girar la pantalla. Como ya persistimos y restauramos el índice nosotros
        // mismos, desactivamos el guardado de instancia nativo del Spinner para eliminar esa
        // doble restauración de raíz.
        spinner.isSaveEnabled = false

        val currentScalePct = (ShaderPreferences.getScaleValue(this) * 100).toInt()

        // Primera lista: cómo se elige la resolución. Las consolas van en la segunda (setupConsoleSpinner)
        val modes = listOf(
            getString(R.string.res_auto),
            getString(R.string.res_scale, currentScalePct),
            getString(R.string.res_custom),
            getString(R.string.res_console)
        )

        val adapter = ArrayAdapter(this, R.layout.crt_spinner_item, modes)
        adapter.setDropDownViewResource(R.layout.crt_spinner_dropdown_item)

        // Bloquear disparos del listener durante la inicialización
        isInternalResolutionSelection = true
        spinner.adapter = adapter

        lastResolutionPosition = resModeFor(ShaderPreferences.getVirtualResolutionIndex(this))
        expectedInternalPosition = lastResolutionPosition
        spinner.setSelection(lastResolutionPosition)
        setupConsoleSpinner()

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Si es una selección interna (restauración por rotación), no disparamos lógica de usuario
                if (isInternalResolutionSelection) {
                    isInternalResolutionSelection = false
                    return
                }

                // Evitar disparar si la posición no ha cambiado realmente (prevención extra para rotaciones)
                if (position == lastResolutionPosition && position != RES_MODE_SCALE && position != RES_MODE_CUSTOM) return

                val byUser = resolutionUserInitiated
                resolutionUserInitiated = false
                // Scale/Custom abren un diálogo: solo si el usuario ha tocado el selector. Al rotar, Android re-emite
                // la selección guardada (Scale) y el diálogo salía solo.
                if ((position == RES_MODE_SCALE || position == RES_MODE_CUSTOM) && !byUser) return

                when (position) {
                    RES_MODE_CUSTOM -> showCustomResolutionDialog()
                    RES_MODE_SCALE -> showScalePercentageDialog()
                    RES_MODE_CONSOLE -> {
                        // Vuelve a la última consola usada (la primera vez, NES / SNES) y enseña la segunda lista
                        val entry = ConsoleResolutions.byIndex(ShaderPreferences.getLastConsole(this@MainActivity))
                            ?: ConsoleResolutions.byIndex(ConsoleResolutions.DEFAULT_INDEX)!!
                        applyConsole(entry)
                        syncConsoleRow()
                    }
                    else -> {
                        applyResolution(0f, 0f, ConsoleResolutions.INDEX_AUTO, RES_MODE_AUTO)
                        syncConsoleRow()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- EL TRUCO PARA RE-SELECCIÓN ---
        // Cuando el usuario toca el spinner para abrirlo, si ya está en Scale o Custom,
        // lo movemos internamente a otra posición (Auto) sin que se note.
        // Así, cuando el usuario vuelva a pulsar "Scale" en la lista, se detectará como un CAMBIO.
        // Mando (Ayn Thor): A/Enter/Centro del d-pad abren el desplegable sin pasar por el listener táctil
        spinner.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                 keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
                resolutionUserInitiated = true
            }
            false
        }
        spinner.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                resolutionUserInitiated = true
                val currentPos = spinner.selectedItemPosition
                if (currentPos == RES_MODE_SCALE || currentPos == RES_MODE_CUSTOM) {
                    isInternalResolutionSelection = true
                    expectedInternalPosition = RES_MODE_AUTO
                    spinner.setSelection(RES_MODE_AUTO) // Mover a Auto (temporalmente y en silencio)
                }
            }
            view.performClick()
            false
        }
    }

    /**
     * Segunda lista de CONTENT RES: las consolas, con su resolución (ConsoleResolutions). Solo se ve cuando el modo es CONSOLE.
     * Elegir una la aplica al momento y se recuerda para la próxima vez que se pase a CONSOLE.
     */
    private fun setupConsoleSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_console) ?: return
        spinner.isSaveEnabled = false
        // Cerrado enseña el nombre y la resolución (cabe en el selector); desplegado, también el fabricante
        val adapter = object : ArrayAdapter<ConsoleResolutions.Entry>(this, R.layout.crt_spinner_item, ConsoleResolutions.ALL) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.text = getItem(position)?.shortLabel(this@MainActivity)
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.text = getItem(position)?.label(this@MainActivity)
                return v
            }
        }
        adapter.setDropDownViewResource(R.layout.crt_spinner_dropdown_item)
        spinner.adapter = adapter
        syncConsoleRow()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val entry = ConsoleResolutions.ALL.getOrNull(position) ?: return
                val current = ShaderPreferences.getVirtualResolutionIndex(this@MainActivity)
                // La selección inicial llega con la consola ya guardada: no es un cambio. Y fuera del modo CONSOLE la lista está oculta:
                // lo que llegue entonces no lo ha elegido el usuario (B26: al recrear la pantalla en horizontal llegaba la selección
                // inicial, NES / SNES, y cambiaba CONTENT RES de Auto a 256x224 sin tocar nada)
                if (entry.index == current || !ConsoleResolutions.isConsole(current)) return
                applyConsole(entry)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Enseña u oculta la lista de consolas según el modo guardado y deja marcada la consola vigente (sin disparar el listener). */
    private fun syncConsoleRow() {
        val row = findViewById<View>(R.id.row_console) ?: return
        val spinner = findViewById<Spinner>(R.id.spinner_console) ?: return
        val idx = ShaderPreferences.getVirtualResolutionIndex(this)
        val isConsole = ConsoleResolutions.isConsole(idx)
        row.visibility = if (isConsole) View.VISIBLE else View.GONE
        val pos = ConsoleResolutions.positionOf(if (isConsole) idx else ShaderPreferences.getLastConsole(this))
        spinner.setSelection(if (pos >= 0) pos else ConsoleResolutions.positionOf(ConsoleResolutions.DEFAULT_INDEX))
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Modo de funcionamiento (T31): Captura (el oficial) o Glass (compatibilidad, capa sin captura)
    // ---------------------------------------------------------------------------------------------------------------------

    private fun setupModeSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_mode) ?: return
        spinner.isSaveEnabled = false
        val adapter = ArrayAdapter(this, R.layout.crt_spinner_item,
            listOf(getString(R.string.mode_capture), getString(R.string.mode_glass)))
        adapter.setDropDownViewResource(R.layout.crt_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(ShaderPreferences.getRenderMode(this))
        updateModeHint()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // La selección inicial llega con el modo ya guardado: no es un cambio
                if (position == ShaderPreferences.getRenderMode(this@MainActivity)) return
                onModeChosen(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateModeHint() {
        findViewById<View>(R.id.txt_mode_hint)?.visibility =
            if (ShaderPreferences.getRenderMode(this) == VulkanEngineBridge.MODE_GLASS) View.VISIBLE else View.GONE
    }

    /**
     * Cambio de modo desde el selector. Sin sesión solo se guarda (y se avisa al motor para que los parámetros GLASS_* ya salgan
     * en SETTINGS). Con sesión en marcha se cambia en caliente: a Glass no hace falta ningún permiso; a Captura sí (diálogo del
     * sistema), y si se cancela la preferencia vuelve a Glass (ver mediaProjectionLauncher).
     */
    private fun onModeChosen(mode: Int) {
        ShaderPreferences.setRenderMode(this, mode)
        updateModeHint()
        updateGridInfo()
        showToast(getString(if (mode == VulkanEngineBridge.MODE_GLASS) R.string.toast_mode_glass else R.string.toast_mode_capture))
        if (ShaderCaptureService.isRunning) {
            if (mode == VulkanEngineBridge.MODE_GLASS) startGlassService() else requestMediaProjection()
        } else {
            VulkanEngineBridge.setRenderMode(mode)
        }
    }

    /** Arranca (o convierte la sesión en) una sesión Glass: sin permiso de captura. */
    private fun startGlassService() {
        val intent = Intent(this, ShaderCaptureService::class.java).apply {
            action = ShaderCaptureService.ACTION_START_GLASS
            putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, ShaderPreferences.getActiveShaderId(this@MainActivity))
        }
        startForegroundService(intent)
    }

    /** RUN SYSTEM ya con notificaciones y accesibilidad resueltas: Glass arranca directo, Captura pide el permiso de pantalla. */
    private fun startSessionForMode() {
        if (ShaderPreferences.getRenderMode(this) == VulkanEngineBridge.MODE_GLASS) startGlassService() else requestMediaProjection()
    }

    private fun setupAspectRatioSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_aspect_ratio) ?: return
        // Mismo motivo que en setupResolutionSpinner(): evita que la restauración automática
        // de Android tras rotar dispare un onItemSelected "real" y muestre un toast fantasma.
        spinner.isSaveEnabled = false
        // Zona del shader (T05): el aspecto elegido define el rectángulo de pantalla sobre el que se aplica el shader
        // (fuera de él se ve la pantalla real). Las opciones y su significado viven en ShaderZone.
        val options = ShaderZone.ASPECT_OPTIONS.map { it.text(this) }

        val adapter = ArrayAdapter(this, R.layout.crt_spinner_item, options)
        adapter.setDropDownViewResource(R.layout.crt_spinner_dropdown_item)
        spinner.adapter = adapter

        val savedIdx = ShaderPreferences.getAspectRatioIndex(this)
        if (savedIdx < options.size) {
            spinner.setSelection(savedIdx)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // La selección inicial llega aquí con el valor ya guardado: no es un cambio (sin toast ni recalcular la zona)
                if (position == ShaderPreferences.getAspectRatioIndex(this@MainActivity)) return
                ShaderPreferences.setAspectRatioIndex(this@MainActivity, position)
                VulkanEngineBridge.refreshZone()
                updateGridInfo()
                showToast(getString(R.string.toast_zone, options[position]))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateGridInfo()
    }

    /**
     * Línea bajo SHADER ZONE con lo que está haciendo el shader: su rejilla, la zona, el tamaño del píxel y un aviso en
     * amarillo si el píxel sale deformado. Solo informa; los cambios de zona de fuera de la app (editor, detección) se ven
     * al volver a esta pantalla.
     */
    /**
     * Vertical: los selectores (CONTENT RES, ZONE, MODE) van plegados tras una línea con su resumen, para que el panel no se
     * coma media pantalla y la lista de presets se vea. Se despliegan tocando la línea; se recuerda cómo se dejó.
     */
    private fun setupSettingsToggle() {
        val header = findViewById<View>(R.id.row_settings_toggle) ?: return // en horizontal no existe: los selectores van siempre a la vista
        header.setOnClickListener {
            ShaderPreferences.setMainSettingsExpanded(this, !ShaderPreferences.isMainSettingsExpanded(this))
            applySettingsExpanded()
        }
        applySettingsExpanded()
    }

    private fun applySettingsExpanded() {
        val group = findViewById<View>(R.id.group_settings) ?: return
        val expanded = ShaderPreferences.isMainSettingsExpanded(this)
        group.visibility = if (expanded) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txt_settings_toggle)?.setText(if (expanded) R.string.settings_toggle_open else R.string.settings_toggle_closed)
        findViewById<View>(R.id.txt_settings_summary)?.visibility = if (expanded) View.INVISIBLE else View.VISIBLE
        updateSettingsSummary()
    }

    /** "Auto · Pantalla completa · Capture": lo que dicen los selectores plegados. En ámbar si el píxel sale deformado. */
    private fun updateSettingsSummary() {
        val tv = findViewById<TextView>(R.id.txt_settings_summary) ?: return
        val idx = ShaderPreferences.getVirtualResolutionIndex(this)
        val res = when {
            ConsoleResolutions.isConsole(idx) -> ConsoleResolutions.byIndex(idx)!!.name
            idx == ConsoleResolutions.INDEX_SCALE -> "Scale ${(ShaderPreferences.getScaleValue(this) * 100).toInt()}%"
            idx == ConsoleResolutions.INDEX_CUSTOM -> ShaderPreferences.getVirtualResolution(this).let { "${it.first.toInt()}x${it.second.toInt()}" }
            else -> "Auto"
        }
        val zone = if (ShaderPreferences.isDual(this)) "Dual" else
            ShaderZone.ASPECT_OPTIONS.getOrNull(ShaderPreferences.getAspectRatioIndex(this))?.text(this)?.substringBefore(" (") ?: ""
        val mode = getString(if (ShaderPreferences.getRenderMode(this) == VulkanEngineBridge.MODE_GLASS) R.string.mode_glass_short else R.string.mode_capture)
        val (sw, sh) = ShaderZone.screenSize(windowManager)
        val distorted = ShaderZone.gridInfo(this, sw, sh).distorted
        val summary = listOf(res, zone, mode).joinToString(" · ")
        tv.text = if (distorted) getString(R.string.summary_distorted, summary) else summary
        val (color, glow) = if (distorted) R.attr.crtWarn to R.attr.crtGlowWarn else R.attr.crtDim to R.attr.crtGlowDim
        tv.setTextColor(CrtTheme.color(this, color))
        tv.setShadowLayer(tv.shadowRadius, 0f, 0f, CrtTheme.color(this, glow))
    }

    private fun updateGridInfo() {
        updateSettingsSummary()
        val tv = findViewById<TextView>(R.id.txt_grid_info) ?: return
        val (sw, sh) = ShaderZone.screenSize(windowManager)
        val info = ShaderZone.gridInfo(this, sw, sh)
        // En Glass la resolución "Auto" no es la de la zona: es una línea virtual cada 4 px de pantalla
        tv.text = if (info.auto && ShaderPreferences.getRenderMode(this) == VulkanEngineBridge.MODE_GLASS) {
            getString(R.string.grid_info_glass_auto, (info.zoneW / 4).coerceAtLeast(1), (info.zoneH / 4).coerceAtLeast(1), info.zoneW, info.zoneH)
        } else ShaderZone.describeGrid(this, info)
        val (color, glow) = if (info.distorted) R.attr.crtWarn to R.attr.crtGlowWarn
                            else R.attr.crtDim to R.attr.crtGlowDim
        tv.setTextColor(CrtTheme.color(this, color))
        tv.setShadowLayer(tv.shadowRadius, 0f, 0f, CrtTheme.color(this, glow))
    }

    /**
     * Con SCALE la resolución es un porcentaje de la pantalla real. Se guardó en píxeles, y las guardadas antes de usar la pantalla
     * real (o en otra orientación o perfil) no cuadran con la zona: se recalcula a partir del porcentaje.
     */
    private fun refreshScaleResolution() {
        if (ShaderPreferences.getVirtualResolutionIndex(this) != ConsoleResolutions.INDEX_SCALE) return
        val (sw, sh) = ShaderZone.screenSize(windowManager)
        val scale = ShaderPreferences.getScaleValue(this)
        val (oldW, oldH) = ShaderPreferences.getVirtualResolution(this)
        // La rejilla se ordena por lado largo/corto, así que da igual en qué orientación se guardó
        val w = if (oldW >= oldH) max(sw, sh) * scale else min(sw, sh) * scale
        val h = if (oldW >= oldH) min(sw, sh) * scale else max(sw, sh) * scale
        if (abs(w - oldW) < 0.5f && abs(h - oldH) < 0.5f) return
        VulkanEngineBridge.setVirtualNativeResolution(w, h)
        ShaderPreferences.setVirtualResolution(this, w, h)
        VulkanEngineBridge.refreshZone()
        updateGridInfo()
    }

    private fun showScalePercentageDialog() {
        // Pantalla real, la misma que mide la zona del shader. resources.displayMetrics deja fuera las barras del sistema (2160 de
        // alto en vez de 2340 en el S24): la rejilla no era proporcional a la zona y salía "píxel deformado" al 25 %.
        val (screenW, screenH) = ShaderZone.screenSize(windowManager)

        // Tema CRT del diálogo puesto aquí, sin depender del alertDialogTheme heredado. El slider sale del sliderStyle del tema.
        val themedContext = ContextThemeWrapper(this, R.style.CrtAlertDialog)

        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        val initialScale = ShaderPreferences.getScaleValue(this)
        fun describe(value: Float) = "${(value * 100).toInt()}%  ·  ${(screenW * value).toInt()}x${(screenH * value).toInt()}"
        val txtValue = CrtUi.text(themedContext, CrtUi.Text.TITLE, describe(initialScale)).apply {
            setPadding(0, 0, 0, CrtUi.dp(themedContext, 4f))
        }

        val slider = Slider(themedContext).apply {
            valueFrom = 0.1f
            valueTo = 1.0f
            stepSize = 0.05f
            value = initialScale
        }

        slider.addOnChangeListener { _, value, _ -> txtValue.text = describe(value) }

        layout.addView(txtValue)
        layout.addView(slider)

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle(R.string.dialog_scale_title)
            .setView(CrtUi.dialogContent(layout))
            .setPositiveButton(R.string.btn_apply) { _, _ ->
                val scale = slider.value
                val targetW = (screenW * scale)
                val targetH = (screenH * scale)
                VulkanEngineBridge.setVirtualNativeResolution(targetW, targetH)
                ShaderPreferences.setVirtualResolution(this@MainActivity, targetW, targetH)
                ShaderPreferences.setScaleValue(this@MainActivity, scale)
                ShaderPreferences.setVirtualResolutionIndex(this@MainActivity, ConsoleResolutions.INDEX_SCALE)
                syncZoneWithResolution(RES_MODE_SCALE)
                VulkanEngineBridge.refreshZone()
                updateGridInfo()
                lastResolutionPosition = RES_MODE_SCALE
                showToast(getString(R.string.toast_scale, (scale * 100).toInt(), targetW.toInt(), targetH.toInt()))

                // Actualizar adaptador con el nuevo texto de porcentaje
                setupResolutionSpinner()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Si cancela, restauramos la posición anterior en el spinner sin disparar el listener
                isInternalResolutionSelection = true
                findViewById<Spinner>(R.id.spinner_res_profile)?.setSelection(lastResolutionPosition)
            }
            .create()
        // Fondo de TODA la ventana del diálogo (título y botones incluidos) con el tubo CRT, por si el tema no llega
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        dialog.show()
    }

    private fun showCustomResolutionDialog() {
        val themedContext = ContextThemeWrapper(this, R.style.CrtAlertDialog)

        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Los campos salen del editTextStyle del tema del diálogo (línea de fósforo, cursor de bloque)
        fun styledInput(hintText: String, initialValue: String) = EditText(themedContext).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(initialValue)
        }

        val editW = styledInput(getString(R.string.hint_width), ShaderPreferences.getVirtualResolution(this@MainActivity).first.toInt().toString())
        val editW_H = styledInput(getString(R.string.hint_height), ShaderPreferences.getVirtualResolution(this@MainActivity).second.toInt().toString())
        layout.addView(editW)
        layout.addView(CrtUi.text(themedContext, CrtUi.Text.TITLE, "x").apply {
            val m = CrtUi.dp(themedContext, 12f)
            setPadding(m, 0, m, 0)
        })
        layout.addView(editW_H)

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle(R.string.dialog_custom_title)
            .setView(CrtUi.dialogContent(layout))
            .setPositiveButton(R.string.btn_set) { _, _ ->
                val w = editW.text.toString().toFloatOrNull() ?: 0f
                val h = editW_H.text.toString().toFloatOrNull() ?: 0f
                if (w > 0 && h > 0) {
                    VulkanEngineBridge.setVirtualNativeResolution(w, h)
                    ShaderPreferences.setVirtualResolution(this@MainActivity, w, h)
                    ShaderPreferences.setVirtualResolutionIndex(this@MainActivity, ConsoleResolutions.INDEX_CUSTOM)
                    VulkanEngineBridge.refreshZone()
                    updateGridInfo()
                    lastResolutionPosition = RES_MODE_CUSTOM
                    showToast(getString(R.string.toast_custom, w.toInt(), h.toInt()))

                    setupResolutionSpinner()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                isInternalResolutionSelection = true
                findViewById<Spinner>(R.id.spinner_res_profile)?.setSelection(lastResolutionPosition)
            }
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_crt_dialog)
        dialog.show()
    }

    /**
     * Grano de fósforo por encima de toda la pantalla: puntos sueltos del color del fósforo, muy tenues. Las scanlines ya van en
     * el foreground del layout; aquí solo hay grano, para no oscurecer el texto (antes otra capa de scanlines lo apagaba).
     */
    private fun setupCrtOverlay() {
        val overlay = findViewById<View>(R.id.crt_overlay) ?: return

        val size = 64
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val phosphor = CrtTheme.color(this, R.attr.crtPhosphor)
        val random = Random()
        repeat(70) {
            paint.color = ColorUtils.setAlphaComponent(phosphor, 14 + random.nextInt(18))
            val x = random.nextInt(size).toFloat()
            val y = random.nextInt(size).toFloat()
            canvas.drawRect(x, y, x + 1 + random.nextInt(2), y + 1, paint)
        }

        val drawable = bitmap.toDrawable(resources)
        drawable.tileModeY = Shader.TileMode.REPEAT
        drawable.tileModeX = Shader.TileMode.REPEAT

        overlay.background = drawable
    }

    private var grainAnimators: List<ObjectAnimator> = emptyList()

    /**
     * Enciende o apaga el grano según Ajustes. Solo anima con la pantalla en primer plano (onResume/onPause): un animador infinito
     * sobre toda la pantalla gasta batería y antes seguía corriendo con la app en segundo plano.
     */
    private fun applyGrain() {
        val overlay = findViewById<View>(R.id.crt_overlay) ?: return
        if (!ShaderPreferences.isCrtGrain(this)) {
            stopGrain()
            overlay.visibility = View.GONE
            return
        }
        if (overlay.background == null) setupCrtOverlay()
        overlay.visibility = View.VISIBLE
        if (grainAnimators.isEmpty()) startFlickerAnimation(overlay)
    }

    private fun stopGrain() {
        grainAnimators.forEach { it.cancel() }
        grainAnimators = emptyList()
    }

    /** El grano tiembla como la señal de un CRT: brillo que oscila y un píxel de jitter horizontal. */
    private fun startFlickerAnimation(overlay: View) {
        val flicker = ObjectAnimator.ofFloat(overlay, "alpha", 0.5f, 1f)
        flicker.duration = 48
        flicker.repeatCount = ValueAnimator.INFINITE
        flicker.repeatMode = ValueAnimator.REVERSE

        val jitterX = ObjectAnimator.ofFloat(overlay, "translationX", -1f, 1f)
        jitterX.duration = 20
        jitterX.repeatCount = ValueAnimator.INFINITE
        jitterX.repeatMode = ValueAnimator.REVERSE

        grainAnimators = listOf(flicker, jitterX)
        flicker.start()
        jitterX.start()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}