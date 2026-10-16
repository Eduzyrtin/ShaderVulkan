package com.sanabria.shadervulkan.model

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import androidx.annotation.StringRes
import com.sanabria.shadervulkan.R
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Zona del shader" (PROYECTO.md, Bloque 1B): el rectángulo de la pantalla sobre el que se aplica el shader. Fuera de la
 * zona se ve la pantalla real, sin tocar. Este objeto es el ÚNICO sitio que decide cuál es la zona; OverlayManager la usa
 * para dimensionar la ventana del overlay y se la pasa al motor (setInputRegion) para recortar la captura.
 *
 * La zona sale de un aspecto (centrada y lo más grande posible con esa forma, T05) o se dibuja a mano con el editor de zona
 * (T06, una por orientación, opción "Manual"). La automática por detección de bandas negras (T07) se añadirá aquí.
 */
object ShaderZone {

    /** Aspecto = ancho/alto. [FULL_SCREEN] = sin zona; [BY_RESOLUTION] = el que corresponde a la resolución elegida. */
    const val FULL_SCREEN = 0f
    const val BY_RESOLUTION = -1f

    /** [labelRes] != 0: el texto está en strings.xml (traducido); si no, [label] vale tal cual (4:3, 16:9...). */
    class AspectOption(val label: String, val ratio: Float, @param:StringRes val labelRes: Int = 0) {
        fun text(ctx: Context): String = if (labelRes != 0) ctx.getString(labelRes) else label
    }

    /**
     * Los 12 primeros índices son los de siempre (se guardan en los perfiles: no se pueden reordenar). Los nuevos van al final.
     */
    val ASPECT_OPTIONS: List<AspectOption> = listOf(
        AspectOption("Full screen (no zone)", FULL_SCREEN, R.string.aspect_full),      // 0
        AspectOption("4:3", 4f / 3f),                                   // 1
        AspectOption("16:9", 16f / 9f),                                 // 2
        AspectOption("16:10", 16f / 10f),                               // 3
        AspectOption("1:1", 1f, R.string.aspect_square),                               // 4
        AspectOption("21:9", 21f / 9f, R.string.aspect_ultrawide),                     // 5
        AspectOption("3:2", 3f / 2f),                                   // 6
        AspectOption("3:4", 3f / 4f, R.string.aspect_portrait43),                    // 7
        AspectOption("2:1", 2f),                                        // 8
        AspectOption("8:7", 8f / 7f, R.string.aspect_retro),                           // 9
        AspectOption("19.5:9", 19.5f / 9f, R.string.aspect_mobile),             // 10
        AspectOption("9:19.5", 9f / 19.5f, R.string.aspect_mobile_portrait),           // 11
        AspectOption("Auto (by resolution)", BY_RESOLUTION, R.string.aspect_auto_resolution),         // 12
        AspectOption("10:9 (Game Boy)", 10f / 9f),                      // 13
        AspectOption("Manual (drawn zone)", FULL_SCREEN, R.string.aspect_manual),            // 14: la zona sale de ShaderPreferences.getManualZone
        AspectOption("Auto (detect black bars)", FULL_SCREEN, R.string.aspect_auto_detect)      // 15: la zona la mide el motor (T07)
    )

    const val AUTO_INDEX = 12
    const val MANUAL_INDEX = 14
    const val DETECT_INDEX = 15

    fun isManual(ctx: Context): Boolean = ShaderPreferences.getAspectRatioIndex(ctx) == MANUAL_INDEX
    fun isAutoDetect(ctx: Context): Boolean = ShaderPreferences.getAspectRatioIndex(ctx) == DETECT_INDEX

    // Caja de contenido que ha medido la detección de bandas negras (T07), por orientación. Solo vive en memoria: se vuelve a
    // medir en unos segundos. null = sin recorte (todavía no ha medido nada, o no hay bandas).
    @Volatile private var detectedPortrait: FloatArray? = null
    @Volatile private var detectedLandscape: FloatArray? = null

