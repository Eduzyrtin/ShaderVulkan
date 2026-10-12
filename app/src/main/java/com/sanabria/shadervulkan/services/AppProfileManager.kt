package com.sanabria.shadervulkan.services

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.sanabria.shadervulkan.AutoStartActivity
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.AppProfileStore
import com.sanabria.shadervulkan.model.AppProfileStore.AppProfile
import com.sanabria.shadervulkan.model.AppProfileStore.Mode
import com.sanabria.shadervulkan.model.ShaderParamStore
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.native.VulkanEngineBridge
import com.sanabria.shadervulkan.overlay.AppExitDialog

/**
 * Perfiles por app (T61): vigila qué app está delante y aplica su perfil.
 *
 *  - App con perfil → carga su shader y su perfil de ajustes y pone la sesión en su modo: Glass arranca solo; Captura abre
 *    [AutoStartActivity], que pide el permiso de compartir pantalla (el token es de un solo uso y la captura es de una app).
 *    "Sin shader" oculta el overlay mientras esa app está delante.
 *  - App sin perfil con una sesión que arrancó un perfil → pregunta si pausar o cerrar (AppExitDialog).
 *  - Una sesión puesta a mano por el usuario (RUN SYSTEM) no se toca al salir de una app sin perfil.
 *
 * Quién está delante se lee de las ventanas de aplicación del servicio de accesibilidad (no del paquete de los eventos, que
 * también vienen de teclados, diálogos de permisos y del propio sistema), tras una pausa corta para no reaccionar a transiciones.
 */
