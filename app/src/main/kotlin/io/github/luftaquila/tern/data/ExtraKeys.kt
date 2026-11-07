// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Keys with a fixed meaning that can be placed on the extra-keys bar. */
@Serializable
enum class SpecialKey(val repeatable: Boolean = false) {
    /** KEYBOARD shows or hides the keyboard; SNIPPETS opens the list of snippets to send. */
    KEYBOARD, PASTE, SNIPPETS,
    ESC, TAB, ENTER, BACKSPACE(true),
    CTRL, ALT, SHIFT,
    LEFT(true), DOWN(true), UP(true), RIGHT(true),
    HOME, END, PAGE_UP(true), PAGE_DOWN(true), INSERT, DELETE(true),
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    CTRL_C, CTRL_D, CTRL_Z, CTRL_L, CTRL_R, CTRL_A, CTRL_E, CTRL_U, CTRL_W, CTRL_K,
    ;

    /** For CTRL_x keys: the letter sent with Ctrl. */
    val ctrlLetter: Char? get() = if (name.startsWith("CTRL_")) name.last().lowercaseChar() else null

    val isModifier: Boolean get() = this == CTRL || this == ALT || this == SHIFT
}

/** One key on the extra-keys bar. */
@Serializable
sealed interface ExtraKeyDef {
    @Serializable
    @SerialName("special")
    data class Special(val key: SpecialKey) : ExtraKeyDef

    /**
     * Sends [text]: typed when plain (sticky CTRL/ALT apply to its first character), as-is when it
     * has control characters (Enter, ESC, ...). One character, or one of [ExtraKeysConfig.MACROS];
     * other text keys are snippets ([SnippetRef]), and older layouts' ones become snippets on
     * load ([ExtraKeysConfig.macrosAsSnippets]).
     */
    @Serializable
    @SerialName("text")
    data class Text(val text: String, val label: String? = null) : ExtraKeyDef {
        /** A key with its own label or more than one character: a snippet unless built in. */
        val isMacro: Boolean get() = !label.isNullOrBlank() || text.codePointCount(0, text.length) > 1
    }

    /** Sends the [Snippet] with this [id], labelled with its name. */
    @Serializable
    @SerialName("snippet")
    data class SnippetRef(val id: String) : ExtraKeyDef
}

/** Rows of the extra-keys bar: row 0 is always shown, the others when the bar is expanded. */
@Serializable
data class ExtraKeysConfig(val rows: List<List<ExtraKeyDef>>) {
    companion object {
        private fun s(k: SpecialKey) = ExtraKeyDef.Special(k)
        private fun t(text: String) = ExtraKeyDef.Text(text)

        /** One row: ESC, CTRL, ALT, ← ↓ ↑ →, paste. */
        val DEFAULT = ExtraKeysConfig(
            listOf(
                listOf(
                    s(SpecialKey.ESC), s(SpecialKey.CTRL), s(SpecialKey.ALT),
                    s(SpecialKey.LEFT), s(SpecialKey.DOWN), s(SpecialKey.UP), s(SpecialKey.RIGHT),
                    s(SpecialKey.PASTE),
                ),
            ),
        )

        /** The earlier built-in layout: settings still holding it unchanged get [DEFAULT] (see [migrate]). */
        internal val PREVIOUS_DEFAULT = ExtraKeysConfig(
            listOf(
                listOf(
                    s(SpecialKey.KEYBOARD), s(SpecialKey.ESC), s(SpecialKey.TAB), s(SpecialKey.CTRL), s(SpecialKey.ALT),
                    s(SpecialKey.LEFT), s(SpecialKey.DOWN), s(SpecialKey.UP), s(SpecialKey.RIGHT),
                    t("/"), t("-"), t("|"), t("~"),
                    s(SpecialKey.HOME), s(SpecialKey.END), s(SpecialKey.PAGE_UP), s(SpecialKey.PAGE_DOWN),
                ),
                listOf(
                    s(SpecialKey.SHIFT), s(SpecialKey.CTRL_C), s(SpecialKey.CTRL_D), s(SpecialKey.CTRL_Z),
                    s(SpecialKey.CTRL_L), s(SpecialKey.CTRL_R), t("_"), t(":"), t("$"), t("`"), t("\\"),
                    t("*"), t("&"), t("<"), t(">"), s(SpecialKey.DELETE), s(SpecialKey.INSERT),
                ),
                listOf(
                    s(SpecialKey.F1), s(SpecialKey.F2), s(SpecialKey.F3), s(SpecialKey.F4), s(SpecialKey.F5), s(SpecialKey.F6),
                    s(SpecialKey.F7), s(SpecialKey.F8), s(SpecialKey.F9), s(SpecialKey.F10), s(SpecialKey.F11), s(SpecialKey.F12),
                ),
            ),
        )

        /** [config], or [DEFAULT] if it is the untouched earlier default layout. */
        fun migrate(config: ExtraKeysConfig): ExtraKeysConfig = if (config == PREVIOUS_DEFAULT) DEFAULT else config

        /** Symbols offered in the key picker. */
        val SYMBOLS: List<String> = listOf(
            "/", "-", "_", "|", "~", "`", "\\", ":", ";", "$", "*", "&", "#", "@", "!", "?", "%", "^",
            "=", "+", "<", ">", "{", "}", "[", "]", "(", ")", "'", "\"", ",", ".",
        )

        /** Built-in command keys offered in the key picker; they stay text keys, not snippets. */
        val MACROS: List<ExtraKeyDef.Text> = listOf(
            ExtraKeyDef.Text("\u001b:wq\r", ":wq"),
            ExtraKeyDef.Text("\u001b:q!\r", ":q!"),
            ExtraKeyDef.Text("\u0002"), // Ctrl-B, the tmux prefix
            ExtraKeyDef.Text("cd ..\r", "cd .."),
            ExtraKeyDef.Text("exit\r", "exit"),
        )
    }

