package com.sanabria.shadervulkan.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.graphics.ColorUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sanabria.shadervulkan.ProjectionRecoveryActivity
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.ui.CrtTheme
import com.sanabria.shadervulkan.ui.CrtUi

/**
 * La ÚNICA notificación de la app (T14). Un solo ID: lo que cambia es su contenido según el [ServiceState] de la sesión.
 *
 *  - ACTIVE:       "activo"         → DETENER + CERRAR
 *  - PAUSED:       "pausado"        → REANUDAR + CERRAR
 *  - INTERRUPTED:  "interrumpida"   → REANUDAR (pide el permiso otra vez) + CERRAR
 *
 * Para que la interrupción salga como aviso emergente sin dejar una segunda notificación, la misma notificación cambia de
 * canal: normal (importancia baja, silenciosa) en ACTIVE/PAUSED y de importancia alta SOLO en INTERRUPTED. El aviso emergente
 * lo decide quien publica con [build] (`alert`): una actualización con `setOnlyAlertOnce(true)` no vuelve a avisar.
 *
 * Solo construye; publicar (startForeground / notify) y cancelar es cosa del servicio, que es el único que sabe si es foreground.
 */
internal class CaptureNotification(private val context: Context) {

    companion object {
        private const val TAG = "CaptureNotification"
        const val ID = 1

        /** Canal de ACTIVE y PAUSED. Mismo ID que antes de T14: solo cambia el nombre que ve el usuario. */
        const val STATUS_CHANNEL_ID = "ShaderCaptureChannel"
        /** Canal de INTERRUPTED: importancia alta (aviso emergente), sin sonido ni vibración. */
        const val ALERT_CHANNEL_ID = "ShaderInterruptedChannel"
        /** Canal de la segunda notificación que existía antes de T14. Un canal ya creado no puede quitarle el sonido, así que se sustituye. */
        private const val LEGACY_ALERT_CHANNEL_ID = "ShaderRecoveryChannel"

        // Un requestCode distinto por destino: dos PendingIntent solo se distinguen por la acción y se pisaban entre sí
        private const val REQ_OPEN_APP = 10
        private const val REQ_PAUSE = 11
        private const val REQ_RESUME = 12
        private const val REQ_CLOSE = 13
        private const val REQ_RECOVER = 14

    }

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Color de acento (el fondo del icono): un término medio entre lo tenue y la línea de la paleta elegida, legible en sombra clara y oscura. */
    private fun accentColor(): Int {
        val ui = CrtUi.themed(context)
        return ColorUtils.blendARGB(CrtTheme.color(ui, R.attr.crtDim), CrtTheme.color(ui, R.attr.crtFaint), 0.35f)
    }

    fun createChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, context.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_status_description)
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, context.getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_alert_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        try {
            notificationManager.deleteNotificationChannel(LEGACY_ALERT_CHANNEL_ID)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo borrar el canal antiguo: ${e.message}")
        }
    }

    /** @param alert true solo si esta publicación debe salir como aviso emergente (la interrupción, cuando el usuario mira el móvil). */
    fun build(state: ServiceState, alert: Boolean, mode: SessionMode = SessionMode.CAPTURE): Notification {
        val glass = mode == SessionMode.GLASS
        val builder = NotificationCompat.Builder(context, if (state == ServiceState.INTERRUPTED) ALERT_CHANNEL_ID else STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accentColor())
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            ServiceState.ACTIVE -> builder
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentTitle(context.getString(if (glass) R.string.notif_glass_active_title else R.string.notif_active_title))
                .setContentText(context.getString(if (glass) R.string.notif_glass_active_text else R.string.notif_active_text))
                .setContentIntent(openApp())
                .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.notif_action_pause), service(REQ_PAUSE, ShaderCaptureService.ACTION_STOP))
            ServiceState.PAUSED -> builder
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentTitle(context.getString(if (glass) R.string.notif_glass_paused_title else R.string.notif_paused_title))
                .setContentText(context.getString(if (glass) R.string.notif_glass_paused_text else R.string.notif_paused_text))
                .setContentIntent(openApp())
                .addAction(android.R.drawable.ic_media_play, context.getString(R.string.notif_action_resume), service(REQ_RESUME, ShaderCaptureService.ACTION_START))
            ServiceState.INTERRUPTED -> builder
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentTitle(context.getString(R.string.notif_interrupted_title))
                .setContentText(context.getString(R.string.notif_interrupted_text))
                .setContentIntent(recovery())
                .addAction(android.R.drawable.ic_media_play, context.getString(R.string.notif_action_resume), recovery())
            ServiceState.IDLE, ServiceState.CLOSING ->
                throw IllegalStateException("Sin sesión no hay notificación (estado $state)")
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_close), service(REQ_CLOSE, ShaderCaptureService.ACTION_STOP_FULL))
        return builder.build()
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun service(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
        context, requestCode,
        Intent(context, ShaderCaptureService::class.java).setAction(action),
        flags()
    )

    /** Abre ProjectionRecoveryActivity: pide el permiso de captura otra vez (el token de la proyección cortada no se puede reutilizar). */
    private fun recovery(): PendingIntent = PendingIntent.getActivity(
        context, REQ_RECOVER,
        Intent(context, ProjectionRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        flags()
    )

    /** Igual que tocar el icono de la app: trae al frente la tarea que ya exista en vez de apilar otra MainActivity. */
    private fun openApp(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, com.sanabria.shadervulkan.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQ_OPEN_APP, launch, flags())
    }
}
