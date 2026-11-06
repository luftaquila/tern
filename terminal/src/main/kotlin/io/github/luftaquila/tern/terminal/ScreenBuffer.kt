// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * A screen plus (optionally) its scrollback, stored as a ring of rows so that scrolling the
 * whole screen into history is O(1) per line.
 *
 * Addressing: row 0 is the top of the screen; rows -1 down to -[historySize] are history,
 * -1 being the newest history line.
 */
internal class ScreenBuffer(columns: Int, rows: Int, val historyCapacity: Int) {
    var columns: Int = columns
        private set
    var rows: Int = rows
        private set

    var historySize: Int = 0
        private set

    private var capacity = rows + historyCapacity
    private var lines = arrayOfNulls<TerminalRow>(capacity)
    private var screenStart = 0
    private var tmp = arrayOfNulls<TerminalRow>(rows)

    private fun slot(row: Int): Int {
        val i = (screenStart + row) % capacity
        return if (i < 0) i + capacity else i
    }

    fun row(row: Int): TerminalRow {
        val i = slot(row)
        val r = lines[i]
        if (r != null) return r
        val created = TerminalRow(columns)
        lines[i] = created
        return created
    }

    fun clearHistory() {
        historySize = 0
    }

    fun clearScreen(fill: Long) {
        for (r in 0 until rows) row(r).clear(fill)
    }

    /**
     * Scrolls rows [top, bottom] up by [count]. When [toHistory] (only meaningful with
     * top == 0 and a history capacity), the lines leaving the top are appended to history.
     * @return number of lines pushed into history.
     */
    fun scrollUp(top: Int, bottom: Int, count: Int, fill: Long, toHistory: Boolean): Int {
        val height = bottom - top + 1
        if (height <= 0 || count <= 0) return 0
        val n = minOf(count, height)
        if (toHistory && top == 0 && historyCapacity > 0) {
            for (k in 0 until n) {
                pushTop(fill)
                if (bottom < rows - 1) {
                    // Keep the rows below the region fixed: move the fresh blank row from the
                    // last screen row up to `bottom`.
                    val blank = lines[slot(rows - 1)]
                    for (r in rows - 1 downTo bottom + 1) lines[slot(r)] = lines[slot(r - 1)]
                    lines[slot(bottom)] = blank
                }
            }
            return n
        }
        if (n == height) {
            for (r in top..bottom) row(r).clear(fill)
            return 0
        }
        for (k in 0 until n) tmp[k] = lines[slot(top + k)]
        for (r in top..bottom - n) lines[slot(r)] = lines[slot(r + n)]
        for (k in 0 until n) {
            val reused = tmp[k] ?: TerminalRow(columns)
            tmp[k] = null
            reused.clear(fill)
            lines[slot(bottom - n + 1 + k)] = reused
        }
        return 0
    }

    /** Scrolls rows [top, bottom] down by [count], blanking the rows that open at the top. */
    fun scrollDown(top: Int, bottom: Int, count: Int, fill: Long) {
        val height = bottom - top + 1
        if (height <= 0 || count <= 0) return
        val n = minOf(count, height)
        if (n == height) {
            for (r in top..bottom) row(r).clear(fill)
            return
        }
        for (k in 0 until n) tmp[k] = lines[slot(bottom - n + 1 + k)]
        for (r in bottom downTo top + n) lines[slot(r)] = lines[slot(r - n)]
        for (k in 0 until n) {
            val reused = tmp[k] ?: TerminalRow(columns)
            tmp[k] = null
            reused.clear(fill)
            lines[slot(top + k)] = reused
        }
    }

    /** Moves screen row 0 into history and opens a blank row at the bottom of the screen. */
    private fun pushTop(fill: Long) {
        screenStart = (screenStart + 1) % capacity
        if (historySize < historyCapacity) historySize++
        val i = slot(rows - 1)
        val r = lines[i]
        if (r == null || r.columns != columns) {
            lines[i] = TerminalRow(columns).also { it.clear(fill) }
        } else {
            r.clear(fill)
        }
    }

    /** All lines, oldest history line first, then the screen rows. */
    private fun snapshot(): ArrayList<TerminalRow> {
        val all = ArrayList<TerminalRow>(historySize + rows)
        for (r in -historySize until rows) all.add(row(r))
        return all
    }

    private fun rebuild(newColumns: Int, newRows: Int, history: List<TerminalRow>, screen: List<TerminalRow>) {
        val newCapacity = newRows + historyCapacity
        val newLines = arrayOfNulls<TerminalRow>(newCapacity)
        val hs = minOf(history.size, historyCapacity)
        val hFrom = history.size - hs
        for (i in 0 until hs) newLines[i] = history[hFrom + i]
        for (i in 0 until newRows) {
            newLines[hs + i] = if (i < screen.size) screen[i] else TerminalRow(newColumns)
        }
        lines = newLines
        capacity = newCapacity
        screenStart = hs % newCapacity
        historySize = hs
        rows = newRows
        columns = newColumns
        tmp = arrayOfNulls(newRows)
    }

