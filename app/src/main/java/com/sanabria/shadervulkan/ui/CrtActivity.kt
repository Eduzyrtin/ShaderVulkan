package com.sanabria.shadervulkan.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base de las pantallas de la app: aplica la paleta y el glow elegidos en Ajustes (CrtTheme) antes de crear la ventana, y se
 * recrea sola al volver a primer plano si mientras tanto se cambiaron (así MainActivity se repinta al salir de Ajustes).
 */
open class CrtActivity : AppCompatActivity() {

    private var themeApplied = false
    private var themeSignature = 0

    /**
     * Aplica el tema CRT. onCreate lo hace solo; hay que llamarlo antes si se toca la ventana antes de super.onCreate (por
     * ejemplo con enableEdgeToEdge(), que ya crea la ventana con el fondo del tema). Es idempotente.
     */
    protected fun applyCrtTheme() {
        if (themeApplied) return
        themeApplied = true
        CrtTheme.applyTo(this)
        themeSignature = CrtTheme.signature(this)
    }

    /** Recrea la pantalla para repintarla con otra paleta o glow. Quien la recibe puede saber que no es un arranque ni un giro. */
    protected fun recreateForTheme() {
        themeRecreation = true
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCrtTheme()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (CrtTheme.signature(this) != themeSignature) recreateForTheme()
    }

    companion object {
        @Volatile private var themeRecreation = false

        /** true una sola vez si la pantalla que se acaba de crear viene de un cambio de paleta (para no repetir avisos de arranque). */
        fun consumeThemeRecreation(): Boolean = themeRecreation.also { themeRecreation = false }
    }
}