class AppProfileManager(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var exitDialog: AppExitDialog? = null

    /** La última app (distinta de las ignoradas) que estuvo delante. */
    var foreground: String? = null
        private set

    /** Hay una app "sin shader" delante: el overlay está oculto por ese motivo. */
    private var offApplied = false

    private val check = Runnable { evaluate() }

    /** Llamar en cada cambio de ventanas del servicio de accesibilidad. */
    fun onWindowsEvent() {
        handler.removeCallbacks(check)
        handler.postDelayed(check, SETTLE_MS)
    }

    /** El servicio se apaga o se ha desactivado la función: se suelta todo. */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        dismissExitDialog()
        if (offApplied) ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_APP_OFF)
        offApplied = false
        foreground = null
    }

    private fun evaluate() {
        if (!ShaderPreferences.isAppProfilesEnabled(service)) {
            if (offApplied || exitDialog != null) reset()
            return
        }
        val pkg = detectForeground() ?: return
        if (pkg == foreground) return
        Log.i(TAG, "App delante: $pkg (antes $foreground)")
        foreground = pkg
        onAppChanged(pkg)
    }

    /** Paquete de la ventana de aplicación que tiene el foco; null si no hay ninguna que cuente. */
    private fun detectForeground(): String? {
        val windows = try { service.windows } catch (e: Exception) { return null }
        // R08: getRoot() es una llamada entre procesos que atiende el hilo principal de la app (el emulador). Primero solo las ventanas
        // con el foco o activas (lo normal es una); las demás, solo si ninguna de esas cuenta. Mismo resultado que antes.
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !(w.isFocused || w.isActive)) continue
            val pkg = w.root?.packageName?.toString() ?: continue
            if (pkg in IGNORED || pkg == service.packageName) continue
            return pkg
        }
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || w.isFocused || w.isActive) continue
            val pkg = w.root?.packageName?.toString() ?: continue
            if (pkg in IGNORED || pkg == service.packageName) continue
            return pkg
        }
        return null
    }

    private fun onAppChanged(pkg: String) {
        dismissExitDialog()
        if (offApplied) {
            ShaderOverlayAccessibilityService.resumeOverlay(ShaderOverlayAccessibilityService.REASON_APP_OFF)
            offApplied = false
        }
        // Sin perfil propio vale el perfil por defecto (T60), si lo hay: es el de "todas las demás apps", Inicio incluido
        val profile = AppProfileStore.get(service, pkg) ?: AppProfileStore.get(service, AppProfileStore.DEFAULT_KEY)?.copy(packageName = pkg)
        if (profile == null) {
            askWhatToDoWithAutoSession()
            return
        }
        enter(profile)
    }

    /** Se salió de una app con perfil a otra sin él: la sesión que arrancó ese perfil se pausa o se cierra, a elección. */
    private fun askWhatToDoWithAutoSession() {
        val auto = ShaderCaptureService.autoPackage ?: return
        if (ShaderCaptureService.state != ServiceState.ACTIVE) return // en pausa o sin sesión no hay nada que preguntar
        val dialog = AppExitDialog(service)
        exitDialog = dialog
        dialog.show(
            appLabel(auto),
            onPause = { sendToService(ShaderCaptureService.ACTION_STOP) },
            onClose = { sendToService(ShaderCaptureService.ACTION_STOP_FULL) },
            onKeep = { ShaderCaptureService.markAuto(null) } // la sesión pasa a ser del usuario: no se vuelve a preguntar
        )
    }

    private fun dismissExitDialog() {
        exitDialog?.dismiss()
        exitDialog = null
    }

    private fun enter(profile: AppProfile) {
        val pkg = profile.packageName
        val state = ShaderCaptureService.state
        val active = state == ServiceState.ACTIVE || state == ServiceState.PAUSED

        if (profile.mode == Mode.OFF) {
            if (ShaderCaptureService.isRunning) {
                ShaderOverlayAccessibilityService.suspendOverlay(ShaderOverlayAccessibilityService.REASON_APP_OFF)
                offApplied = true
            }
            return
        }

        val wantMode = if (profile.mode == Mode.CAPTURE) SessionMode.CAPTURE else SessionMode.GLASS
        val previousShader = ShaderPreferences.getActiveShaderId(service)
        prepare(profile)

        // Misma modalidad ya en marcha: basta cambiar shader y ajustes. En Captura, la sesión vale si es de esta app o la puso el
        // usuario (por ejemplo, pantalla entera); si es de otra app compartida, esta saldría en negro y hay que pedir permiso de nuevo.
        val sessionFits = active && ShaderCaptureService.sessionMode == wantMode &&
            (wantMode == SessionMode.GLASS || ShaderCaptureService.autoPackage == null || ShaderCaptureService.autoPackage == pkg)
        if (sessionFits) {
            ShaderCaptureService.markAuto(pkg)
            if (previousShader != profile.shaderId) {
                ShaderCaptureService.requestShader(service, profile.shaderId) // carga y aplica el perfil activo (ya puesto en prepare)
            } else {
                profile.profileName?.let { VulkanEngineBridge.applyProfile(service, profile.shaderId, it) }
            }
            if (state == ServiceState.PAUSED) sendToService(ShaderCaptureService.ACTION_START) // reanudar no pide permiso
            VulkanEngineBridge.refreshZone() // las pantallas y la zona de la segunda pueden haber cambiado
            return
        }
        start(profile)
    }

    /** Deja guardado lo que el perfil dice, para que el arranque (o la carga) lo recoja de las preferencias. */
    private fun prepare(p: AppProfile) {
        ShaderPreferences.setActiveShaderId(service, p.shaderId, p.shaderName)
        p.profileName?.let { ShaderParamStore.setActiveProfile(service, p.shaderId, it) }
        ShaderPreferences.setScreens(service, p.screens)
        ShaderPreferences.setSecondZone(service, false, p.zone2Portrait)
        ShaderPreferences.setSecondZone(service, true, p.zone2Landscape)
        // Preset propio de la zona B (T18): el perfil lo trae o la B vuelve a seguir al de la A. La carga la pide el servicio cuando el
        // modo dual está puesto (después de aplicar los ajustes del shader, que son los que lo activan).
        if (p.zoneBShaderId != null) {
            ShaderPreferences.setZoneBShader(service, p.zoneBShaderId, p.zoneBShaderName ?: p.zoneBShaderId)
            p.zoneBProfile?.let { ShaderParamStore.setActiveProfile(service, p.zoneBShaderId, it) }
        } else if (ShaderPreferences.getZoneBShaderId(service) != null) {
            ShaderCaptureService.clearZoneB(service)
        }
    }

    /** Sesión nueva para el perfil. Glass, si el sistema deja, directo; si no (y siempre en Captura) por una actividad. */
    private fun start(p: AppProfile) {
        if (p.mode == Mode.GLASS) {
            try {
                service.startForegroundService(Intent(service, ShaderCaptureService::class.java).apply {
                    action = ShaderCaptureService.ACTION_START_GLASS
                    putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, p.shaderId)
                    putExtra(ShaderCaptureService.EXTRA_AUTO_PACKAGE, p.packageName)
                })
                Log.i(TAG, "Glass arrancado para ${p.packageName}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "El sistema no deja arrancar el servicio desde segundo plano (${e.message}); se abre una actividad")
            }
        }
        val launchedAt = System.currentTimeMillis()
        try {
            service.startActivity(startIntent(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            Log.w(TAG, "startActivity falló: ${e.message}")
        }
        // Android puede ignorar en silencio el arranque de una actividad desde segundo plano: si no llegó a abrirse, un aviso
        handler.postDelayed({
            if (AutoStartActivity.started < launchedAt) postHint(p)
        }, ACTIVITY_CHECK_MS)
    }

    private fun startIntent(p: AppProfile) = Intent(service, AutoStartActivity::class.java).apply {
        putExtra(AutoStartActivity.EXTRA_PACKAGE, p.packageName)
        putExtra(AutoStartActivity.EXTRA_MODE, p.mode.name)
        putExtra(AutoStartActivity.EXTRA_SHADER, p.shaderId)
    }

    /** "Toca para activar el shader en X": plan B cuando el sistema no deja abrir la actividad desde segundo plano. */
    private fun postHint(p: AppProfile) {
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(HINT_CHANNEL, service.getString(R.string.notif_channel_app_hint), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val pending = PendingIntent.getActivity(
            service, HINT_ID, startIntent(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = appLabel(p.packageName)
        nm.notify(HINT_ID, NotificationCompat.Builder(service, HINT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(service.getString(R.string.notif_app_hint_title, label))
            .setContentText(service.getString(R.string.notif_app_hint_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build())
    }

    private fun sendToService(action: String) {
        service.startService(Intent(service, ShaderCaptureService::class.java).setAction(action))
    }

    fun appLabel(pkg: String): String = try {
        service.packageManager.getApplicationInfo(pkg, 0).loadLabel(service.packageManager).toString()
    } catch (_: Exception) { pkg }

    // ---------------------------------------------------------------------------------------------------------------------
    // Desde el menú flotante: guardar y quitar el perfil de la app que tiene delante
    // ---------------------------------------------------------------------------------------------------------------------

    fun hasProfileForForeground(): Boolean = foreground?.let { AppProfileStore.get(service, it) != null } == true

    /**
     * Guarda como perfil de la app de delante lo que se está viendo: shader, parámetros, resolución, zona, barras y modo dual (en
     * un perfil de ajustes del shader llamado "APP <nombre>") más el modo y, en dos pantallas, cuáles y la zona de la segunda.
     * Devuelve el texto para el aviso.
     */
    fun saveForeground(asDefault: Boolean = false): String {
        val pkg = if (asDefault) AppProfileStore.DEFAULT_KEY else (foreground ?: return service.getString(R.string.toast_app_none))
        val shaderId = ShaderPreferences.getActiveShaderId(service) ?: return service.getString(R.string.toast_app_no_shader)
        val shaderName = ShaderPreferences.getActiveShaderName(service) ?: shaderId
        val label = if (asDefault) service.getString(R.string.apps_default_label) else appLabel(pkg)
        val profileName = "APP $label"
        // Las instantáneas son de cada zona: la A se lee con la salida 0 y la B (si tiene preset propio) con la 1
        val hadProfile = AppProfileStore.get(service, pkg) != null
        val zoneB = if (ShaderPreferences.isDual(service)) ShaderPreferences.getZoneBShaderId(service) else null
        VulkanEngineBridge.setEditOutput(0)
        VulkanEngineBridge.saveCurrentAsProfile(service, shaderId, profileName)
        if (zoneB != null) {
            VulkanEngineBridge.setEditOutput(1)
            VulkanEngineBridge.saveCurrentAsProfile(service, zoneB, profileName)
        }
        VulkanEngineBridge.syncEditOutput(service)
        val mode = when {
            ShaderCaptureService.isRunning -> if (ShaderCaptureService.sessionMode == SessionMode.GLASS) Mode.GLASS else Mode.CAPTURE
            ShaderPreferences.getRenderMode(service) == VulkanEngineBridge.MODE_GLASS -> Mode.GLASS
            else -> Mode.CAPTURE
        }
        AppProfileStore.save(service, AppProfile(
            packageName = pkg, mode = mode, shaderId = shaderId, shaderName = shaderName, profileName = profileName,
            screens = ShaderPreferences.getScreens(service),
            zone2Portrait = ShaderPreferences.getSecondZone(service, false),
            zone2Landscape = ShaderPreferences.getSecondZone(service, true),
            zoneBShaderId = zoneB,
            zoneBShaderName = if (zoneB != null) ShaderPreferences.getZoneBShaderName(service) else null,
            zoneBProfile = if (zoneB != null) profileName else null
        ))
        // La sesión de ahora pasa a ser de esta app: al salir de ella se preguntará
        if (ShaderCaptureService.isRunning && !asDefault) ShaderCaptureService.markAuto(pkg)
        return service.getString(if (hadProfile) R.string.toast_app_updated else R.string.toast_app_saved, label, mode.name, shaderName)
    }

    fun hasDefaultProfile(): Boolean = AppProfileStore.get(service, AppProfileStore.DEFAULT_KEY) != null

    fun removeDefault(): String {
        AppProfileStore.delete(service, AppProfileStore.DEFAULT_KEY)
        return service.getString(R.string.toast_app_default_removed)
    }

    fun removeForeground(): String {
        val pkg = foreground ?: return service.getString(R.string.toast_app_none)
        AppProfileStore.delete(service, pkg)
        if (ShaderCaptureService.autoPackage == pkg) ShaderCaptureService.markAuto(null)
        return service.getString(R.string.toast_app_removed, appLabel(pkg))
    }

    fun toast(text: String) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "SVDIAG_APPS"
        private const val SETTLE_MS = 350L
        private const val ACTIVITY_CHECK_MS = 1500L
        private const val HINT_CHANNEL = "ShaderAppHintChannel"
        private const val HINT_ID = 2

        /** Ventanas de aplicación que no cuentan como "la app que usa el usuario": el sistema y los diálogos de permisos. */
        private val IGNORED = setOf(
            "com.android.systemui", "android",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller"
        )
    }
}
