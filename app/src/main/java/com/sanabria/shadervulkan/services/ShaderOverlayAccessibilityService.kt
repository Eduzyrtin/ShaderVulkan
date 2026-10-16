package com.sanabria.shadervulkan.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.sanabria.shadervulkan.model.PerfSettings
import com.sanabria.shadervulkan.model.ScreenLayout
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.overlay.FpsCounter
import com.sanabria.shadervulkan.overlay.LoadingHud
import com.sanabria.shadervulkan.overlay.OverlayControlManager
import com.sanabria.shadervulkan.overlay.OverlayManager
import com.sanabria.shadervulkan.overlay.ZoneEditor

/**
 * ShaderOverlayAccessibilityService — Servicio de accesibilidad para renderizar el overlay
 * sin que Android/Samsung bloqueen los toques en las aplicaciones compartidas.
 */
class ShaderOverlayAccessibilityService : AccessibilityService() {

    private var overlayManager: OverlayManager? = null
    // Ventana de la zona B del modo dual (T16, salida 1 del motor). Solo existe mientras el modo dual esté activo.
    private var secondOverlay: OverlayManager? = null
    private var controlManager: OverlayControlManager? = null
    private var loadingHud: LoadingHud? = null
    private var fpsCounter: FpsCounter? = null // contador de FPS (T66)
    private var lastPackageName: String? = null
    private var shadeDetector: ShadeDetector? = null
    private var zoneEditor: ZoneEditor? = null
    // Perfiles por app (T61)
    private var appProfiles: AppProfileManager? = null
    // true mientras el editor de zona está abierto: el botón flotante se oculta para que no tape el editor
    private var zoneEditing = false

    // Razones activas de suspensión del overlay. Solo vuelve cuando no queda ninguna.
    private val suspendReasons = mutableSetOf<String>()