    /**
     * Changes the number of screen rows keeping the column count (no reflow).
     * Shrinking first drops blank rows at the bottom below the cursor, then pushes rows from
     * the top into history. Growing pulls rows back from history (keeping the cursor's
     * distance from the bottom) and adds blank rows when history runs out.
     *
     * @return the new cursor row in [out] ([0] = row, [1] = shift of content, positive when
     * lines moved up into history, negative when pulled back down).
     */
    fun resizeRows(newRows: Int, cursorRow: Int, out: IntArray) {
        if (newRows == rows) {
            out[0] = cursorRow
            out[1] = 0
            return
        }
        val all = snapshot()
        val h = historySize
        val screenFrom: Int
        val newCursor: Int
        if (newRows < rows) {
            val k = rows - newRows
            var drop = 0
            var r = rows - 1
            while (drop < k && r > cursorRow && all[h + r].isBlank()) {
                drop++
                r--
            }
            val push = k - drop
            screenFrom = h + push
            newCursor = cursorRow - push
        } else {
            val pull = minOf(newRows - rows, h)
            screenFrom = h - pull
            newCursor = cursorRow + pull
        }
        val history = all.subList(0, screenFrom)
        val screen = all.subList(screenFrom, minOf(all.size, screenFrom + newRows))
        rebuild(columns, newRows, history, screen)
        out[0] = newCursor.coerceIn(0, newRows - 1)
        out[1] = screenFrom - h
    }

    /** Truncates/pads every screen row to [newColumns] then resizes rows (used for the alternate screen). */
    fun resizeSimple(newColumns: Int, newRows: Int, cursorRow: Int, out: IntArray) {
        if (newColumns != columns) {
            for (r in -historySize until rows) lines[slot(r)]?.resizeColumns(newColumns, TextStyle.DEFAULT)
            columns = newColumns
        }
        resizeRows(newRows, cursorRow, out)
    }

    /**
     * Re-wraps history + screen to [newColumns] using the soft-wrap flags and resizes to
     * [newRows], keeping the cursor on the same logical character.
     *
     * @param out receives [0] cursor row, [1] cursor column, [2] 1 if the cursor is in the
     * pending-wrap state, [3] content shift (lines above the screen, new minus old).
     */
    fun reflow(newColumns: Int, newRows: Int, cursorRow: Int, cursorCol: Int, pendingWrap: Boolean, out: IntArray) {
        val all = snapshot()
        val h = historySize
        val cursorAbs = (h + cursorRow).coerceIn(0, all.size - 1)
        var last = all.size - 1
        while (last > cursorAbs && all[last].isBlank()) last--

        val result = ArrayList<TerminalRow>(last + 1)
        var newCursorIdx = 0
        var newCursorCol = 0
        var newPending = false
        val starts = ArrayList<Int>()

        var i = 0
        while (i <= last) {
            var j = i
            while (j < last && all[j].isWrapped) j++
            val firstOut = result.size
            val containsCursor = cursorAbs in i..j
            starts.clear()
            starts.add(0)
            var row = TerminalRow(newColumns)
            var col = 0
            var off = 0
            var cursorOff = -1
            for (k in i..j) {
                val src = all[k]
                if (k == cursorAbs) cursorOff = off + cursorCol + (if (pendingWrap) 1 else 0)
                val used = src.usedLength()
                var c = 0
                while (c < used) {
                    // A stray spacer (should not happen) is carried over as a blank cell so
                    // that column offsets stay consistent with the cursor offset.
                    val cp = src.text[c].let { if (it == TerminalRow.WIDE_SPACER) 0 else it }
                    val w = if (cp != 0 && c + 1 < src.columns && src.text[c + 1] == TerminalRow.WIDE_SPACER) 2 else 1
                    if (col + w > newColumns) {
                        row.isWrapped = true
                        result.add(row)
                        row = TerminalRow(newColumns)
                        col = 0
                        if (containsCursor) starts.add(off)
                    }
                    row.text[col] = cp
                    row.styles[col] = src.styles[c]
                    val comb = src.combining?.get(c)
                    if (comb != null) {
                        val dst = row.combining ?: arrayOfNulls<String>(newColumns).also { row.combining = it }
                        dst[col] = comb
                    }
                    if (w == 2) {
                        row.text[col + 1] = TerminalRow.WIDE_SPACER
                        row.styles[col + 1] = src.styles[c + 1]
                    }
                    col += w
                    off += w
                    c += w
                }
            }
            result.add(row)
            if (containsCursor && cursorOff >= 0) {
                var r = starts.size - 1
                while (r > 0 && starts[r] > cursorOff) r--
                newCursorIdx = firstOut + r
                var cc = cursorOff - starts[r]
                if (cc >= newColumns) {
                    cc = newColumns - 1
                    newPending = true
                } else {
                    newPending = false
                }
                newCursorCol = cc
            }
            i = j + 1
        }

        val total = result.size
        var screenTop = maxOf(0, total - newRows)
        if (newCursorIdx < screenTop) screenTop = newCursorIdx
        val history = result.subList(0, screenTop)
        val screen = result.subList(screenTop, minOf(total, screenTop + newRows))
        rebuild(newColumns, newRows, history, screen)
        out[0] = (newCursorIdx - screenTop).coerceIn(0, newRows - 1)
        out[1] = newCursorCol.coerceIn(0, newColumns - 1)
        out[2] = if (newPending) 1 else 0
        out[3] = screenTop - h
    }
}
