// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * A terminal color scheme. All colors are opaque ARGB ints (0xFFRRGGBB), except [selection]
 * which may carry alpha because it is drawn as an overlay.
 *
 * [ansi] holds the 16 base colors (0-7 normal, 8-15 bright). Indices 16-255 of the xterm
 * palette are derived (6x6x6 cube + 24 step gray ramp) by the emulator.
 */
data class ColorScheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val cursorText: Int,
    val selection: Int,
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == 16) { "ansi must have 16 colors" }
    }
}

object ColorSchemes {
    private fun c(rgb: Long): Int = (0xFF000000L or rgb).toInt()

    val DEFAULT_DARK = ColorScheme(
        id = "default-dark",
        name = "Default Dark",
        isDark = true,
        foreground = c(0xD8DEE9),
        background = c(0x0F1218),
        cursor = c(0x8CA6FF),
        cursorText = c(0x0F1218),
        selection = 0x668CA6FF.toInt(),
        ansi = listOf(
            c(0x1C2129), c(0xF7768E), c(0x9ECE6A), c(0xE0AF68),
            c(0x7AA2F7), c(0xBB9AF7), c(0x7DCFFF), c(0xC0CAF5),
            c(0x4B5263), c(0xFF8FA3), c(0xB9F27C), c(0xFFC777),
            c(0x9AB8FF), c(0xD0B3FF), c(0xA6E3FF), c(0xFFFFFF),
        ),
    )

    val DEFAULT_LIGHT = ColorScheme(
        id = "default-light",
        name = "Default Light",
        isDark = false,
        foreground = c(0x24292F),
        background = c(0xFAFBFC),
        cursor = c(0x4C6FFF),
        cursorText = c(0xFFFFFF),
        selection = 0x554C6FFF,
        ansi = listOf(
            c(0x24292F), c(0xCF222E), c(0x116329), c(0x9A6700),
            c(0x0969DA), c(0x8250DF), c(0x1B7C83), c(0x6E7781),
            c(0x57606A), c(0xA40E26), c(0x1A7F37), c(0x7D4E00),
            c(0x218BFF), c(0xA475F9), c(0x3192AA), c(0x8C959F),
        ),
    )

    val SOLARIZED_DARK = ColorScheme(
        id = "solarized-dark",
        name = "Solarized Dark",
        isDark = true,
        foreground = c(0x839496),
        background = c(0x002B36),
        cursor = c(0x93A1A1),
        cursorText = c(0x002B36),
        selection = 0x66586E75,
        ansi = listOf(
            c(0x073642), c(0xDC322F), c(0x859900), c(0xB58900),
            c(0x268BD2), c(0xD33682), c(0x2AA198), c(0xEEE8D5),
            c(0x002B36), c(0xCB4B16), c(0x586E75), c(0x657B83),
            c(0x839496), c(0x6C71C4), c(0x93A1A1), c(0xFDF6E3),
        ),
    )

    val SOLARIZED_LIGHT = ColorScheme(
        id = "solarized-light",
        name = "Solarized Light",
        isDark = false,
        foreground = c(0x657B83),
        background = c(0xFDF6E3),
        cursor = c(0x586E75),
        cursorText = c(0xFDF6E3),
        selection = 0x5593A1A1,
        ansi = listOf(
            c(0x073642), c(0xDC322F), c(0x859900), c(0xB58900),
            c(0x268BD2), c(0xD33682), c(0x2AA198), c(0xEEE8D5),
            c(0x002B36), c(0xCB4B16), c(0x586E75), c(0x657B83),
            c(0x839496), c(0x6C71C4), c(0x93A1A1), c(0xFDF6E3),
        ),
    )

    val DRACULA = ColorScheme(
        id = "dracula",
        name = "Dracula",
        isDark = true,
        foreground = c(0xF8F8F2),
        background = c(0x282A36),
        cursor = c(0xF8F8F2),
        cursorText = c(0x282A36),
        selection = 0x8844475A.toInt(),
        ansi = listOf(
            c(0x21222C), c(0xFF5555), c(0x50FA7B), c(0xF1FA8C),
            c(0xBD93F9), c(0xFF79C6), c(0x8BE9FD), c(0xF8F8F2),
            c(0x6272A4), c(0xFF6E6E), c(0x69FF94), c(0xFFFFA5),
            c(0xD6ACFF), c(0xFF92DF), c(0xA4FFFF), c(0xFFFFFF),
        ),
    )

    val NORD = ColorScheme(
        id = "nord",
        name = "Nord",
        isDark = true,
        foreground = c(0xD8DEE9),
        background = c(0x2E3440),
        cursor = c(0xD8DEE9),
        cursorText = c(0x2E3440),
        selection = 0x77434C5E,
        ansi = listOf(
            c(0x3B4252), c(0xBF616A), c(0xA3BE8C), c(0xEBCB8B),
            c(0x81A1C1), c(0xB48EAD), c(0x88C0D0), c(0xE5E9F0),
            c(0x4C566A), c(0xBF616A), c(0xA3BE8C), c(0xEBCB8B),
            c(0x81A1C1), c(0xB48EAD), c(0x8FBCBB), c(0xECEFF4),
        ),
    )

    val GRUVBOX_DARK = ColorScheme(
        id = "gruvbox-dark",
        name = "Gruvbox Dark",
        isDark = true,
        foreground = c(0xEBDBB2),
        background = c(0x282828),
        cursor = c(0xEBDBB2),
        cursorText = c(0x282828),
        selection = 0x77665C54,
        ansi = listOf(
            c(0x282828), c(0xCC241D), c(0x98971A), c(0xD79921),
            c(0x458588), c(0xB16286), c(0x689D6A), c(0xA89984),
            c(0x928374), c(0xFB4934), c(0xB8BB26), c(0xFABD2F),
            c(0x83A598), c(0xD3869B), c(0x8EC07C), c(0xEBDBB2),
        ),
    )

    val ALL: List<ColorScheme> = listOf(
        DEFAULT_DARK, DEFAULT_LIGHT, SOLARIZED_DARK, SOLARIZED_LIGHT, DRACULA, NORD, GRUVBOX_DARK,
    )

    val DEFAULT: ColorScheme = DEFAULT_DARK

    fun byId(id: String?): ColorScheme = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
