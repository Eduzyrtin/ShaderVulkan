package com.sanabria.shadervulkan.model

import android.content.Context
import androidx.annotation.StringRes
import com.sanabria.shadervulkan.R

/**
 * Resoluciones internas de las consolas y ordenadores clásicos para CONTENT RES (la rejilla de píxeles del shader).
 *
 * CONTENT RES se reparte en dos listas: la de modo (AUTO, SCALE, CUSTOM, CONSOLE) y esta, la de consolas. El índice de cada
 * entrada es lo que se guarda en las preferencias y en los perfiles (`resIdx`), así que **no se puede cambiar ni reutilizar**
 * (los perfiles ya guardados apuntan a él). Reservados: 0 = Auto, 7 = Scale, 8 = Custom. Los índices 1–6 son los de las seis
 * resoluciones originales; las nuevas empiezan en 20. Para añadir una consola, usar el siguiente índice libre.
 *
 * [aspect] es el aspecto con el que se veía de verdad (ancho/alto de la imagen en la tele): los sistemas retro no tenían
 * píxeles cuadrados, p. ej. 256x224 se veía a 4:3, no a 8:7. Lo usa la zona del shader en modo "Auto (según resolución)".
 */
object ConsoleResolutions {

    const val INDEX_AUTO = 0
    const val INDEX_SCALE = 7
    const val INDEX_CUSTOM = 8

    /** Consola que se propone al pasar a la lista de consolas por primera vez: NES / SNES. */
    const val DEFAULT_INDEX = 3

    /**
     * [w2]x[h2] > 0: consola con dos pantallas de resolución distinta (3DS, T16). En el modo dual la zona A usa [w]x[h] y la zona B
     * usa [w2]x[h2]; sin modo dual solo cuenta la primera. Con 0, las dos zonas usan la misma rejilla (DS).
     */
    class Entry(val index: Int, val maker: String, val name: String, val w: Int, val h: Int, val aspect: Float,
                val w2: Int = 0, val h2: Int = 0,
                /** Matiz del nombre que sí se traduce ("alta", "pantalla superior"...); 0 = ninguno. Marcas y modelos no se traducen. */
                @StringRes val qualifier: Int = 0) {
        private val size: String get() = if (w2 > 0 && h2 > 0) "${w}x$h + ${w2}x$h2" else "${w}x$h"
        private fun fullName(ctx: Context) = if (qualifier != 0) "$name (${ctx.getString(qualifier)})" else name

        /** Texto de la lista desplegable: con el fabricante delante, salvo que el nombre ya lo lleve ("Atari 2600"). */
        fun label(ctx: Context): String = fullName(ctx).let { (if (it.startsWith(maker)) it else "$maker · $it") + " ($size)" }

        /** Texto del selector cerrado: más corto, para que quepa la resolución. */
        fun shortLabel(ctx: Context): String = "${fullName(ctx)} ($size)"
    }

    private const val R43 = 4f / 3f
    private const val R169 = 16f / 9f

