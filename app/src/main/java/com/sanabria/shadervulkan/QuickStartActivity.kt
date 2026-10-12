package com.sanabria.shadervulkan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.sanabria.shadervulkan.model.AppProfileStore
import com.sanabria.shadervulkan.model.ShaderPreferences
import com.sanabria.shadervulkan.services.ShaderCaptureService
import com.sanabria.shadervulkan.services.ShaderOverlayAccessibilityService

/**
 * Arranque rápido (T70/T72): "activar el último shader" desde el atajo del icono o el azulejo de ajustes rápidos. Transparente y sin
 * historial. Vuelve a poner el último shader activo en el último modo usado (Captura pide compartir pantalla; Glass arranca directo)
 * reutilizando [AutoStartActivity]. Si no se puede (sin shader elegido o sin el servicio de accesibilidad), abre la pantalla principal,
 * que explica qué falta. Con una sesión ya en marcha no hace nada.
 */
class QuickStartActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !ShaderCaptureService.isRunning) {
            val shader = ShaderPreferences.getActiveShaderId(this)
            if (shader == null || !ShaderOverlayAccessibilityService.isConnected()) {
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                val mode = if (ShaderPreferences.getRenderMode(this) == 1) AppProfileStore.Mode.GLASS else AppProfileStore.Mode.CAPTURE
                startActivity(Intent(this, AutoStartActivity::class.java).apply {
                    putExtra(AutoStartActivity.EXTRA_MODE, mode.name)
                    putExtra(AutoStartActivity.EXTRA_SHADER, shader)
                })
            }
        }
        finish()
    }
}
