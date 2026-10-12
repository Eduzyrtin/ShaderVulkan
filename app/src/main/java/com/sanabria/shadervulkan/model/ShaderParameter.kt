package com.sanabria.shadervulkan.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

data class ShaderParameter(
    val name: String,
    val description: String,
    val initial: Float,
    val current: Float,
    val minimum: Float,
    val maximum: Float,
    val step: Float
) {
    private val range: Float get() = maximum - minimum

    /**
     * Parámetro puramente informativo (etiqueta/separador): rango nulo (min == max), o descripción hecha solo de
     * símbolos ("-----", "====") o en blanco. RetroArch los lista como una fila más; aquí se muestran como texto no editable.
     */
    val isInfo: Boolean
        get() = range <= 0f || (description.isNotBlank() && description.none { it.isLetterOrDigit() })

    /** Etiqueta de relleno: descripción en blanco, solo sirve de espacio entre grupos. */
    val isSpacer: Boolean get() = isInfo && description.isBlank()

    /** Separador visual: descripción formada solo por símbolos ("-------", "======"). */
    val isDivider: Boolean get() = isInfo && description.isNotBlank() && description.none { it.isLetterOrDigit() }

    /** Texto a mostrar como título del parámetro: la descripción (como RetroArch) o, si no hay, su nombre. */
    val title: String get() = description.trim().ifEmpty { name }

    /** Paso real del control. Si el preset no lo define (o es inválido) se usa 0.01, como el motor nativo. */
    val effectiveStep: Float
        get() = if (step > 0f && step.isFinite()) step else 0.01f

    /** Nº de pasos discretos entre mínimo y máximo (al menos 1 si el rango es válido; 0 si el rango no lo es). */
    val stepCount: Int
        get() = if (range > 0f) max(1, (range / effectiveStep).roundToInt()) else 0

    /** Paso ajustado para que el rango sea múltiplo exacto (Material Slider lo exige; los extremos se respetan). */
    val alignedStep: Float
        get() = if (stepCount > 0) range / stepCount else 0f

    /** Parámetro de valores enteros (0, 1, 2…): paso, mínimo y máximo sin decimales. */
    val isInteger: Boolean
        get() = isWhole(effectiveStep) && isWhole(minimum) && isWhole(maximum)

    /** Ajusta 'value' a la rejilla min + k·paso, dentro de [min, max], sin ruido de coma flotante. */
    fun snap(value: Float): Float {
        if (range <= 0f) return minimum
        val k = ((value - minimum) / alignedStep).roundToInt().coerceIn(0, stepCount)
        val v = (minimum + k * alignedStep).coerceIn(minimum, maximum)
        // Limpiar errores de acumulación de coma flotante (p. ej. 0.30000001). Siempre a 6 decimales como mínimo: redondear a los
        // decimales que se muestran (máx. 4) sacaba el valor de la rejilla en pasos finos (1/64 = 0.015625 -> 0.4844), y el Slider
        // de Material lanza IllegalStateException si el valor no cae en min + k·paso.
        val scale = pow10(max(decimals, 6))
        return (Math.round(v.toDouble() * scale) / scale).toFloat().coerceIn(minimum, maximum)
    }

    /** Valor de la rejilla correspondiente al índice k (0..stepCount). */
    fun valueAt(k: Int): Float = snap(minimum + k.coerceIn(0, stepCount) * alignedStep)

    /** Índice de la rejilla más cercano a 'value'. */
    fun indexOf(value: Float): Int =
        if (range <= 0f) 0 else ((value - minimum) / alignedStep).roundToInt().coerceIn(0, stepCount)

    /** Nº de decimales a mostrar según el paso (0 para enteros, máx. 4). */
    val decimals: Int
        get() {
            if (isInteger) return 0
            var s = effectiveStep.toDouble()
            var d = 0
            while (d < 4 && abs(s - Math.round(s)) > 1e-4) { s *= 10.0; d++ }
            return max(1, d)
        }

    fun format(value: Float): String =
        if (decimals == 0) value.roundToInt().toString() else String.format(java.util.Locale.US, "%.${decimals}f", value)

    private fun isWhole(v: Float) = abs(v - floor(v + 0.5f)) < 1e-4f

    private fun pow10(n: Int): Double { var r = 1.0; repeat(n) { r *= 10.0 }; return r }
}