    companion object {
        private const val TAG = "ShaderOverlayAccService"
        const val REASON_SHADE = "SHADE"
        /** Pausa manual (botón del menú flotante, DETENER de la notificación): oculta el overlay sin destruir su superficie. */
        const val REASON_MANUAL = "MANUAL"
        /** La app compartida (captura de una sola app) ya no es visible: la captura sale en negro. */
        const val REASON_CONTENT_HIDDEN = "CONTENT_HIDDEN"
        /** El editor de zona está abierto: se ve la app real para encajar el rectángulo. */
        const val REASON_EDIT = "EDIT"
        /** Hay delante una app con perfil "sin shader" (T61). */
        const val REASON_APP_OFF = "APP_OFF"
        private var instance: ShaderOverlayAccessibilityService? = null

        /** true mientras el overlay está suspendido; el servicio de captura descarta frames. */
        @Volatile var isOverlaySuspended = false
            private set

        /**
         * R14: suspendido por algo que no depende de la captura (todo salvo REASON_CONTENT_HIDDEN, cuyo fin avisa la propia captura):
         * mientras dure, el servicio de captura quita la superficie del VirtualDisplay.
         */
        @Volatile var isCaptureIdle = false
            private set

        /**
         * Suspende el overlay (transparente, sin destruir la superficie) por una razón.
         */
        fun suspendOverlay(reason: String) {
            onMain { instance?.suspendInternal(reason) }
        }

        /**
         * Retira una razón de suspensión; el overlay vuelve si no queda ninguna.
         */
        fun resumeOverlay(reason: String) {
            onMain { instance?.resumeInternal(reason) }
        }

        /**
         * Muestra el overlay si el servicio de accesibilidad está activo.
         */
        fun showOverlay() {
            instance?.showOverlayInternal()
        }

        /**
         * Oculta el overlay si el servicio de accesibilidad está activo.
         */
        fun hideOverlay() {
            instance?.hideOverlayInternal()
        }

        /**
         * Muestra el botón de control.
         */
        fun showControlButton() {
            val svc = instance ?: return
            if (!svc.zoneEditing) svc.controlManager?.showControlButton()
        }

        /** Guarda como perfil de la app que está delante lo que se ve ahora (T61). Devuelve el texto del aviso. */
        fun saveAppProfile(): String = instance?.appProfiles?.saveForeground() ?: ""

        /** Guarda lo que se ve ahora como perfil por defecto, para toda app sin perfil propio (T60). */
        fun saveDefaultProfile(): String = instance?.appProfiles?.saveForeground(asDefault = true) ?: ""

        /** Quita el perfil por defecto (T60). */
        fun removeDefaultProfile(): String = instance?.appProfiles?.removeDefault() ?: ""

        /** Hay un perfil por defecto guardado (T60). */
        fun hasDefaultProfile(): Boolean = instance?.appProfiles?.hasDefaultProfile() == true

        /** Quita el perfil de la app que está delante (T61). */
        fun removeAppProfile(): String = instance?.appProfiles?.removeForeground() ?: ""

        /** Nombre de la app que está delante y si ya tiene perfil (T61), para el menú flotante. */
        fun foregroundApp(): Pair<String, Boolean>? {
            val m = instance?.appProfiles ?: return null
            val pkg = m.foreground ?: return null
            return m.appLabel(pkg) to m.hasProfileForForeground()
        }

        /** Abre el editor de zona del shader (T06), desde el menú flotante. */
        fun startZoneEditor() {
            onMain { instance?.startZoneEditorInternal() }
        }

        /**
         * Oculta el botón de control.
         */
        fun hideControlButton() {
            instance?.controlManager?.hideControlButton()
        }

        /** Modo Glass (T31): la ventana del overlay pasa a transparente (o vuelve al fondo negro de Captura). */
        fun setGlassMode(glass: Boolean) {
            onMain {
                val svc = instance ?: return@onMain
                // R03: si alguna ventana del shader se rehizo (cambia su formato), ha quedado encima: botón, menú y paneles se suben otra vez
                val rebuilt = svc.overlays().map { it.setGlassMode(glass) }.any { it }
                svc.screensChangedInternal() // la segunda pantalla (T17) solo vale en Glass
                if (rebuilt) {
                    if (!svc.zoneEditing) svc.controlManager?.bringToFront()
                    svc.loadingHud?.bringToFront()
                    svc.fpsCounter?.bringToFront()
                }
            }
        }

        /** Se eligió otra combinación de pantallas (T17) o cambió algo que la afecta: las ventanas se recolocan. */
        fun screensChanged() {
            onMain { instance?.screensChangedInternal() }
        }

        /**
         * Pone el desenfoque de las ventanas del shader (T35) según GLASS_BLUR; en Captura o sin ese parámetro, ninguno. Barato: no hace
         * nada si el valor no cambió, así que se puede llamar a menudo (lo hace el temporizador de Glass y el slider del menú).
         */
        fun refreshBlur() {
            onMain {
                val svc = instance ?: return@onMain
                val glass = ShaderCaptureService.sessionMode == SessionMode.GLASS && ShaderCaptureService.isRunning
                val px = if (glass) VulkanEngineBridge.getParameterValue("GLASS_BLUR", 0f).toInt() else 0
                svc.overlays().forEach { it.setBlur(px) }
            }
        }

        /** Ajustes de rendimiento (T66) cambiados: tope de FPS y contador (esquina, tamaño, opacidad…) al momento. */
        fun applyPerfSettings() {
            onMain {
                val svc = instance ?: return@onMain
                svc.applyPerfSettingsInternal()
                svc.fpsCounter?.refresh()
            }
        }

        /** Tamaño y opacidad del botón flotante (T66): se rehace con los valores nuevos. */
        fun refreshButtonStyle() {
            onMain { instance?.controlManager?.refreshStyle() }
        }

        /** El estado de la sesión cambió: el botón flotante vuelve a pintarse (encendido o apagado). */
        fun refreshControlButton() {
            onMain { instance?.controlManager?.refreshState() }
        }

        /**
         * Oculta el menú si está abierto.
         */
        fun hideMenu() {
            instance?.controlManager?.hideMenu()
        }

        /**
         * Actualiza el layout del overlay tras un cambio de configuración/rotación.
         */
        fun updateOverlayLayout() {
            onMain { instance?.updateLayoutInternal() }
        }

        /** Se ha pedido cargar un preset: el panel de carga mira el progreso ya, sin esperar a su sondeo lento de reposo (R08). */
        fun onLoadRequested() {
            onMain { instance?.loadingHud?.kick() }
        }

        /** Las vistas del overlay solo se tocan desde el hilo principal; los llamantes pueden venir de otros hilos. */
        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block()
            else Handler(Looper.getMainLooper()).post(block)
        }

