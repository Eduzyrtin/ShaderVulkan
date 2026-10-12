package com.sanabria.shadervulkan

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.services.ServiceState
import com.sanabria.shadervulkan.services.ShaderCaptureService

/**
 * Actividad transparente para recuperar la captura cuando el sistema corta la proyección (p. ej. al bloquear la pantalla).
 * Se abre desde la notificación en estado INTERRUPTED: pide un permiso de compartir pantalla nuevo, relanza el servicio con el
 * token y se cierra sola. El shader y los ajustes se conservan; solo hay que volver a dar el permiso.
 */
class ProjectionRecoveryActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // Si mientras se decidía se pulsó CERRAR, el token no resucita nada
            if (ShaderCaptureService.state == ServiceState.INTERRUPTED) {
                val intent = Intent(this, ShaderCaptureService::class.java).apply {
                    action = ShaderCaptureService.ACTION_START
                    putExtra(ShaderCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ShaderCaptureService.EXTRA_DATA, data)
                    putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, ShaderPreferences.getActiveShaderId(this@ProjectionRecoveryActivity))
                }
                startForegroundService(intent)
            }
        } else {
            Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Solo hay algo que recuperar si la captura está interrumpida: tras CERRAR no hay sesión, y si ya se reanudó no hace falta permiso
        if (ShaderCaptureService.state != ServiceState.INTERRUPTED) {
            finish()
            return
        }
        // Tras una rotación la actividad se recrea y el diálogo del sistema sigue abierto: no pedirlo dos veces
        if (savedInstanceState != null) return

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}
