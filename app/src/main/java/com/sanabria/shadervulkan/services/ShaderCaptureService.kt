package com.sanabria.shadervulkan.services

import com.sanabria.shadervulkan.AppLocale
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.net.toUri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.model.ShaderStack
import com.sanabria.shadervulkan.overlay.PerfStats
import com.sanabria.shadervulkan.native.VulkanEngineBridge

/**
 * Estado de la sesión de captura (T14): única fuente de verdad. Lo que ven la notificación, el botón flotante y la pantalla
 * principal sale de aquí; no hay otras banderas.
 *
 *  - IDLE:        sin sesión. El servicio puede existir (precarga del preset) pero no hay captura ni notificación.
 *  - ACTIVE:      el shader se está aplicando.
 *  - PAUSED:      pausa manual. La captura sigue viva, el overlay existe pero invisible; reanudar no pide permiso.
 *  - INTERRUPTED: el sistema cortó la proyección (p. ej. al bloquear). Hace falta un permiso nuevo para volver.
 *  - CLOSING:     CERRAR en curso. Terminal: nada puede volver a publicar la notificación ni resucitar la sesión.
 *
 * Transiciones: IDLE → ACTIVE (token nuevo) · ACTIVE ⇄ PAUSED · ACTIVE/PAUSED → INTERRUPTED (proyección cortada) ·
 * INTERRUPTED → ACTIVE (token nuevo) · cualquiera → CLOSING → IDLE (al destruirse el servicio).
 */
enum class ServiceState { IDLE, ACTIVE, PAUSED, INTERRUPTED, CLOSING }

/**
 * Cómo funciona la sesión (T31): CAPTURE lee la pantalla con MediaProjection y la procesa (el modo oficial); GLASS dibuja una
 * capa transparente encima de la pantalla sin capturar nada (PROYECTO.md §10). En GLASS no hay proyección, así que no existe
 * el estado INTERRUPTED.
 */
enum class SessionMode { CAPTURE, GLASS }

/**
 * ShaderCaptureService — Foreground Service que gestiona la captura de pantalla
 * y mantiene vivo el overlay de shaders.
 */
class ShaderCaptureService : Service(), SensorEventListener {

    // Idioma de la app (no el del sistema) para la notificación de la sesión
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    private val TAG = "ShaderCaptureService"

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null

    // R06: el motor lee la captura directamente del buffer del ImageReader (sin copiarla). La Image del último frame procesado se retiene
    // hasta que se procesa el siguiente (el motor puede volver a leerla en un redibujado) y solo entonces se devuelve. captureLock ordena
    // el procesado (hilo de captura) con el cierre del lector (hilo principal o de captura).
    private val captureLock = Any()
    private var heldImage: android.media.Image? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null

    private var lastGyro = FloatArray(3)
    private var lastAccel = FloatArray(3)

