package com.sanabria.shadervulkan.model

import android.net.Uri

/**
 * Conjunto de presets combinados (T45, "Shader Preset Prepend/Append" de RetroArch). Un conjunto es un shader más: su identificador
 * (que es también la ruta que recibe el servicio y la clave de los perfiles de ajustes) es "stack:" y las rutas de sus piezas
 * separadas por un carácter de control, en orden de ejecución (la primera pieza va delante, sobre la captura; la última, detrás).
 * Con una sola pieza el identificador es la propia ruta, como siempre.
 */
object ShaderStack {
    const val PREFIX = "stack:"
    private const val SEP = "\u001F"
    /** Tope de piezas del conjunto (el motor también limita las pasadas totales). */
    const val MAX_PARTS = 8

    fun isStack(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    /** Piezas del conjunto, en orden de ejecución. Un shader normal es un conjunto de una pieza. */
    fun parts(id: String): List<String> =
        if (isStack(id)) id.removePrefix(PREFIX).split(SEP).filter { it.isNotEmpty() } else listOf(id)

    /** Identificador del conjunto; con una sola pieza, la propia ruta. Null si no hay piezas. */
    fun make(parts: List<String>): String? = when (parts.size) {
        0 -> null
        1 -> parts[0]
        else -> PREFIX + parts.joinToString(SEP)
    }

    /** Nombre corto de una pieza: el fichero sin extensión. */
    fun partName(path: String): String {
        val decoded = if (path.startsWith("content://") || path.startsWith("file://")) Uri.decode(path) else path
        return decoded.substringAfterLast('/').substringBeforeLast('.')
    }

    /** "crt-pi + vignette" */
    fun displayName(parts: List<String>): String = parts.joinToString(" + ") { partName(it) }
}