    /** En el orden en que se enseñan: por fabricante y, dentro de cada uno, de menor a mayor resolución. */
    val ALL: List<Entry> = listOf(
        Entry(76, "Amstrad", "Amstrad CPC", 320, 200, R43),
        Entry(68, "Apple", "Apple II", 280, 192, R43),
        Entry(5, "Arcade", "Capcom CPS-1 / CPS-2 / CPS-3", 384, 224, R43),
        Entry(25, "Arcade", "Konami / Namco / Irem", 288, 224, R43),
        Entry(26, "Arcade", "Midway / Atari", 336, 240, R43),
        Entry(20, "Atari", "Atari 2600", 160, 192, R43),
        Entry(22, "Atari", "Atari Lynx", 160, 102, 160f / 102f),
        Entry(23, "Atari", "Atari ST", 320, 200, R43, qualifier = R.string.res_q_low),
        Entry(21, "Atari", "Atari 7800 / Jaguar", 320, 240, R43),
        Entry(24, "Bandai", "WonderSwan / Color", 224, 144, 14f / 9f),
        Entry(30, "Coleco", "ColecoVision", 256, 192, R43),
        Entry(32, "Commodore", "Commodore 64", 320, 200, R43),
        Entry(33, "Commodore", "Amiga", 320, 256, R43),
        Entry(34, "Commodore", "Amiga", 640, 512, R43, qualifier = R.string.res_q_hires),
        Entry(31, "Mattel", "Intellivision", 160, 192, R43),
        Entry(36, "Microsoft", "Xbox", 640, 480, R43),
        Entry(37, "Microsoft", "Xbox 360", 1280, 720, R169),
        Entry(66, "MSX", "MSX / MSX2", 256, 192, R43),
        Entry(40, "NEC", "PC-98", 640, 400, R43),
        Entry(38, "NEC", "PC Engine / TurboGrafx-16", 256, 240, R43),
        Entry(48, "Nintendo", "Pokémon Mini", 96, 64, 3f / 2f),
        Entry(1, "Nintendo", "Game Boy / Game Boy Color", 160, 144, 10f / 9f),
        Entry(2, "Nintendo", "Game Boy Advance", 240, 160, 3f / 2f),
        Entry(44, "Nintendo", "DS", 256, 192, R43, qualifier = R.string.res_q_each),
        Entry(3, "Nintendo", "NES / SNES", 256, 224, R43),
        Entry(41, "Nintendo", "NES / SNES (PAL)", 256, 240, R43),
        Entry(46, "Nintendo", "3DS", 320, 240, R43, qualifier = R.string.res_q_bottom),
        Entry(52, "Nintendo", "Nintendo 64", 320, 240, R43),
        Entry(43, "Nintendo", "Virtual Boy", 384, 224, 12f / 7f),
        Entry(45, "Nintendo", "3DS", 400, 240, 5f / 3f, qualifier = R.string.res_q_top),
        Entry(80, "Nintendo", "3DS", 400, 240, 5f / 3f, 320, 240, qualifier = R.string.res_q_both), // modo dual (T16): A 400x240, B 320x240
        Entry(42, "Nintendo", "SNES", 512, 448, R43, qualifier = R.string.res_q_high),
        Entry(47, "Nintendo", "GameCube / Wii", 640, 480, R43),
        Entry(51, "Nintendo", "Wii U GamePad", 854, 480, R169),
        Entry(49, "Nintendo", "Switch / Wii U (720p)", 1280, 720, R169),
        Entry(50, "Nintendo", "Switch / Wii U (1080p)", 1920, 1080, R169),
        Entry(63, "Panasonic", "3DO", 320, 240, R43),
        Entry(64, "Philips", "CD-i", 384, 280, R43),
        Entry(69, "PC", "DOS (VGA 13h)", 320, 200, R43),
        Entry(70, "PC", "VGA", 640, 480, R43),
        Entry(54, "Sega", "Master System / SG-1000", 256, 192, R43),
        Entry(55, "Sega", "Game Gear", 160, 144, 10f / 9f),
        Entry(6, "Sega", "Genesis / Mega Drive / Sega CD / 32X", 320, 224, R43),
        Entry(56, "Sega", "Genesis / Mega Drive (PAL)", 320, 240, R43),
        Entry(53, "Sega", "Saturn", 320, 240, R43),
        Entry(57, "Sega", "Dreamcast / Naomi", 640, 480, R43),
        Entry(65, "Sinclair", "ZX Spectrum", 256, 192, R43),
        Entry(28, "SNK", "Neo Geo AES / MVS / CD", 320, 224, R43),
        Entry(29, "SNK", "Neo Geo Pocket / Color", 160, 152, 160f / 152f),
        Entry(4, "Sony", "PlayStation", 320, 240, R43),
        Entry(59, "Sony", "PlayStation", 640, 480, R43, qualifier = R.string.res_q_high),
        Entry(60, "Sony", "PlayStation 2", 640, 448, R43),
        Entry(61, "Sony", "PSP", 480, 272, R169),
        Entry(62, "Sony", "PS Vita", 960, 544, R169),
        Entry(71, "Watara", "Supervision", 160, 160, 1f),
        Entry(73, "HD", "480p", 854, 480, R169),
        Entry(74, "HD", "720p", 1280, 720, R169),
        Entry(75, "HD", "1080p", 1920, 1080, R169)
    )

    private val byIndex: Map<Int, Entry> = ALL.associateBy { it.index }

    fun byIndex(index: Int): Entry? = byIndex[index]

    /** true si el índice guardado es una consola (no Auto, Scale ni Custom). */
    fun isConsole(index: Int): Boolean = byIndex.containsKey(index)

    /** Posición de la consola en [ALL] (para el selector), o -1. */
    fun positionOf(index: Int): Int = ALL.indexOfFirst { it.index == index }
}