        /**
         * Indica si el servicio de accesibilidad está activo y conectado.
         */
        fun isConnected(): Boolean {
            return instance != null
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        controlManager = OverlayControlManager(this)
        loadingHud = LoadingHud(this)
        fpsCounter = FpsCounter(this) { controlManager?.isMenuShowing == true }
        // El menú, el botón y el contador de FPS siempre sobre las barras negras
        overlayManager?.onBarsAdded = {
            controlManager?.bringToFront()
            fpsCounter?.bringToFront()
        }
        zoneEditor = ZoneEditor(this)
        appProfiles = AppProfileManager(this)
        shadeDetector = ShadeDetector(this) { open ->
            if (open && ShaderPreferences.isAutoPauseOnShade(this)) {
                controlManager?.hideMenu()
                suspendInternal(REASON_SHADE)
            } else {
                resumeInternal(REASON_SHADE)
            }
        }
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Servicio de accesibilidad conectado")
        instance = this
        ScreenLayout.logDisplays(this)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        appProfiles?.onWindowsEvent() // si ya hay una app con perfil delante, se aplica
        if (ShaderCaptureService.hasControls) {
            controlManager?.showControlButton()
            showOverlayInternal()
            // La pausa manual vive en este servicio (suspendReasons) y se pierde si se desconecta: se restablece según el estado
            if (ShaderCaptureService.state == ServiceState.PAUSED) suspendInternal(REASON_MANUAL)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Se evalúa siempre para tener el estado del panel al día, aunque no haya captura. El texto solo se construye si el detector
            // vuelca el log (R08: antes se formaba en cada evento)
            shadeDetector?.onWindowsEvent {
                "evento=${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=${event.packageName} cls=${event.className} cambios=${event.windowChanges}"
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            appProfiles?.onWindowsEvent()
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        
        // Evitar bucle infinito si el cambio de ventana es causado por nosotros mismos
        if (packageName == lastPackageName) return
        lastPackageName = packageName
        
        // Log para depuración
        Log.d(TAG, "Cambio de ventana detectado: $packageName")
        
        // Ignorar nuestra propia app y el sistema para el botón flotante en términos de bucles
        val isOurApp = packageName == getPackageName()
        val isSystem = packageName == "com.android.systemui" || packageName == "android"

        if (!isOurApp && !isSystem) {
            // Solo nos aseguramos de que el botón esté visible si estamos en una app de terceros
            if (ShaderCaptureService.hasControls && !zoneEditing) {
                controlManager?.showControlButton()
            }
        }
        // Nota: Dentro de nuestra app, no forzamos ocultar el botón aquí para evitar el bucle.
        // El botón se gestiona por el estado de ShaderCaptureService (hasControls).
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "onConfigurationChanged en ShaderOverlayAccessibilityService: orientation=${newConfig.orientation}")
        // Una zona dibujada en una orientación no vale en la otra: se cierra el editor sin guardar
        zoneEditor?.cancel()
        com.sanabria.shadervulkan.model.ShaderZone.clearDetected() // las bandas medidas antes del giro ya no valen (T07)
        updateLayoutInternal()
        // Si el menú está abierto hay que rehacerlo: su tamaño y su disposición (una o dos columnas) dependen de la orientación
        controlManager?.onConfigurationChanged(newConfig)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio de accesibilidad interrumpido")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Servicio de accesibilidad desvinculado")
        instance = null
        clearSuspension()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        appProfiles?.reset()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        hideOverlayInternal()
        controlManager?.hideControlButton()
        instance = null
        clearSuspension()
        super.onDestroy()
    }

    private fun suspendInternal(reason: String) {
        if (!suspendReasons.add(reason)) return
        Log.i(TAG, "suspendOverlay($reason): razones=$suspendReasons")
        applySuspension()
    }

    private fun resumeInternal(reason: String) {
        if (!suspendReasons.remove(reason)) return
        Log.i(TAG, "resumeOverlay($reason): razones=$suspendReasons")
        applySuspension()
    }

    private fun applySuspension() {
        val suspended = suspendReasons.isNotEmpty()
        isOverlaySuspended = suspended
        overlays().forEach { it.setSuspended(suspended) }
        val idle = suspendReasons.any { it != REASON_CONTENT_HIDDEN }
        if (idle != isCaptureIdle) {
            isCaptureIdle = idle
            ShaderCaptureService.onCaptureNeedChanged() // R14
        }
    }

    private fun clearSuspension() {
        shadeDetector?.reset()
        suspendReasons.clear()
        applySuspension()
    }

    /**
     * Editor de zona (T06): se suspende el overlay del shader para ver la app real, se oculta el botón flotante (y su menú) y
     * se abre el editor. Al cerrarlo (OK, FULL, CANCEL o al girar) se retoma todo; si se guardó una zona, el cambio de
     * tamaño del overlay ya está aplicado.
     */
    private fun startZoneEditorInternal() {
        val editor = zoneEditor ?: return
        if (zoneEditing || editor.isShowing) return
        zoneEditing = true
        controlManager?.hideControlButton() // también cierra el menú
        suspendInternal(REASON_EDIT)
        editor.show(ScreenLayout.plan(this)[0]) { // en la pantalla donde está la salida 0; con las dos, el botón PANTALLA salta a la otra
            zoneEditing = false
            resumeInternal(REASON_EDIT)
            if (ShaderCaptureService.hasControls) controlManager?.showControlButton()
        }
    }

