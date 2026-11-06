// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * One line of the screen or scrollback. Read-only view for renderers; mutation is internal.
 *
 * CONTRACT:
 *  - [getCodePoint] returns 0 for an empty (never written / erased) cell, which renders as a
 *    blank cell with that cell's background, and [WIDE_SPACER] for the right half of a
 *    double-width character (the left half holds the code point).
 *  - [getCombining] returns zero-width code points (combining marks, Hangul conjoining
 *    medial/final jamo, ZWJ sequences, variation selectors) attached to the cell's base
 *    character, or null.
 *  - Columns >= [columns] must not be accessed by callers.
 */
class TerminalRow internal constructor(columns: Int) {
    var columns: Int = columns
        internal set

    /** True if the text of this line continues on the next line (soft wrap by autowrap). */
    var isWrapped: Boolean = false
        internal set

    internal var text: IntArray = IntArray(columns)
    internal var styles: LongArray = LongArray(columns) { TextStyle.DEFAULT }

    /** Lazily allocated; index = column. */
    internal var combining: Array<String?>? = null

    fun getCodePoint(column: Int): Int = text[column]

    fun getCombining(column: Int): String? = combining?.get(column)

    fun getStyle(column: Int): Long = styles[column]

    /** True when the cell at [column] starts a double-width character. */
    fun isWide(column: Int): Boolean =
        column + 1 < columns && text[column + 1] == WIDE_SPACER && text[column] != WIDE_SPACER

    // ---- internal mutation ---------------------------------------------------------------

    internal fun clear(style: Long) {
        text.fill(0)
        styles.fill(style)
        combining = null
        isWrapped = false
    }

    /** Blank cells [from, to) with [style]. Does not fix up wide-char halves. */
    internal fun erase(from: Int, to: Int, style: Long) {
        if (from >= to) return
        text.fill(0, from, to)
        styles.fill(style, from, to)
        val c = combining
        if (c != null) c.fill(null, from, to)
    }

    internal fun setCell(column: Int, codePoint: Int, style: Long) {
        text[column] = codePoint
        styles[column] = style
        val c = combining
        if (c != null) c[column] = null
    }

    internal fun appendCombining(column: Int, codePoint: Int) {
        var c = combining
        if (c == null) {
            c = arrayOfNulls(columns)
            combining = c
        }
        val prev = c[column]
        val add = String(Character.toChars(codePoint))
        // Bound pathological input (e.g. thousands of combining marks on one cell).
        if (prev == null) c[column] = add else if (prev.length < 32) c[column] = prev + add
    }

    /** Copy cells [from, from+len) of [src] to [dest, dest+len) of this row. */
    internal fun copyFrom(src: TerminalRow, from: Int, dest: Int, len: Int) {
        if (len <= 0) return
        System.arraycopy(src.text, from, text, dest, len)
        System.arraycopy(src.styles, from, styles, dest, len)
        val sc = src.combining
        if (sc == null) {
            combining?.fill(null, dest, dest + len)
        } else {
            var c = combining
            if (c == null) {
                c = arrayOfNulls(columns)
                combining = c
            }
            System.arraycopy(sc, from, c, dest, len)
        }
    }

    /** Resize in place (truncating or padding with blank cells of [style]). */
    internal fun resizeColumns(newColumns: Int, style: Long) {
        if (newColumns == columns) return
        val oldText = text
        val oldColumns = columns
        val n = minOf(oldColumns, newColumns)
        val t = IntArray(newColumns)
        System.arraycopy(oldText, 0, t, 0, n)
        val s = LongArray(newColumns)
        System.arraycopy(styles, 0, s, 0, n)
        if (newColumns > n) s.fill(style, n, newColumns)
        val c = combining
        if (c != null) {
            val nc = arrayOfNulls<String>(newColumns)
            System.arraycopy(c, 0, nc, 0, n)
            combining = nc
        }
        text = t
        styles = s
        columns = newColumns
        // A wide char whose right half was cut off by truncation becomes a blank.
        if (newColumns in 1 until oldColumns && oldText[newColumns] == WIDE_SPACER) {
            setCell(newColumns - 1, 0, styles[newColumns - 1])
        }
    }

    /** Index after the last non-empty cell (codepoint != 0), i.e. the used length. */
    internal fun usedLength(): Int {
        var i = columns
        while (i > 0 && text[i - 1] == 0) i--
        return i
    }

    internal fun isBlank(): Boolean {
        for (i in 0 until columns) if (text[i] != 0) return false
        return true
    }

    companion object {
        const val WIDE_SPACER = -1
    }
}