    fun hasSnippet(id: String): Boolean = rows.any { row -> row.any { it is ExtraKeyDef.SnippetRef && it.id == id } }

    /** This layout with a key for snippet [id] at the end of the first row. */
    fun withSnippet(id: String): ExtraKeysConfig {
        val key = ExtraKeyDef.SnippetRef(id)
        return ExtraKeysConfig(if (rows.isEmpty()) listOf(listOf(key)) else rows.mapIndexed { i, row -> if (i == 0) row + key else row })
    }

    /** This layout without the keys for snippet [id]. */
    fun withoutSnippet(id: String): ExtraKeysConfig =
        ExtraKeysConfig(rows.map { row -> row.filterNot { it is ExtraKeyDef.SnippetRef && it.id == id } })

    /**
     * This layout with each macro key ([ExtraKeyDef.Text.isMacro], other than [MACROS]) turned into
     * a key for the snippet with the same name and text: one of [snippets], or a new one made with
     * [newId]. Returns the layout and the new snippets.
     */
    fun macrosAsSnippets(snippets: List<Snippet>, newId: () -> String = { UUID.randomUUID().toString() }): Pair<ExtraKeysConfig, List<Snippet>> {
        val added = ArrayList<Snippet>()
        val converted = rows.map { row ->
            row.map { key ->
                if (key !is ExtraKeyDef.Text || !key.isMacro || key in MACROS) {
                    key
                } else {
                    val name = key.label?.trim().orEmpty()
                    val snippet = (snippets + added).firstOrNull { it.name == name && it.text == key.text }
                        ?: Snippet(newId(), name, key.text).also(added::add)
                    ExtraKeyDef.SnippetRef(snippet.id)
                }
            }
        }
        return ExtraKeysConfig(converted) to added
    }
}

/**
 * Escape syntax for custom key text: `\n` or `\r` = Enter (CR), `\t` = Tab, `\e` = ESC,
 * `\\` = backslash, `\xHH` = byte/char code, `\uXXXX` = Unicode code point.
 */
object KeyTextEscapes {
    fun parse(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '\\' || i + 1 >= input.length) {
                out.append(c); i++; continue
            }
            when (val n = input[i + 1]) {
                'n', 'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'e' -> { out.append('\u001b'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'x' -> {
                    val hex = input.substring(i + 2, minOf(i + 4, input.length))
                    val v = hex.toIntOrNull(16)
                    if (hex.length == 2 && v != null) { out.append(v.toChar()); i += 4 } else { out.append('\\').append(n); i += 2 }
                }
                'u' -> {
                    val hex = input.substring(i + 2, minOf(i + 6, input.length))
                    val v = hex.toIntOrNull(16)
                    if (hex.length == 4 && v != null) { out.appendCodePoint(v); i += 6 } else { out.append('\\').append(n); i += 2 }
                }
                else -> { out.append('\\').append(n); i += 2 }
            }
        }
        return out.toString()
    }

    fun format(text: String): String {
        val out = StringBuilder()
        for (c in text) {
            when {
                c == '\r' || c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                c == '\u001b' -> out.append("\\e")
                c == '\\' -> out.append("\\\\")
                c < ' ' || c == '\u007f' -> out.append("\\x").append(String.format("%02x", c.code))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** True if [text] contains control characters, i.e. it must be sent raw rather than typed. */
    fun isRaw(text: String): Boolean = text.any { it < ' ' || it == '\u007f' }

    /** [text] for a key or list label: Enter, Tab and ESC as symbols, other control characters as ^X. */
    fun display(text: String): String = buildString {
        for (c in text) {
            append(
                when (c) {
                    '\r', '\n' -> "⏎"
                    '\t' -> "⇥"
                    '\u001b' -> "⎋"
                    else -> if (c < ' ') "^" + (c.code + 64).toChar() else c.toString()
                },
            )
        }
    }
}