    private var shaderLoaderThread: HandlerThread? = null
    private var shaderLoaderHandler: Handler? = null

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    private var lastRotation = -1
    private var lastWidth = -1
    private var lastHeight = -1
    // Tamaño actual del VirtualDisplay (R12: puede ser menor que la pantalla, ver CaptureScale)
    private var captureW = 0
    private var captureH = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                checkDisplayRotationAndResize()
            }
        }
    }

    /**
     * Tamaño real de la pantalla en la orientación ACTUAL. Se lee de la Display (que se actualiza antes que nada) y no de
     * currentWindowMetrics: ese valor sale de la Configuration del servicio, que llega después del DisplayListener, así que
     * al rotar se redimensionaba el VirtualDisplay con la orientación anterior.
     */
    private fun realDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    /**
     * El DisplayListener (hilo del handler) y onConfigurationChanged (hilo principal) llegan por separado en cada giro:
     * se serializan en el hilo del handler para no recrear dos veces el VirtualDisplay desde hilos distintos.
     */
    private fun checkDisplayRotationAndResize() {
        val h = handler
        if (h == null || Looper.myLooper() == h.looper) checkDisplayRotationAndResizeNow() else h.post { checkDisplayRotationAndResizeNow() }
    }

    private fun checkDisplayRotationAndResizeNow() {
        val metrics = realDisplayMetrics()
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

        if (rotation != lastRotation || metrics.widthPixels != lastWidth || metrics.heightPixels != lastHeight) {
            Log.i(TAG, "checkDisplayRotationAndResize: rotación=$rotation (antes $lastRotation), pantalla=${metrics.widthPixels}x${metrics.heightPixels} (antes ${lastWidth}x${lastHeight})")
            lastRotation = rotation
            lastWidth = metrics.widthPixels
            lastHeight = metrics.heightPixels
            updateRotationData()
            recreateVirtualDisplay()
        }
    }

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            Log.i(TAG, "onConfigurationChanged en ShaderCaptureService: orientation=${newConfig.orientation}")
            checkDisplayRotationAndResize()
        }
        override fun onLowMemory() {}
    }

    companion object {
        /** Ruta del preset propio de la zona B que ya se pidió (T18); null = ninguno. Fuera del servicio para poder olvidarla al quitarlo. */
        @Volatile var requestedShaderPathB: String? = null

        /** Si hay modo dual con preset propio en la zona B y todavía no se ha pedido, lo pide. */
        fun requestZoneBIfNeeded(context: Context) {
            val b = ShaderPreferences.getZoneBShaderId(context) ?: return
            if (ShaderPreferences.isDual(context) && b != requestedShaderPathB) requestShaderB(context, b)
        }

        /** La zona B deja de tener preset propio en el motor (sigue al de la A) sin olvidar cuál era: al volver el modo dual se recarga. */
        fun suspendZoneB(context: Context) {
            ShaderPreferences.setEditZone(context, 0)
            requestedShaderPathB = null
            VulkanEngineBridge.clearOutputPreset(1)
            VulkanEngineBridge.syncEditOutput(context)
        }

        /** La zona B vuelve a seguir al preset de la A y se olvida el suyo. */
        fun clearZoneB(context: Context) {
            ShaderPreferences.setZoneBShader(context, null, null)
            suspendZoneB(context)
        }

        /** Estado de la sesión. Es estado en memoria: tras un cierre forzado del proceso vuelve a IDLE. Solo cambia el servicio. */
        @Volatile var state = ServiceState.IDLE
            private set

        /** Modo de la sesión en curso (solo significa algo mientras [isRunning]). Lo fija el servicio al arrancar la sesión. */
        @Volatile var sessionMode = SessionMode.CAPTURE
            private set

        /**
         * App cuyo perfil (T61) arrancó la sesión en curso; null si la sesión la puso el usuario a mano. Con ella, al salir de la app
         * se pregunta si pausar o cerrar. Se borra sola al acabar la sesión.
         */
        @Volatile var autoPackage: String? = null
            private set

        fun markAuto(packageName: String?) { autoPackage = packageName }

        /**
         * true mientras hay una SESIÓN de captura: activa, en pausa o interrumpida (se puede reanudar). NO es true solo porque
         * el servicio exista: MainActivity y SETTINGS lo arrancan para precargar el preset y leer sus parámetros, y eso no
         * debe sacar el botón flotante ni contar como captura (B07).
         */
        val isRunning: Boolean get() = state == ServiceState.ACTIVE || state == ServiceState.PAUSED || state == ServiceState.INTERRUPTED

        /** true solo mientras el shader se está aplicando (no en pausa ni interrumpida). */
        val isCapturing: Boolean get() = state == ServiceState.ACTIVE

        /** El botón flotante solo tiene sentido con shader: activo o en pausa. Interrumpida, el único mando es la notificación. */
        val hasControls: Boolean get() = state == ServiceState.ACTIVE || state == ServiceState.PAUSED

        /** Quien quiera enterarse de los cambios de estado (MainActivity, para el botón RUN/STOP). Se llama en el hilo principal. */
        @Volatile var stateListener: ((ServiceState) -> Unit)? = null

        /** Cargas de preset encoladas o en curso en el hilo de carga (lectura + análisis; la compilación va aparte, en nativo). */
        private val pendingLoads = java.util.concurrent.atomic.AtomicInteger(0)
        val isLoadingShader: Boolean get() = pendingLoads.get() > 0
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_SHADER_PATH = "extra_shader_path"
        const val ACTION_START = "action_start"
        /** Sesión Glass: capa sin captura, sin permiso de pantalla (T31). Lleva EXTRA_SHADER_PATH. */
        const val ACTION_START_GLASS = "action_start_glass"
        const val ACTION_STOP = "action_stop"
        const val ACTION_STOP_FULL = "action_stop_full"
        const val ACTION_LOAD_SHADER = "action_load_shader"
        /** Preset propio de la zona B (T18). Lleva EXTRA_SHADER_PATH. */
        const val ACTION_LOAD_SHADER_B = "action_load_shader_b"
        private const val GLASS_IDLE_MS = 300L
        /** Imágenes del ImageReader de la captura (R06: una la retiene el motor). */
        private const val MAX_IMAGES = 4
        /** Paquete de la app cuyo perfil (T61) pide esta sesión nueva (ACTION_START / ACTION_START_GLASS). */
        const val EXTRA_AUTO_PACKAGE = "extra_auto_package"

        /** Pide cargar otro preset con la sesión como esté (activa, en pausa, Captura o Glass): no la reinicia ni pide permiso. */
        /** Pide cargar el preset propio de la zona B (T18). */
        fun requestShaderB(context: Context, path: String) {
            context.startService(Intent(context, ShaderCaptureService::class.java).apply {
                action = ACTION_LOAD_SHADER_B
                putExtra(EXTRA_SHADER_PATH, path)
            })
        }

        fun requestShader(context: Context, path: String) {
            context.startService(Intent(context, ShaderCaptureService::class.java).apply {
                action = ACTION_LOAD_SHADER
                putExtra(EXTRA_SHADER_PATH, path)
            })
        }

        /**
         * Aviso antes del diálogo del sistema de compartir pantalla: el único modo admitido es compartir UNA app. Compartir la pantalla
         * entera captura el propio overlay (retroalimentación) y se ve en negro; Android no deja a las apps quitar esa opción.
         * Android 13 y anteriores solo ofrecen la pantalla entera: ahí la Captura no sirve y el aviso manda a Glass.
         */
        fun showShareHint(context: Context) {
            Toast.makeText(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) R.string.toast_share_one_app
                else R.string.toast_capture_legacy, Toast.LENGTH_LONG).show()
        }

        /** El servicio en marcha (para los avisos de R14 desde el servicio de accesibilidad). */
        @Volatile private var current: ShaderCaptureService? = null

        /** R14: el overlay se suspendió o se reanudó: quizá la captura deba pararse o volver. */
        fun onCaptureNeedChanged() { current?.updateCaptureSurface() }
    }

    /** Cambios de batería (T71): el modo batería decide si ahorra o vuelve a la normalidad. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = BatterySaver.evaluate(this@ShaderCaptureService, intent)
    }

    override fun onCreate() {
        super.onCreate()
        // La sesión NO empieza aquí: el servicio también se crea solo para precargar el preset (B07)
        state = ServiceState.IDLE
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notifier.createChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        }

        // Modo batería (T71): se evalúa con cada cambio de batería; solo actúa con sesión en marcha
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }

        registerComponentCallbacks(componentCallbacks)

        // R05: aquí corre el render de cada frame capturado (processFrame). Con prioridad de pantalla, como el RenderThread de Android:
        // junto a un emulador que ocupa la CPU, un frame que espera turno es un frame que llega tarde o se pierde
        handlerThread = HandlerThread("ShaderCaptureThread", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        handler = Handler(handlerThread!!.looper)

        displayManager.registerDisplayListener(displayListener, handler)

        // Inicializar sensores
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Los sensores solo se encienden si el preset activo los usa (como RetroArch); el motor avisa al adoptar un preset
        VulkanEngineBridge.sensorsNeededListener = { needed -> setSensorsEnabled(needed) }

        // R12: la zona o la rejilla cambiaron: quizá la captura pueda (o deba) tener otro tamaño
        CaptureScale.listener = { handler?.post { applyCaptureScale() } }

        // Inicializar motor nativo con el contexto para permitir carga externa
        VulkanEngineBridge.initVulkan(this, applicationContext.assets) // el de la aplicación vive lo que el proceso (ver MainActivity)
        // El motor se prepara en el modo elegido, para que la precarga del preset ya salga con los parámetros GLASS_* si toca
        VulkanEngineBridge.setRenderMode(ShaderPreferences.getRenderMode(this))
        VulkanEngineBridge.applySavedSettings(this)
        updateRotationData()

        shaderLoaderThread = HandlerThread("ShaderLoaderThread").apply { start() }
        shaderLoaderHandler = Handler(shaderLoaderThread!!.looper)
        current = this
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifier by lazy { CaptureNotification(this) }

    /** true mientras la notificación está publicada con startForeground (el sistema la trata como la de un servicio en primer plano). */
    private var inForeground = false

    /** Hay una interrupción sin avisar: el aviso emergente sale cuando el usuario mira el móvil, y solo una vez por interrupción. */
    private var alertPending = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand: action=$action estado=$state")
        // Cerrar es terminal: nada de lo que llegue mientras el servicio se apaga puede resucitar la sesión ni la notificación
        if (state == ServiceState.CLOSING) return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                // Con token es una sesión nueva; sin token, REANUDAR de una pausa (la captura sigue viva)
                if (data != null) {
                    autoPackage = intent.getStringExtra(EXTRA_AUTO_PACKAGE)
                    startSession(resultCode, data, intent.getStringExtra(EXTRA_SHADER_PATH))
                } else resumeSession()
            }
            ACTION_START_GLASS -> {
                autoPackage = intent.getStringExtra(EXTRA_AUTO_PACKAGE)
                startGlassSession(intent.getStringExtra(EXTRA_SHADER_PATH))
            }
            ACTION_LOAD_SHADER_B -> {
                val shaderPath = intent.getStringExtra(EXTRA_SHADER_PATH)
                if (shaderPath != null) loadShaderFromAssets(shaderPath, output = 1)
            }
            ACTION_LOAD_SHADER -> {
                val shaderPath = intent.getStringExtra(EXTRA_SHADER_PATH)
                if (shaderPath != null) loadShaderFromAssets(shaderPath)
            }
            ACTION_STOP -> pauseSession()
            ACTION_STOP_FULL -> closeSession()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Estados de la sesión (T14). Todo cambio pasa por setState(); las acciones de abajo solo deciden a qué estado ir.
    // ---------------------------------------------------------------------------------------------------------------------

    /**
     * ÚNICO punto por el que cambia el estado. Publica la notificación UNA vez por cambio, avisa a la interfaz y, salvo que se
     * pida lo contrario, ajusta overlay y botón flotante al estado nuevo. En CLOSING retira la notificación y desde ahí ninguna
     * otra ruta puede volver a publicarla.
     *
     * @param restartForeground vuelve a llamar a startForeground aunque ya lo fuera (sesión nueva con token nuevo, Android 14+).
     * @return false solo si el sistema no dejó pasar a primer plano (entonces no hay sesión que valga).
     */
    private fun setState(next: ServiceState, syncOverlay: Boolean = true, restartForeground: Boolean = false): Boolean {
        val previous = state
        if (previous == ServiceState.CLOSING && next != ServiceState.IDLE) return false
        state = next
        Log.i(TAG, "estado $previous -> $next")
        if (next == ServiceState.IDLE || next == ServiceState.CLOSING) {
            autoPackage = null
            stopGlassTicker()
        }

        // Se avisa (emergente) de una interrupción de una captura que estaba funcionando; si ya estaba en pausa no hay nada que avisar
        var alert = false
        if (next == ServiceState.INTERRUPTED) {
            alertPending = previous == ServiceState.ACTIVE
            if (alertPending && userIsLooking()) {
                alert = true
                alertPending = false
            }
        } else {
            alertPending = false
        }

        val published = when (next) {
            ServiceState.ACTIVE, ServiceState.PAUSED, ServiceState.INTERRUPTED -> publishNotification(alert, restartForeground)
            ServiceState.IDLE, ServiceState.CLOSING -> { removeNotification(); true }
        }
        if (syncOverlay) syncOverlay()
        updateCaptureSurface() // R14: en pausa el sistema deja de componer la captura
        ShaderOverlayAccessibilityService.refreshControlButton()
        stateListener?.invoke(next)
        ShaderTileService.refresh(this) // azulejo de ajustes rápidos (T70)
        if (next == ServiceState.IDLE || next == ServiceState.CLOSING) BatterySaver.reset() else BatterySaver.reevaluate(this)
        return published
    }

    /** true si el usuario está mirando el móvil ahora mismo: pantalla encendida y sin bloqueo de pantalla. */
    private fun userIsLooking(): Boolean {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun publishNotification(alert: Boolean, restartForeground: Boolean = false): Boolean {
        val notification = notifier.build(state, alert, sessionMode)
        if (restartForeground || !inForeground) {
            // En Android 14+ startForeground va ANTES de obtener la MediaProjection. En Glass no hay proyección: specialUse en
            // Android 14+ y sin tipo en 29–33 (specialUse no existe antes de la 14)
            try {
                if (sessionMode == SessionMode.GLASS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                        startForeground(CaptureNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    else
                        startForeground(CaptureNotification.ID, notification, 0)
                } else {
                    startForeground(CaptureNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                }
                inForeground = true
                Log.i(TAG, "startForeground: Success")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground: FAILED: ${e.message}")
                return false
            }
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CaptureNotification.ID, notification)
        }
        return true
    }

    private fun removeNotification() {
        if (inForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        // Si el servicio no era foreground (o la notificación quedó huérfana) igualmente se cancela por ID
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CaptureNotification.ID)
    }

    /** Overlay y botón flotante según el estado. */
    private fun syncOverlay() {
        when (state) {
            ServiceState.ACTIVE -> {
                // Fin de la pausa manual: el overlay sigue creado (T10), solo hay que volver a hacerlo visible
                ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_MANUAL)
                ShaderOverlayAccessibilityService.showOverlay()
                ShaderOverlayAccessibilityService.showControlButton()
            }
            ServiceState.PAUSED -> {
                // Pausa ligera (T10): alpha 0 sin destruir la superficie, así reanudar no recompila ni vuelve a pedir el permiso
                ShaderOverlayAccessibilityService.suspendOverlay(ShaderOverlayAccessibilityService.REASON_MANUAL)
                ShaderOverlayAccessibilityService.showControlButton()
            }
            // Sin captura (o a punto de cerrar) el botón flotante no controla nada: la notificación es el único mando
            ServiceState.INTERRUPTED, ServiceState.IDLE, ServiceState.CLOSING -> {
                ShaderOverlayAccessibilityService.hideOverlay()
                ShaderOverlayAccessibilityService.hideControlButton()
            }
        }
    }

    /** Sesión nueva con un token de captura nuevo (RUN SYSTEM, o REANUDAR tras una interrupción). */
    private fun startSession(resultCode: Int, data: Intent, shaderPath: String?) {
        val previous = state
        Log.i(TAG, "startSession: token de captura nuevo (estado previo $previous)")
        // Si ya hay una captura, se suelta para empezar de cero con el token nuevo
        stopCaptureComponents()
        // Se anula la referencia ANTES de parar la vieja: así su onStop (que llega después) ve que ya no es la actual
        val oldProjection = mediaProjection
        mediaProjection = null
        oldProjection?.stop()
        // Una sesión nueva no hereda una pausa manual de la anterior (si no, el overlay saldría invisible)
        ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_MANUAL)
        // Modo Captura (puede venir de una sesión Glass): motor, ventana (fondo negro) y tipo de servicio en primer plano
        useSessionMode(SessionMode.CAPTURE)

        // startForeground (dentro de setState) va ANTES de obtener la MediaProjection; el overlay se ajusta al final
        if (!setState(ServiceState.ACTIVE, syncOverlay = false, restartForeground = true)) {
            failStart(previous)
            return
        }
        shaderPath?.let { loadShaderFromAssets(it, force = false, reapply = autoPackage != null) }

        var projection: MediaProjection? = null
        try {
            projection = mediaProjectionManager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback(projection), handler)
            mediaProjection = projection
            // Proyección nueva: parte de cero, sin la suspensión de la anterior
            ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_CONTENT_HIDDEN)
            if (projection != null) {
                setupVirtualDisplay()
                if (virtualDisplay == null) throw IllegalStateException("createVirtualDisplay devolvió null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar la captura: ${e.message}", e)
            mediaProjection = projection
            projection = null
        }
        if (projection == null) {
            failStart(previous)
            return
        }
        syncOverlay()
    }

    // Animación de Glass (T34): con GLASS_ANIM_FPS > 0 se pide un redibujado cada 1000/fps ms; con 0, solo se vuelve a mirar cada
    // IDLE_MS (así un cambio del parámetro se nota enseguida). Tampoco se dibuja en pausa ni con el overlay suspendido. De paso
    // refresca el desenfoque (T35), que depende de otro parámetro (GLASS_BLUR) que se puede cambiar desde cualquier pantalla.
    // R22: GLASS_BLUR y GLASS_ANIM_FPS se leen sin esperar al mutex del motor, y el desenfoque solo se manda al hilo principal si cambió
    // (los demás cambios de modo, de ventana o del slider llaman a refreshBlur por su cuenta)
    private var tickerBlur = -1

    private val glassTicker = object : Runnable {
        override fun run() {
            if (sessionMode != SessionMode.GLASS) return // setState(IDLE/CLOSING) y useSessionMode(CAPTURE) lo paran
            val blur = VulkanEngineBridge.getParameterValue("GLASS_BLUR", 0f).toInt()
            if (blur != tickerBlur) {
                tickerBlur = blur
                ShaderOverlayAccessibilityService.refreshBlur()
            }
            val fps = if (state == ServiceState.ACTIVE && !ShaderOverlayAccessibilityService.isOverlaySuspended)
                VulkanEngineBridge.getGlassAnimFps() else 0f
            if (fps > 0f) {
                VulkanEngineBridge.requestRedraw()
                handler?.postDelayed(this, (1000f / fps).toLong().coerceAtLeast(16L))
            } else {
                handler?.postDelayed(this, GLASS_IDLE_MS)
            }
        }
    }

    private fun startGlassTicker() {
        handler?.removeCallbacks(glassTicker)
        tickerBlur = -1
        handler?.post(glassTicker)
    }

    private fun stopGlassTicker() {
        handler?.removeCallbacks(glassTicker)
    }

    /** Fija el modo de la sesión: lo que cambia en el servicio, en el motor y en la ventana del overlay. */
    private fun useSessionMode(mode: SessionMode) {
        sessionMode = mode
        val glass = mode == SessionMode.GLASS
        VulkanEngineBridge.setRenderMode(if (glass) VulkanEngineBridge.MODE_GLASS else VulkanEngineBridge.MODE_CAPTURE)
        ShaderPreferences.setRenderMode(this, if (glass) VulkanEngineBridge.MODE_GLASS else VulkanEngineBridge.MODE_CAPTURE)
        ShaderOverlayAccessibilityService.setGlassMode(glass)
        if (glass) startGlassTicker() else {
            stopGlassTicker()
            ShaderOverlayAccessibilityService.refreshBlur() // sin Glass no hay desenfoque
        }
    }

    /**
     * Sesión Glass (T31, PROYECTO.md §10): capa transparente con el shader encima de la pantalla, SIN captura ni permiso de
     * pantalla. Si había una sesión de captura se suelta su proyección. Pausa, reanudar y CERRAR son los de siempre; no existe
     * INTERRUPTED porque no hay proyección que perder.
     */
    private fun startGlassSession(shaderPath: String?) {
        val previous = state
        Log.i(TAG, "startGlassSession (estado previo $previous, modo previo $sessionMode)")
        stopCaptureComponents()
        val oldProjection = mediaProjection
        mediaProjection = null // antes de parar: su onStop se ve ya como de una proyección que no es la actual
        oldProjection?.stop()
        // Una sesión nueva no hereda pausas de la anterior
        ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_MANUAL)
        ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_CONTENT_HIDDEN)
        useSessionMode(SessionMode.GLASS)

        if (!setState(ServiceState.ACTIVE, syncOverlay = false, restartForeground = true)) {
            Log.e(TAG, "startGlassSession: el sistema no dejó pasar a primer plano")
            Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_LONG).show()
            setState(ServiceState.IDLE)
            return
        }
        shaderPath?.let { loadShaderFromAssets(it, force = false, reapply = autoPackage != null) }
        syncOverlay()
    }

    /** No se pudo iniciar la captura: no se deja una sesión "activa" que no captura nada. */
    private fun failStart(previous: ServiceState) {
        Log.e(TAG, "failStart: la captura no arrancó (estado previo $previous)")
        stopCaptureComponents()
        mediaProjection?.let {
            mediaProjection = null
            it.stop()
        }
        Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_LONG).show()
        // Si había sesión, la proyección vieja ya se soltó: queda "interrumpida" para poder reintentarlo. Si no, no hay nada.
        setState(if (previous == ServiceState.IDLE) ServiceState.IDLE else ServiceState.INTERRUPTED)
    }

    /** REANUDAR de una pausa. La captura sigue viva, así que no hace falta permiso nuevo. */
    private fun resumeSession() {
        when (state) {
            ServiceState.PAUSED -> setState(ServiceState.ACTIVE)
            ServiceState.ACTIVE -> Log.i(TAG, "resumeSession: ya estaba activa")
            ServiceState.INTERRUPTED -> Log.w(TAG, "resumeSession: la captura se cortó, hace falta un permiso nuevo (ProjectionRecoveryActivity)")
            else -> Log.w(TAG, "resumeSession: no hay sesión que reanudar (estado=$state)")
        }
    }

    /** Pausa manual (botón del menú flotante y DETENER de la notificación). Pausa ligera, T10: ver syncOverlay(). */
    private fun pauseSession() {
        if (state != ServiceState.ACTIVE) {
            Log.i(TAG, "pauseSession: ignorada (estado=$state)")
            return
        }
        setState(ServiceState.PAUSED)
    }

    /** CERRAR: cierre total, terminal. Sin sesión (solo precarga) también cierra el servicio y la app. */
    private fun closeSession() {
        Log.i(TAG, "closeSession: cierre total")
        // CLOSING oculta los overlays y retira la notificación; después de esto nadie puede volver a publicarla
        setState(ServiceState.CLOSING)
        stopCaptureComponents()
        mediaProjection?.let {
            mediaProjection = null // antes de parar: su onStop se ve ya como de una proyección que no es la actual
            it.stop()
        }
        // Notificar a la actividad que debe cerrarse
        sendBroadcast(Intent("com.sanabria.shadervulkan.ACTION_EXIT_APP").apply {
            setPackage(packageName)
        })
        stopSelf()
    }

    /**
     * El sistema terminó la proyección (bloqueo de pantalla en Android 15+, chip de la barra de estado...). No se puede
     * reanudar con el mismo token: se libera la captura y la sesión queda INTERRUPTED hasta que el usuario dé el permiso otra
     * vez (la notificación lleva a ProjectionRecoveryActivity).
     */
    private fun onProjectionLost() {
        if (state != ServiceState.ACTIVE && state != ServiceState.PAUSED) {
            Log.i(TAG, "onProjectionLost: sin sesión que interrumpir (estado=$state)")
            return
        }
        Log.w(TAG, "onProjectionLost: la proyección terminó por parte del sistema; hace falta un permiso nuevo")
        stopCaptureComponents()
        setState(ServiceState.INTERRUPTED)
    }

    private fun projectionCallback(projection: MediaProjection) = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection.Callback: onStop")
            // El estado solo se toca en el hilo principal: así un CERRAR y este aviso no se pisan
            mainHandler.post {
                if (mediaProjection !== projection) {
                    Log.i(TAG, "onStop de una proyección ya sustituida o cerrada: se ignora")
                    return@post
                }
                mediaProjection = null
                onProjectionLost()
            }
        }

        // Android 14+: al compartir una sola app, si esa app se cierra o pasa a segundo plano la captura
        // sigue viva pero sale en negro. Se suspende el overlay (se ve la pantalla real) y se retoma
        // cuando la app vuelve a ser visible.
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (mediaProjection !== projection) return
            Log.i(TAG, "MediaProjection.Callback: onCapturedContentVisibilityChanged($isVisible)")
            if (isVisible) ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_CONTENT_HIDDEN)
            else ShaderOverlayAccessibilityService.suspendOverlay(ShaderOverlayAccessibilityService.REASON_CONTENT_HIDDEN)
        }
    }

    // El aviso emergente de una interrupción sale cuando el usuario mira el móvil: al desbloquear, y solo una vez por interrupción
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (state == ServiceState.INTERRUPTED && alertPending) {
                alertPending = false
                publishNotification(alert = true)
            }
        }
    }

    private fun stopCaptureComponents() {
        Log.i(TAG, "stopCaptureComponents: Liberando VirtualDisplay e ImageReader")
        virtualDisplay?.release()
        virtualDisplay = null
        val reader = imageReader
        synchronized(captureLock) {
            imageReader = null // un frame que esté llegando ya no se procesa (ver installFrameListener)
            releaseHeldImage()
        }
        reader?.close()
    }

    /** R06: el motor deja de leer la captura (espera a la GPU) y se devuelve la Image retenida. Con captureLock. */
    private fun releaseHeldImage() {
        try { VulkanEngineBridge.releaseCaptureInput() } catch (_: Throwable) {}
        heldImage?.close()
        heldImage = null
    }

    // Ruta del último preset pedido (cargado o en carga). Cada carga fuerza recompilar todos los pases
    // (~15 s en crt-royale), así que el arranque (ACTION_START) no recarga lo que la Activity ya pidió.
    @Volatile private var requestedShaderPath: String? = null
    // Lo mismo para la zona B con preset propio (T18)
    /** Si hay modo dual con un preset propio para la zona B y todavía no está cargado, lo carga. */
    private fun loadZoneBIfNeeded() {
        val b = ShaderPreferences.getZoneBShaderId(this) ?: return
        if (!ShaderPreferences.isDual(this) || b == requestedShaderPathB) return
        loadShaderFromAssets(b, force = false, output = 1)
    }

    private fun requestedFor(output: Int) = if (output == 1) requestedShaderPathB else requestedShaderPath
    private fun setRequestedFor(output: Int, path: String?) { if (output == 1) requestedShaderPathB = path else requestedShaderPath = path }

    /** Lee un .slangp (asset o URI externa) y calcula la carpeta de la que cuelgan sus shaders. Null si no se puede leer. */
    private fun readPresetPart(assetPath: String): Pair<String, String>? {
        val isExternal = assetPath.startsWith("content://") || assetPath.startsWith("file://")
        val content = (if (isExternal) {
            Log.d("DEBUG-LOG-JAVA", "Detectado URI externo. Abriendo stream...")
            contentResolver.openInputStream(assetPath.toUri())?.bufferedReader()?.use { it.readText() }
        } else {
            assets.open(assetPath).bufferedReader().use { it.readText() }
        }) ?: return null
        Log.i("DEBUG-LOG-JAVA", "Contenido del preset cargado (${content.length} caracteres)")

        // Calcular ruta base robusta
        val basePath = if (isExternal) {
            // Para SAF, buscamos el último separador codificado %2F o el / del final
            val lastEncodedSlash = assetPath.lastIndexOf("%2F")
            val lastSlash = assetPath.lastIndexOf('/')
            val pivot = if (lastEncodedSlash > lastSlash) lastEncodedSlash + 3 else lastSlash + 1
            assetPath.substring(0, pivot)
        } else {
            val lastSlash = assetPath.lastIndexOf('/')
            if (lastSlash != -1) assetPath.substring(0, lastSlash + 1) else ""
        }
        Log.d("DEBUG-LOG-JAVA", "Base para archivos hijos (REPARADA): '$basePath'")
        if (isExternal) Log.w("DEBUG-LOG-JAVA", "AVISO: Los archivos hijos en $basePath NO serán accesibles por el AAssetManager nativo.")
        return content to basePath
    }

    /** Carga un preset en la salida 0 (la A, o las dos si la B no tiene el suyo) o, con [output] = 1, el preset propio de la zona B. */
    private fun loadShaderFromAssets(assetPath: String, force: Boolean = true, reapply: Boolean = false, output: Int = 0) {
        if (!force && assetPath == requestedFor(output)) {
            Log.i("DEBUG-LOG-JAVA", "Preset ya solicitado/cargado, se omite la recarga: $assetPath")
            // Perfil por app (T61): el preset ya está cargado, pero puede que con los ajustes de otro perfil
            if (reapply && !isLoadingShader) VulkanEngineBridge.applySavedShaderParametersFor(this, assetPath, output)
            return
        }
        setRequestedFor(output, assetPath)
        pendingLoads.incrementAndGet()
        ShaderOverlayAccessibilityService.onLoadRequested() // R08: el panel de carga lo enseña enseguida
        val queued = shaderLoaderHandler?.post {
            Log.i("DEBUG-LOG-JAVA", "Solicitando carga de preset (Background): $assetPath")
            try {
                // Un conjunto de presets (T45) trae varias piezas; un shader normal, una sola
                val parts = ShaderStack.parts(assetPath)
                val contents = ArrayList<String>()
                val bases = ArrayList<String>()
                for (part in parts) {
                    val read = readPresetPart(part)
                    if (read == null) { contents.clear(); break }
                    contents.add(read.first)
                    bases.add(read.second)
                }

                if (contents.size == parts.size && contents.isNotEmpty()) {
                    val loaded = when {
                        contents.size > 1 -> VulkanEngineBridge.loadPresetStack(contents.toTypedArray(), bases.toTypedArray(), output)
                        output == 1 -> VulkanEngineBridge.loadPresetForOutput(contents[0], bases[0], 1)
                        else -> VulkanEngineBridge.loadPreset(contents[0], bases[0])
                    }
                    if (loaded) {
                        // Aplicar parámetros guardados inmediatamente después de cargar el preset
                        VulkanEngineBridge.applySavedShaderParametersFor(this@ShaderCaptureService, assetPath, output)
                        // El preset de la A cambió: la zona B con preset propio se vuelve a cargar si aún no está (arranque, proceso nuevo)
                        if (output == 0) loadZoneBIfNeeded()
                    } else {
                        setRequestedFor(output, null) // permitir reintento
                    }
                } else {
                    setRequestedFor(output, null)
                    Log.e("DEBUG-LOG-JAVA", "Error: El contenido del preset es NULO")
                }
            } catch (e: Exception) {
                setRequestedFor(output, null)
                Log.e("DEBUG-LOG-JAVA", "EXCEPCIÓN cargando shader: ${e.message}")
                e.printStackTrace()
            } finally {
                pendingLoads.decrementAndGet()
            }
        }
        if (queued != true) pendingLoads.decrementAndGet() // sin hilo de carga: nada que esperar
    }

    // R06: 4 imágenes (antes 3): una queda retenida por el motor mientras se procesa la siguiente y acquireLatestImage necesita
    // tener dos a la vez para saltarse las atrasadas
    private fun newImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)

    private var totalFramesArrived = 0

    /** Escucha de frames común al lector inicial y a los que se crean al rotar. Se ejecuta en el hilo del handler. */
    private fun installFrameListener(reader: ImageReader) {
        reader.setOnImageAvailableListener({ r ->
            if (state != ServiceState.ACTIVE || ShaderOverlayAccessibilityService.isOverlaySuspended) {
                try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }
            if (PerfStats.counting) PerfStats.arrived.incrementAndGet() // entrada del contador de FPS (T66)
            totalFramesArrived++
            try {
                val image = r.acquireLatestImage()
                if (image == null) {
                    if (totalFramesArrived % 60 == 1) Log.w("SVDIAG_CAPTURE", "acquireLatestImage devolvió nulo")
                    return@setOnImageAvailableListener
                }
                var held = false
                try {
                    awaitImageReady(image)
                    val buffer = image.hardwareBuffer
                    if (buffer != null) {
                        if (totalFramesArrived % 60 == 1) {
                            Log.i("SVDIAG_CAPTURE", "Frame #$totalFramesArrived recibido: ${buffer.width}x${buffer.height}")
                        }
                        synchronized(captureLock) {
                            // Un lector ya sustituido o cerrado (giro, fin de la captura) no alimenta al motor: su buffer se va a devolver
                            if (r === imageReader) {
                                // R06: true = el motor lee ahora este buffer y ha terminado con el anterior: se retiene este y se
                                // devuelve el anterior. false = no lo ha tocado (tope de FPS): se devuelve ya y se sigue con el anterior.
                                held = VulkanEngineBridge.processFrame(buffer)
                                if (held) {
                                    heldImage?.close()
                                    heldImage = image
                                }
                            }
                        }
                        buffer.close() // solo el objeto Java: el buffer sigue vivo mientras la Image esté abierta
                    } else if (totalFramesArrived % 60 == 1) {
                        Log.w("SVDIAG_CAPTURE", "Frame sin HardwareBuffer")
                    }
                } finally {
                    if (!held) image.close()
                }
            } catch (e: Exception) {
                // Ignorar imágenes cerradas durante transiciones de rotación
                Log.e("SVDIAG_CAPTURE", "Excepción procesando imagen: ${e.message}")
            }
        }, handler)
    }

    private fun setupVirtualDisplay() {
        if (mediaProjection == null) return

        val metrics = realDisplayMetrics()
        // El VirtualDisplay nace con la orientación actual: es la referencia para detectar el siguiente giro
        lastRotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        lastWidth = metrics.widthPixels
        lastHeight = metrics.heightPixels

        // R12: con una rejilla pequeña basta una captura menor que la pantalla (misma forma: el sistema la escala sin bandas)
        val (w, h) = CaptureScale.captureSize(this, metrics.widthPixels, metrics.heightPixels)
        val reader = newImageReader(w, h)
        imageReader = reader

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ShaderCapture",
            w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface, null, handler
        )
        captureW = w
        captureH = h

        Log.i("SVDIAG_CAPTURE", "VirtualDisplay creado con éxito: $virtualDisplay (${w}x$h, pantalla ${metrics.widthPixels}x${metrics.heightPixels})")
        installFrameListener(reader)
        updateCaptureSurface() // R14: por si nace con el overlay suspendido
    }

    /**
     * R14: el VirtualDisplay solo tiene superficie mientras el shader se ve. En pausa, con la barra de estado bajada, con el editor de
     * zona o con un perfil "sin shader" delante, sin superficie el sistema deja de componer la captura (un pase de GPU por frame que
     * también paga el emulador) y el hilo de captura deja de despertarse para tirar frames; la documentación lo equipara a apagar la
     * pantalla del display virtual. Con la app compartida oculta (REASON_CONTENT_HIDDEN) no se quita: el aviso de que vuelve a verse
     * llega por la propia captura. Al volver, el motor enseña el último frame procesado hasta que llega el siguiente. En el hilo del
     * handler.
     */
    private fun updateCaptureSurface() {
        val h = handler ?: return
        if (Looper.myLooper() != h.looper) { h.post { updateCaptureSurface() }; return }
        val vd = virtualDisplay ?: return
        val reader = imageReader ?: return
        val target = if (captureWanted()) reader.surface else null
        try {
            if (vd.surface === target) return
            vd.surface = target
            Log.i("SVDIAG_CAPTURE", "Captura ${if (target == null) "parada (R14: el shader no se ve)" else "reanudada"}")
        } catch (e: Exception) {
            Log.w("SVDIAG_CAPTURE", "updateCaptureSurface: ${e.message}") // la captura se estaba cerrando en el hilo principal
        }
    }

    private fun captureWanted() = state == ServiceState.ACTIVE && !ShaderOverlayAccessibilityService.isCaptureIdle

    /**
     * R12: cambió el tamaño de captura que necesitan las zonas (zona, rejilla, ajuste de captura reducida). Se recrea el VirtualDisplay
     * como al girar si hace falta más resolución, o si se ahorra al menos un 10 % (para no recrearlo por cambios mínimos). En el hilo
     * del handler.
     */
    private fun applyCaptureScale() {
        if (mediaProjection == null || virtualDisplay == null) return
        val metrics = realDisplayMetrics()
        val (w, h) = CaptureScale.captureSize(this, metrics.widthPixels, metrics.heightPixels)
        val grow = w > captureW || h > captureH
        val shrink = w < captureW * 9 / 10
        if (!grow && !shrink) return
        Log.i("SVDIAG_CAPTURE", "Captura reducida (R12): ${captureW}x$captureH -> ${w}x$h")
        recreateVirtualDisplay()
    }

    /**
     * El buffer del ImageReader puede entregarse mientras el compositor aún lo está escribiendo (la fence de adquisición no ha
     * señalizado). Leerlo antes da frames negros o a medias; con la pantalla quieta ese frame queda fijo en el overlay.
     */
    private fun awaitImageReady(image: android.media.Image) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            image.fence.use { fence ->
                if (fence.isValid && !fence.await(java.time.Duration.ofMillis(100))) {
                    Log.w("SVDIAG_CAPTURE", "Fence de captura sin señalizar tras 100 ms")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun recreateVirtualDisplay() {
        if (mediaProjection == null || virtualDisplay == null) return

        val metrics = realDisplayMetrics()
        val oldReader = imageReader
        val (w, h) = CaptureScale.captureSize(this, metrics.widthPixels, metrics.heightPixels) // R12

        // Orden: lector nuevo con su escucha -> el VirtualDisplay pasa a la superficie nueva -> solo entonces se cierra el
        // viejo. Cerrarlo antes dejaba al VirtualDisplay produciendo contra una superficie abandonada durante el giro.
        oldReader?.setOnImageAvailableListener(null, null)
        val newReader = newImageReader(w, h)
        imageReader = newReader
        installFrameListener(newReader)

        virtualDisplay?.resize(w, h, metrics.densityDpi)
        virtualDisplay?.surface = if (captureWanted()) newReader.surface else null // R14: sin superficie mientras el shader no se ve
        captureW = w
        captureH = h
        // R06: la Image retenida es del lector viejo: el motor deja de leerla antes de cerrarlo
        synchronized(captureLock) { releaseHeldImage() }
        oldReader?.close()

        Log.i("SVDIAG_CAPTURE", "VirtualDisplay.resize realizado a ${w}x$h (pantalla ${metrics.widthPixels}x${metrics.heightPixels}), dpi=${metrics.densityDpi}")

        // Notificar al servicio de accesibilidad que actualice las dimensiones del layout overlay en WindowManager
        ShaderOverlayAccessibilityService.updateOverlayLayout()
    }

    override fun onDestroy() {
        // Lo que quedara encolado en el hilo principal (p. ej. un onStop de la proyección) ya no debe tocar nada
        mainHandler.removeCallbacksAndMessages(null)
        if (current === this) current = null
        // Retira la notificación (por si el sistema destruye el servicio sin pasar por CERRAR) y oculta overlay y botón
        setState(ServiceState.IDLE)
        try { unregisterReceiver(userPresentReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        displayManager.unregisterDisplayListener(displayListener)
        unregisterComponentCallbacks(componentCallbacks)
        VulkanEngineBridge.sensorsNeededListener = null
        CaptureScale.listener = null
        setSensorsEnabled(false)
        stopCaptureComponents()
        mediaProjection?.stop()
        mediaProjection = null
        VulkanEngineBridge.release()
        handlerThread?.quitSafely()
        shaderLoaderThread?.quitSafely()
        super.onDestroy()
    }

    private var sensorsRegistered = false

    /** Enciende o apaga el giroscopio y el acelerómetro. Se llama desde un hilo del motor: está sincronizado. */
    @Synchronized
    private fun setSensorsEnabled(enabled: Boolean) {
        if (enabled == sensorsRegistered) return
        val sm = sensorManager ?: return
        if (enabled) {
            sm.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.unregisterListener(this)
            java.util.Arrays.fill(lastGyro, 0f)
            java.util.Arrays.fill(lastAccel, 0f)
        }
        sensorsRegistered = enabled
        Log.i(TAG, "Sensores ${if (enabled) "ENCENDIDOS (el preset los usa)" else "apagados"}")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, lastGyro, 0, 3)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccel, 0, 3)
            }
        }
        VulkanEngineBridge.updateSensors(
            lastGyro[0], lastGyro[1], lastGyro[2],
            lastAccel[0], lastAccel[1], lastAccel[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateRotationData() {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        } else {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        VulkanEngineBridge.updateRotation(rotation)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