    private fun showOverlayInternal() {
        // El modo sale de la sesión: así vale también si el servicio de accesibilidad se conectó después de arrancarla
        val glass = ShaderCaptureService.sessionMode == SessionMode.GLASS
        overlays().forEach { it.setGlassMode(glass) }
        overlayManager?.setDisplay(ScreenLayout.plan(this)[0]) // antes de crear la ventana, para no ponerla primero en la otra pantalla
        overlayManager?.showOverlay()
        syncDual()
        if (!zoneEditing) controlManager?.showControlButton() // showControlButton ya llama internamente a bringToFront() si existe
        loadingHud?.start()
        loadingHud?.bringToFront() // el overlay recién añadido taparía el panel
        applyPerfSettingsInternal()
        fpsCounter?.start()
        fpsCounter?.bringToFront()
    }

    /** Las ventanas de shader que existen: la principal y, en modo dual, la de la zona B. */
    private fun overlays(): List<OverlayManager> = listOfNotNull(overlayManager, secondOverlay)

    /**
     * Pone las ventanas de shader donde toca (solo con la ventana principal puesta): la de la salida 0 en su display y la de la
     * salida 1 (zona B del modo dual, o la segunda pantalla de T17) si el plan la pide.
     */
    private fun syncDual() {
        val main = overlayManager ?: return
        if (!main.isShown) return
        val plan = ScreenLayout.plan(this)
        var rebuilt = main.setDisplay(plan[0])
        val second = secondOverlay
        if (plan.size > 1 && second == null) {
            val o = OverlayManager(this, 1)
            o.setGlassMode(ShaderCaptureService.sessionMode == SessionMode.GLASS)
            o.setSuspended(isOverlaySuspended)
            o.setDisplay(plan[1])
            secondOverlay = o
            o.showOverlay()
            refreshBlur() // R22: el temporizador de Glass ya solo lo aplica cuando cambia el valor
            ShaderCaptureService.requestZoneBIfNeeded(this) // la zona B con preset propio (T18)
            rebuilt = true
        } else if (plan.size > 1 && second != null) {
            if (second.setDisplay(plan[1])) rebuilt = true
        } else if (plan.size == 1 && second != null) {
            second.hideOverlay()
            secondOverlay = null
            ShaderCaptureService.suspendZoneB(this)
        }
        // Ventana nueva: encima de todo lo anterior, así que el botón, el menú y el panel de carga se suben otra vez
        if (rebuilt) {
            if (!zoneEditing) controlManager?.bringToFront()
            loadingHud?.bringToFront()
        }
    }

    // Segunda pantalla física (T17): si aparece, desaparece o gira, las ventanas se recolocan
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onSecondDisplayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = onSecondDisplayChanged(displayId)
        override fun onDisplayChanged(displayId: Int) = onSecondDisplayChanged(displayId)
    }

    private fun onSecondDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) return // el giro de la pantalla principal ya llega por onConfigurationChanged
        Log.i(TAG, "Cambió el display $displayId")
        if (overlayManager?.isShown == true) updateLayoutInternal()
    }

    /** Pantallas elegidas (T17), desde el menú flotante: recoloca las ventanas ya. */
    private fun screensChangedInternal() {
        if (overlayManager?.isShown == true) updateLayoutInternal()
    }

    /** El layout cambió (giro, zona, resolución, modo dual): ventanas de zona A y B al día. */
    private fun updateLayoutInternal() {
        syncDual()
        overlays().forEach { it.updateOverlayLayout() }
        loadingHud?.kick() // un cambio de zona, resolución o giro puede recompilar el preset (R08)
    }

    private fun hideOverlayInternal() {
        zoneEditor?.cancel() // si la captura se cierra con el editor abierto
        secondOverlay?.hideOverlay()
        secondOverlay = null
        overlayManager?.hideOverlay()
        controlManager?.hideMenu()
        loadingHud?.stop()
        fpsCounter?.stop()
    }

    private fun applyPerfSettingsInternal() {
        try { VulkanEngineBridge.setFpsCap(BatterySaver.effectiveFpsCap(this)) } catch (_: Throwable) {}
    }
}
