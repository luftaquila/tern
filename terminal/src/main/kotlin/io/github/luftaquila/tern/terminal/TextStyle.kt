// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * Cell style packed into a Long. The exact bit layout is private to this module; renderers
 * only use [flags] and [TerminalEmulator.resolveColors].
 *
 * CONTRACT (implemented by the terminal module):
 *  - [flags] returns the OR of the attribute constants below.
 *  - [DEFAULT] is the style of a freshly erased cell with default colors and no attributes.
 *
 * Layout: bits 0-9 attribute flags, bit 10/11 "fg/bg is RGB", bits 12-35 background,
 * bits 36-59 foreground. A color is either a palette index (0-255, [COLOR_DEFAULT_FG],
 * [COLOR_DEFAULT_BG]) or a 24-bit RGB value.
 */
object TextStyle {
    const val BOLD = 1 shl 0
    const val FAINT = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val INVISIBLE = 1 shl 6
    const val STRIKETHROUGH = 1 shl 7
    const val DOUBLE_UNDERLINE = 1 shl 8
    const val CURLY_UNDERLINE = 1 shl 9

    /** Palette index meaning "the scheme's default foreground". */
    const val COLOR_DEFAULT_FG = 256

    /** Palette index meaning "the scheme's default background". */
    const val COLOR_DEFAULT_BG = 257

    /** Any underline variant. */
    internal const val ANY_UNDERLINE = UNDERLINE or DOUBLE_UNDERLINE or CURLY_UNDERLINE

    internal const val FLAGS_MASK = 0x3FF

    /**
     * Marker bit for an internal color value holding 24-bit RGB rather than a palette index
     * (used by [encode] / [foreground] / [background]).
     */
    internal const val RGB_BIT = 0x1000000

    private const val FG_RGB = 1L shl 10
    private const val BG_RGB = 1L shl 11
    private const val BG_SHIFT = 12
    private const val FG_SHIFT = 36
    private const val COLOR_MASK = 0xFFFFFFL

    private val DEFAULT_STYLE: Long = encode(COLOR_DEFAULT_FG, COLOR_DEFAULT_BG, 0)

    val DEFAULT: Long get() = DEFAULT_STYLE

    fun flags(style: Long): Int = (style and FLAGS_MASK.toLong()).toInt()

    /** @param fg palette index or ([RGB_BIT] or 0xRRGGBB); same for [bg]. */
    internal fun encode(fg: Int, bg: Int, flags: Int): Long {
        var s = (flags and FLAGS_MASK).toLong()
        if (fg and RGB_BIT != 0) s = s or FG_RGB
        if (bg and RGB_BIT != 0) s = s or BG_RGB
        s = s or ((bg.toLong() and COLOR_MASK) shl BG_SHIFT)
        s = s or ((fg.toLong() and COLOR_MASK) shl FG_SHIFT)
        return s
    }

    /** Internal color value of the foreground (palette index or [RGB_BIT] | rgb). */
    internal fun foreground(style: Long): Int {
        val v = ((style ushr FG_SHIFT) and COLOR_MASK).toInt()
        return if (style and FG_RGB != 0L) v or RGB_BIT else v
    }

    /** Internal color value of the background (palette index or [RGB_BIT] | rgb). */
    internal fun background(style: Long): Int {
        val v = ((style ushr BG_SHIFT) and COLOR_MASK).toInt()
        return if (style and BG_RGB != 0L) v or RGB_BIT else v
    }

    internal fun withFlags(style: Long, flags: Int): Long =
        (style and FLAGS_MASK.toLong().inv()) or (flags and FLAGS_MASK).toLong()

    internal fun withForeground(style: Long, fg: Int): Long =
        encode(fg, background(style), flags(style))

    internal fun withBackground(style: Long, bg: Int): Long =
        encode(foreground(style), bg, flags(style))
}
