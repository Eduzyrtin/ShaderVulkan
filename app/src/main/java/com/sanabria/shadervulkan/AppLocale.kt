package com.sanabria.shadervulkan

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * Idioma elegido para la app (Ajustes de la app o el selector de idioma por app de Android 13+) aplicado a un contexto.
 *
 * Las actividades ya lo reciben solas, pero los servicios no: el menú flotante, el aviso de carga y las notificaciones
 * salían en el idioma del sistema aunque la app estuviera en otro. Cada servicio lo aplica en attachBaseContext.
 */
object AppLocale {

    fun wrap(base: Context): Context {
        val tags = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
            } else {
                AppCompatDelegate.getApplicationLocales().toLanguageTags()
            }
        } catch (_: Throwable) {
            ""
        }
        if (tags.isBlank()) return base // sin idioma propio: el del sistema, como hasta ahora
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tags))
        return base.createConfigurationContext(config)
    }
}