    /** Guarda la caja detectada; devuelve true si cambió algo. [box] null = sin recorte. */
    fun setDetectedBox(landscape: Boolean, box: FloatArray?): Boolean {
        val old = if (landscape) detectedLandscape else detectedPortrait
        val changed = when {
            old == null && box == null -> false
            old == null || box == null -> true
            else -> !old.contentEquals(box)
        }
        if (landscape) detectedLandscape = box else detectedPortrait = box
        return changed
    }

    /**
     * Olvida las cajas detectadas. Al girar la pantalla lo medido ya no vale (el contenido se recoloca) y el motor volverá a
     * empezar desde pantalla completa: así la primera disposición tras el giro ya es esa y no se recompila dos veces.
     */
    fun clearDetected() {
        detectedPortrait = null
        detectedLandscape = null
    }

    // Lados que pueden pintarse de negro fuera de la zona (máscara de bits, T08)
    const val BAR_LEFT = 1
    const val BAR_RIGHT = 2
    const val BAR_TOP = 4
    const val BAR_BOTTOM = 8
    const val BAR_ALL = BAR_LEFT or BAR_RIGHT or BAR_TOP or BAR_BOTTOM

    /**
     * Rectángulos que se pintan de negro: cada barra ocupa toda la pantalla a lo largo de su lado (izquierda y derecha, todo
     * el alto; arriba y abajo, todo el ancho). Sin zona no hay nada fuera, así que tampoco hay barras.
     */
    fun barRects(zone: Rect?, mask: Int, screenW: Int, screenH: Int): List<Rect> {
        if (zone == null || mask == 0) return emptyList()
        val out = ArrayList<Rect>(4)
        if (mask and BAR_LEFT != 0 && zone.left > 0) out.add(Rect(0, 0, zone.left, screenH))
        if (mask and BAR_RIGHT != 0 && zone.right < screenW) out.add(Rect(zone.right, 0, screenW, screenH))
        if (mask and BAR_TOP != 0 && zone.top > 0) out.add(Rect(0, 0, screenW, zone.top))
        if (mask and BAR_BOTTOM != 0 && zone.bottom < screenH) out.add(Rect(0, zone.bottom, screenW, screenH))
        return out
    }

    /**
     * Aspecto por defecto de cada perfil de resolución (índices del selector de MainActivity): los sistemas retro no tenían
     * píxeles cuadrados, p. ej. 256x224 (NES/SNES) se veía a 4:3 en la tele, no a 8:7. 0 = pantalla completa.
     */
    fun aspectForResolution(resIdx: Int, resW: Float, resH: Float): Float {
        ConsoleResolutions.byIndex(resIdx)?.let { return it.aspect } // consolas: ConsoleResolutions
        return when (resIdx) {
            ConsoleResolutions.INDEX_CUSTOM ->
                if (resW > 0f && resH > 0f) max(resW, resH) / min(resW, resH) else FULL_SCREEN // Custom: píxel cuadrado
            else -> FULL_SCREEN            // Auto (pantalla nativa) y Scale
        }
    }

    /**
     * Forma del píxel retro con la que se ve bien cada resolución (ancho/alto del píxel; 1 = cuadrado): la que resulta de
     * mostrar la rejilla con su aspecto por defecto (NES 256x224 a 4:3 → 7/6; Game Boy, GBA y Custom → 1). Las resoluciones
     * sin aspecto por defecto (Auto, Scale) cuentan como cuadradas.
     */
    fun referencePixelAspect(resIdx: Int, resW: Float, resH: Float): Float {
        val a = aspectForResolution(resIdx, resW, resH)
        if (a <= 0f || resW <= 0f || resH <= 0f) return 1f
        return a / (max(resW, resH) / min(resW, resH))
    }

