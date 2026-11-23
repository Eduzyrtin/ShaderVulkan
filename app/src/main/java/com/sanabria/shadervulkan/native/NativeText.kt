package com.sanabria.shadervulkan.native

import android.content.Context
import com.sanabria.shadervulkan.R

/**
 * Traduce los textos que manda el motor nativo (etiqueta de carga y avisos de fallo). El C++ no escribe frases en ningún
 * idioma: manda códigos "@clave|arg1|arg2…" (LoadProgress::code) y aquí se montan con las cadenas de la app, en el idioma
 * que tenga el usuario. Varios avisos llegan separados por '\u001e' y se muestran unidos con « · ».
 * Un texto sin '@' se enseña tal cual.
 */
object NativeText {

    fun format(context: Context, raw: String): String =
        raw.split('\u001e').filter { it.isNotBlank() }.joinToString(" · ") { one(context, it) }

    private fun one(context: Context, raw: String): String {
        if (!raw.startsWith("@")) return raw
        val parts = raw.substring(1).split('|')
        val key = parts[0]
        fun arg(i: Int) = parts.getOrNull(i).orEmpty()
        fun num(i: Int) = arg(i).toIntOrNull() ?: 0
        return when (key) {
            "reading" -> context.getString(R.string.native_load_reading)
            "preparing" -> context.getString(R.string.native_load_preparing)
            "compiling" -> context.getString(R.string.native_load_compiling)
            "analyze" -> context.getString(R.string.native_load_analyze_pass, num(1), num(2), arg(3))
            "compile" -> context.getString(R.string.native_load_compile_pass, num(1), num(2), arg(3))
            "texture" -> context.getString(R.string.native_load_texture, arg(1))
            // El motivo viene del analizador de presets (texto técnico): se une todo lo que siga, por si llevaba '|'
            "invalid" -> context.getString(R.string.native_status_invalid_preset, parts.drop(1).joinToString("|"))
            "nopasses" -> context.getString(R.string.native_status_no_passes)
            "lutfail" -> context.getString(R.string.native_status_lut_failed, arg(1))
            "passfail" -> if (num(3) > 0) {
                context.getString(R.string.native_status_passes_failed, num(1), arg(2), num(3))
            } else {
                context.getString(R.string.native_status_pass_failed, num(1), arg(2))
            }
            else -> raw
        }
    }
}
