package com.sanabria.shadervulkan

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.sanabria.shadervulkan.model.AppProfileStore
import com.sanabria.shadervulkan.services.ShaderCaptureService

/**
 * Arranca la sesión de un perfil por app (T61) desde una actividad transparente: un servicio en primer plano solo puede
 * empezar desde segundo plano si hay una actividad visible, y el permiso de compartir pantalla (modo Captura) solo se pide desde
 * una. La abre [com.sanabria.shadervulkan.services.AppProfileManager] al detectar la app, o el usuario tocando el aviso.
 * Extras: [EXTRA_PACKAGE] (la app del perfil), [EXTRA_MODE] ([AppProfileStore.Mode]) y [EXTRA_SHADER] (ruta del preset).
 */
class AutoStartActivity : ComponentActivity() {

    private var packageName_: String? = null
    private var shader: String? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, ShaderCaptureService::class.java).apply {
                action = ShaderCaptureService.ACTION_START
                putExtra(ShaderCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ShaderCaptureService.EXTRA_DATA, data)
                putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, shader)
                putExtra(ShaderCaptureService.EXTRA_AUTO_PACKAGE, packageName_)
            })
        } else {
            Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = System.currentTimeMillis()
        packageName_ = intent.getStringExtra(EXTRA_PACKAGE)
        shader = intent.getStringExtra(EXTRA_SHADER)
        // Tras girar, la actividad se recrea con el diálogo del sistema aún abierto: no pedirlo dos veces
        if (savedInstanceState != null) return

        when (AppProfileStore.Mode.parse(intent.getStringExtra(EXTRA_MODE))) {
            AppProfileStore.Mode.CAPTURE -> {
                ShaderCaptureService.showShareHint(this) // solo se admite compartir una app
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
                    else manager.createScreenCaptureIntent()
                )
            }
            AppProfileStore.Mode.GLASS -> {
                startForegroundService(Intent(this, ShaderCaptureService::class.java).apply {
                    action = ShaderCaptureService.ACTION_START_GLASS
                    putExtra(ShaderCaptureService.EXTRA_SHADER_PATH, shader)
                    putExtra(ShaderCaptureService.EXTRA_AUTO_PACKAGE, packageName_)
                })
                finish()
            }
            AppProfileStore.Mode.OFF -> finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SHADER = "extra_shader"

        /** Instante en que la actividad llegó a abrirse: el gestor lo mira para saber si el sistema bloqueó el arranque desde segundo plano. */
        @Volatile var started = 0L
    }
}