    /**
     * Rejilla adaptada a la forma de la zona (modo "REJILLA ADAPTADA" del editor de zona manual): se mantienen las líneas de la
     * resolución en el lado corto de la zona y el lado largo crece o encoge para que el píxel conserve su forma de referencia
     * (cuadrado en Game Boy, GBA y Custom). Devuelve (largo, corto) como espera el motor, que la orienta según la zona; null si
     * la resolución es Auto (la rejilla ya es la de la zona) o no hay zona.
     */
    fun adaptGrid(resIdx: Int, resW: Float, resH: Float, zoneW: Int, zoneH: Int): Pair<Float, Float>? {
        if (resW <= 0f || resH <= 0f || zoneW <= 0 || zoneH <= 0) return null
        val vMin = min(resW, resH).roundToInt().coerceAtLeast(1)
        val zoneLong = max(zoneW, zoneH).toFloat()
        val zoneShort = min(zoneW, zoneH).toFloat()
        val ref = referencePixelAspect(resIdx, resW, resH)
        // Nunca por debajo del lado corto: el motor ordena la rejilla por tamaño (largo ≥ corto)
        val long = (vMin * (zoneLong / zoneShort) / ref).roundToInt().coerceAtLeast(vMin)
        return long.toFloat() to vMin.toFloat()
    }

    /** Rejilla adaptada solo si el selector es "Manual", el interruptor está activo y hay zona y resolución fija. */
    fun adaptedGrid(ctx: Context, zone: Rect?, screenW: Int, screenH: Int, output: Int = 0, second: Boolean = false): Pair<Float, Float>? {
        // La zona de la segunda pantalla (T17) cuenta como dibujada y usa siempre la rejilla A
        if ((!second && !usesManualZones(ctx)) || !ShaderPreferences.isGridAdapt(ctx) || zone == null) return null
        val (w, h) = if (output == 1 && !second) ShaderPreferences.getVirtualResolutionB(ctx) else ShaderPreferences.getVirtualResolution(ctx)
        return adaptGrid(ShaderPreferences.getVirtualResolutionIndex(ctx), w, h, zone.width(), zone.height())
    }

    /**
     * Qué está pasando con la rejilla en la zona actual, para mostrarlo al usuario: rejilla del shader, tamaño de la zona,
     * si la rejilla está adaptada y si el píxel sale deformado respecto a su forma de referencia.
     */
    class GridInfo(val gridW: Int, val gridH: Int, val zoneW: Int, val zoneH: Int,
                   val auto: Boolean, val adapted: Boolean, val distorted: Boolean)

    /**
     * Rejilla del shader dentro de una zona de [zoneW]x[zoneH] píxeles, orientada como la orienta el motor (el lado largo de
     * la rejilla va en el lado largo de la zona), con la resolución fija dada, adaptada o no. null si la resolución es Auto.
     */
    fun gridFor(resIdx: Int, resW: Float, resH: Float, zoneW: Int, zoneH: Int, adapt: Boolean): GridInfo? {
        if (resW <= 0f || resH <= 0f) return null
        val adaptedG = if (adapt) adaptGrid(resIdx, resW, resH, zoneW, zoneH) else null
        val gLong = (adaptedG?.first ?: max(resW, resH)).roundToInt()
        val gShort = (adaptedG?.second ?: min(resW, resH)).roundToInt()
        val gridW = if (zoneW >= zoneH) gLong else gShort
        val gridH = if (zoneW >= zoneH) gShort else gLong
        val pixW = zoneW.toFloat() / gridW
        val pixH = zoneH.toFloat() / gridH
        // En el marco largo/corto de la zona, igual que la referencia
        val pixAspect = if (zoneW >= zoneH) pixW / pixH else pixH / pixW
        val distorted = abs(pixAspect / referencePixelAspect(resIdx, resW, resH) - 1f) > 0.03f
        return GridInfo(gridW, gridH, zoneW, zoneH, auto = false, adapted = adaptedG != null, distorted = distorted)
    }

    fun gridInfo(ctx: Context, screenW: Int, screenH: Int): GridInfo {
        val rect = currentRect(ctx, screenW, screenH)
        val zw = rect?.width() ?: screenW
        val zh = rect?.height() ?: screenH
        val (rw, rh) = ShaderPreferences.getVirtualResolution(ctx)
        val adapt = usesManualZones(ctx) && ShaderPreferences.isGridAdapt(ctx) && rect != null
        return gridFor(ShaderPreferences.getVirtualResolutionIndex(ctx), rw, rh, zw, zh, adapt)
            ?: GridInfo(zw, zh, zw, zh, auto = true, adapted = false, distorted = false)
    }

    /** Texto de una línea con lo que hace el shader en la zona actual (pantalla principal y editor de zona). */
    fun describeGrid(ctx: Context, info: GridInfo): String {
        if (info.auto) return ctx.getString(R.string.grid_info_auto, info.zoneW, info.zoneH)
        fun f(v: Float) = if (v >= 10f || v == v.toInt().toFloat()) v.roundToInt().toString() else "%.1f".format(java.util.Locale.US, v)
        val base = ctx.getString(R.string.grid_info_fixed, info.gridW, info.gridH, info.zoneW, info.zoneH,
            f(info.zoneW.toFloat() / info.gridW), f(info.zoneH.toFloat() / info.gridH)) +
                (if (info.adapted) ctx.getString(R.string.grid_info_adapted_suffix) else "")
        return if (info.distorted) base + "\n" + ctx.getString(R.string.grid_info_distorted) else base // "⚠" no está en la fuente VGA
    }

    /** Tamaño real de la pantalla en la orientación actual (el mismo que el de la captura). */
    fun screenSize(windowManager: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** Aspecto efectivo (ancho/alto) según el selector y la resolución actuales. 0 = pantalla completa. */
    fun effectiveAspect(ctx: Context): Float {
        val idx = ShaderPreferences.getAspectRatioIndex(ctx).coerceIn(0, ASPECT_OPTIONS.size - 1)
        val ratio = ASPECT_OPTIONS[idx].ratio
        if (ratio != BY_RESOLUTION) return ratio
        val (w, h) = ShaderPreferences.getVirtualResolution(ctx)
        return aspectForResolution(ShaderPreferences.getVirtualResolutionIndex(ctx), w, h)
    }

    /**
     * Zona en píxeles de pantalla para un aspecto dado: el rectángulo más grande con esa forma, centrado. null = pantalla
     * completa (sin aspecto, o ya coincide con el de la pantalla).
     */
    fun rectForAspect(aspect: Float, screenW: Int, screenH: Int): Rect? {
        if (aspect <= 0f || screenW <= 0 || screenH <= 0) return null
        val screenAspect = screenW.toFloat() / screenH
        if (abs(aspect - screenAspect) < 0.002f) return null
        val w: Int
        val h: Int
        if (aspect > screenAspect) {            // más apaisada que la pantalla: ocupa todo el ancho
            w = screenW
            h = (screenW / aspect).roundToInt().coerceIn(1, screenH)
        } else {                                // más alta: ocupa todo el alto
            h = screenH
            w = (screenH * aspect).roundToInt().coerceIn(1, screenW)
        }
        val left = (screenW - w) / 2
        val top = (screenH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Zona actual en píxeles de pantalla (null = pantalla completa). En modo "Manual" es la zona guardada para la orientación
     * actual (una para vertical y otra para horizontal); si esa orientación no tiene, pantalla completa.
     */
    fun currentRect(ctx: Context, screenW: Int, screenH: Int): Rect? {
        if (isDual(ctx)) return dualRects(ctx, screenW, screenH).first // la detección de bandas no vale con dos zonas (P5)
        if (isAutoDetect(ctx)) {
            val box = (if (screenW >= screenH) detectedLandscape else detectedPortrait) ?: return null
            return rectFromFractions(box, screenW, screenH)
        }
        if (isManual(ctx)) {
            val f = ShaderPreferences.getManualZone(ctx, screenW >= screenH) ?: return null
            return rectFromFractions(f, screenW, screenH)
        }
        return rectForAspect(effectiveAspect(ctx), screenW, screenH)
    }

    /**
     * Zona en la segunda pantalla física (T17): la dibujada en su editor o, si no hay, la del aspecto del selector (el mismo
     * 4:3 que arriba, centrado en esa pantalla). null = pantalla completa.
     */
    fun zoneForSecond(ctx: Context, screenW: Int, screenH: Int): Rect? {
        val stored = ShaderPreferences.getSecondZone(ctx, screenW >= screenH)
        if (stored != null) {
            return rectFromFractions(stored, screenW, screenH)
                ?.takeUnless { it.left <= 0 && it.top <= 0 && it.right >= screenW && it.bottom >= screenH }
        }
        return rectForAspect(effectiveAspect(ctx), screenW, screenH)
    }

    // --- Modo dual (T16): dos zonas, A (la de arriba o la izquierda) y B ---

    fun isDual(ctx: Context): Boolean = ShaderPreferences.isDual(ctx)

    /** Las zonas salen de las preferencias (dibujadas o iniciales), no de un aspecto: modo "Manual" o dual. */
    fun usesManualZones(ctx: Context): Boolean = isManual(ctx) || isDual(ctx)

    /**
     * Forma inicial de las dos zonas según la resolución elegida: una encima de la otra en vertical y lado a lado en horizontal,
     * centradas y lo más grandes posible. Cada una con la forma de su pantalla: DS, dos de 4:3 iguales; 3DS, la A de 5:3 y la B,
     * de 4:3, más estrecha (a la misma escala 400x240 y 320x240). Sin aspecto propio (Auto, Scale, Custom), dos de 4:3.
     */
    fun defaultDualRects(ctx: Context, screenW: Int, screenH: Int): Pair<Rect, Rect> {
        val resIdx = ShaderPreferences.getVirtualResolutionIndex(ctx)
        val (resW, resH) = ShaderPreferences.getVirtualResolution(ctx)
        val entry = ConsoleResolutions.byIndex(resIdx)
        val aA = aspectForResolution(resIdx, resW, resH).takeIf { it > 0f } ?: (4f / 3f)
        var aB = aA
        var hB = 1f // alto de B respecto al de A, a la misma escala
        if (entry != null && entry.w2 > 0 && entry.h2 > 0) {
            aB = entry.w2.toFloat() / entry.h2
            hB = entry.h2.toFloat() / entry.h
        }
        val wA = aA; val hA = 1f
        val wB = aB * hB
        val landscape = screenW >= screenH
        val gap = (if (landscape) screenW else screenH) * 0.02f
        return if (!landscape) {
            val totalW = max(wA, wB)
            val totalH = hA + hB
            val s = min(screenW * 0.98f / totalW, (screenH * 0.96f - gap) / totalH)
            val top = (screenH - totalH * s - gap) / 2f
            val a = centeredRect(screenW, wA * s, hA * s, top = top)
            val b = centeredRect(screenW, wB * s, hB * s, top = top + hA * s + gap)
            a to b
        } else {
            val totalW = wA + wB
            val totalH = max(hA, hB)
            val s = min((screenW * 0.96f - gap) / totalW, screenH * 0.96f / totalH)
            val left = (screenW - totalW * s - gap) / 2f
            val a = Rect(left.roundToInt(), ((screenH - hA * s) / 2f).roundToInt(), (left + wA * s).roundToInt(), ((screenH + hA * s) / 2f).roundToInt())
            val bl = left + wA * s + gap
            val b = Rect(bl.roundToInt(), ((screenH - hB * s) / 2f).roundToInt(), (bl + wB * s).roundToInt(), ((screenH + hB * s) / 2f).roundToInt())
            a to b
        }
    }

    private fun centeredRect(screenW: Int, w: Float, h: Float, top: Float): Rect {
        val left = (screenW - w) / 2f
        return Rect(left.roundToInt(), top.roundToInt(), (left + w).roundToInt(), (top + h).roundToInt())
    }

    /**
     * Las dos zonas del modo dual en píxeles de pantalla. Una zona sin dibujar usa la forma inicial; si lo guardado se solapa
     * (p. ej. la resolución cambió), las dos vuelven a la forma inicial.
     */
    fun dualRects(ctx: Context, screenW: Int, screenH: Int): Pair<Rect, Rect> {
        val landscape = screenW >= screenH
        val defaults = defaultDualRects(ctx, screenW, screenH)
        fun fromStore(f: FloatArray?, fallback: Rect): Rect =
            if (f == null) fallback else rectFromFractions(f, screenW, screenH) ?: Rect(0, 0, screenW, screenH)
        val a = fromStore(ShaderPreferences.getManualZone(ctx, landscape), defaults.first)
        val b = fromStore(ShaderPreferences.getManualZoneB(ctx, landscape), defaults.second)
        return if (Rect.intersects(a, b)) defaults else a to b
    }

    /** Zonas actuales en orden de salida (0 = A, 1 = B): una sola salvo en modo dual. Vacío si es pantalla completa. */
    fun currentRects(ctx: Context, screenW: Int, screenH: Int): List<Rect> {
        if (isDual(ctx)) return dualRects(ctx, screenW, screenH).toList()
        return listOfNotNull(currentRect(ctx, screenW, screenH))
    }

    /**
     * Fondo negro del modo dual: todo lo que queda fuera de las dos zonas, como rectángulos (la pantalla menos A y B). Es una sola
     * opción (cualquier lado marcado en BARRAS la activa); no hay lados por separado con dos rectángulos.
     */
    fun dualBarRects(zones: List<Rect>, mask: Int, screenW: Int, screenH: Int): List<Rect> {
        if (mask == 0 || zones.isEmpty()) return emptyList()
        val ys = (listOf(0, screenH) + zones.flatMap { listOf(it.top, it.bottom) }).filter { it in 0..screenH }.distinct().sorted()
        val out = ArrayList<Rect>()
        for (i in 0 until ys.size - 1) {
            val y0 = ys[i]; val y1 = ys[i + 1]
            // Tramos horizontales cubiertos por alguna zona en esta franja, ordenados
            val covered = zones.filter { it.top < y1 && it.bottom > y0 }.map { it.left to it.right }.sortedBy { it.first }
            var x = 0
            for ((l, r) in covered) {
                if (l > x) out.add(Rect(x, y0, l, y1))
                x = max(x, r)
            }
            if (x < screenW) out.add(Rect(x, y0, screenW, y1))
        }
        return out
    }

    /** Fracciones [izq, arriba, der, abajo] -> rectángulo en píxeles. null si es toda la pantalla o no es válido. */
    fun rectFromFractions(f: FloatArray, screenW: Int, screenH: Int): Rect? {
        if (f.size != 4 || screenW <= 0 || screenH <= 0) return null
        val l = (f[0] * screenW).roundToInt().coerceIn(0, screenW - 1)
        val t = (f[1] * screenH).roundToInt().coerceIn(0, screenH - 1)
        val r = (f[2] * screenW).roundToInt().coerceIn(l + 1, screenW)
        val b = (f[3] * screenH).roundToInt().coerceIn(t + 1, screenH)
        if (l == 0 && t == 0 && r == screenW && b == screenH) return null
        return Rect(l, t, r, b)
    }

    /** Rectángulo en píxeles -> fracciones [izq, arriba, der, abajo] (valen en otro dispositivo o resolución). */
    fun fractionsOf(rect: Rect, screenW: Int, screenH: Int): FloatArray = floatArrayOf(
        rect.left.toFloat() / screenW, rect.top.toFloat() / screenH,
        rect.right.toFloat() / screenW, rect.bottom.toFloat() / screenH
    )

    fun formatFractions(f: FloatArray): String = f.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    /** Lee "l,t,r,b"; null si no es una zona válida (0 <= l < r <= 1, 0 <= t < b <= 1). */
    fun parseFractions(s: String): FloatArray? {
        val p = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size != 4) return null
        if (p[0] < 0f || p[1] < 0f || p[2] > 1f || p[3] > 1f || p[0] >= p[2] || p[1] >= p[3]) return null
        return floatArrayOf(p[0], p[1], p[2], p[3])
    }
}
