// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import java.util.Base64

/**
 * An xterm-compatible (TERM=xterm-256color) terminal emulator: VT parser + screen model.
 *
 * Not thread-safe: callers must guard every call (including reads of properties and rows
 * during rendering) with a single lock.
 *
 * Coordinates: rows are 0-based from the top of the visible screen; negative rows
 * (down to -[historySize]) address the scrollback, -1 being the newest history line.
 *
 * Sizes are clamped to at least 2 columns and 1 row; read [columns]/[rows] back after
 * construction or [resize].
 */
class TerminalEmulator(
    columns: Int,
    rows: Int,
    scrollbackLines: Int,
    colorScheme: ColorScheme,
    private val output: TerminalOutput,
) {
    var listener: TerminalListener? = null

    private var mColumns = maxOf(MIN_COLUMNS, columns)
    private var mRows = maxOf(MIN_ROWS, rows)
    private val mainBuffer = ScreenBuffer(mColumns, mRows, maxOf(0, scrollbackLines))
    private val altBuffer = ScreenBuffer(mColumns, mRows, 0)
    private var screen = mainBuffer

    val columns: Int get() = mColumns
    val rows: Int get() = mRows

    // ---- colors -------------------------------------------------------------------------
    private var scheme: ColorScheme = colorScheme

    /** 0-255 indexed colors, 256 default fg, 257 default bg (ARGB). */
    private val palette = IntArray(258)
    private var cursorColorOverride = 0
    private var hasCursorColorOverride = false

    /** Setting a scheme resets OSC palette overrides and fires [TerminalListener.onColorsChanged]. */
    var colorScheme: ColorScheme
        get() = scheme
        set(value) {
            scheme = value
            resetPalette()
            listener?.onColorsChanged()
        }

    /** Effective cursor color: OSC 12 override, else the scheme's cursor color. */
    val cursorColor: Int get() = if (hasCursorColorOverride) cursorColorOverride else scheme.cursor

    private var mTitle = ""
    val title: String get() = mTitle
    private val titleStack = ArrayDeque<String>()

    // ---- cursor & modes -----------------------------------------------------------------
    private var curRow = 0
    private var curCol = 0
    private var aboutToWrap = false
    private var style = TextStyle.DEFAULT
    private var topMargin = 0
    private var bottomMargin = mRows - 1

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var newLineMode = false
    private var appCursorKeys = false
    private var appKeypad = false
    private var bracketedPaste = false
    private var reverseVideo = false
    private var cursorVisible = true
    private var mCursorShape = CursorShape.BLOCK
    private var cursorBlink = false
    private var mouseMode = MouseTrackingMode.NONE
    private var mouseSgr = false
    private var focusReporting = false

    private val charsets = IntArray(4)
    private var glSet = 0
    private var singleShift = -1

    private var tabStops = BooleanArray(mColumns)

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    private var lastPrinted = -1
    private var mScrolledLines = 0L

    // ---- parser -------------------------------------------------------------------------
    private var state = GROUND
    private val params = IntArray(MAX_PARAMS)
    private val subParam = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var paramOverflow = false
    private var privateMarker = 0
    private var intermediate = 0
    private var intermediateCount = 0
    private val stringBuffer = StringBuilder()
    private var apcOverflow = false
    private var dcsFinal = 0
    private var dcsPrivate = 0
    private var dcsIntermediate = 0
    private var dcsIntermediateCount = 0

    private var utf8Need = 0
    private var utf8Cp = 0
    private var utf8Min = 0

    private val resizeOut = IntArray(4)

    init {
        resetPalette()
        resetTabStops()
    }

    /** Feed bytes received from the host. UTF-8 sequences may be split across calls. */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            i++
            if (utf8Need == 0) {
                when {
                    b < 0x80 -> process(b)
                    b and 0xE0 == 0xC0 -> {
                        utf8Need = 1
                        utf8Cp = b and 0x1F
                        utf8Min = 0x80
                    }
                    b and 0xF0 == 0xE0 -> {
                        utf8Need = 2
                        utf8Cp = b and 0x0F
                        utf8Min = 0x800
                    }
                    b and 0xF8 == 0xF0 -> {
                        utf8Need = 3
                        utf8Cp = b and 0x07
                        utf8Min = 0x10000
                    }
                    else -> process(REPLACEMENT)
                }
            } else if (b and 0xC0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                if (--utf8Need == 0) {
                    val cp = utf8Cp
                    if (cp < utf8Min || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) process(REPLACEMENT) else process(cp)
                }
            } else {
                // Truncated sequence: emit a replacement and reprocess this byte.
                utf8Need = 0
                process(REPLACEMENT)
                i--
            }
        }
    }

    /** Resize; rows-only changes must not lose content (lines move to/from scrollback). */
    fun resize(columns: Int, rows: Int) {
        val newCols = maxOf(MIN_COLUMNS, columns)
        val newRows = maxOf(MIN_ROWS, rows)
        if (newCols == mColumns && newRows == mRows) return
        val onAlt = screen === altBuffer
        val out = resizeOut

        // Main screen: its cursor is the live one, or the one saved when entering the alt screen.
        val mainRow: Int
        val mainCol: Int
        val mainWrap: Boolean
        if (!onAlt) {
            mainRow = curRow
            mainCol = curCol
            mainWrap = aboutToWrap
        } else if (savedMain.valid) {
            mainRow = savedMain.row.coerceIn(0, mRows - 1)
            mainCol = savedMain.col.coerceIn(0, mColumns - 1)
            mainWrap = savedMain.aboutToWrap
        } else {
            mainRow = curRow
            mainCol = curCol
            mainWrap = false
        }
        val newMainRow: Int
        var newMainCol: Int
        var newMainWrap: Boolean
        if (newCols != mColumns) {
            mainBuffer.reflow(newCols, newRows, mainRow, mainCol, mainWrap, out)
            newMainRow = out[0]
            newMainCol = out[1]
            newMainWrap = out[2] != 0
            mScrolledLines += out[3]
        } else {
            mainBuffer.resizeRows(newRows, mainRow, out)
            newMainRow = out[0]
            newMainCol = mainCol
            newMainWrap = mainWrap
            mScrolledLines += out[1]
        }
        newMainCol = newMainCol.coerceIn(0, newCols - 1)
        if (newMainWrap && newMainCol != newCols - 1) newMainWrap = false

        altBuffer.resizeSimple(newCols, newRows, if (onAlt) curRow else 0, out)
        val newAltRow = out[0]

        val oldCols = mColumns
        mColumns = newCols
        mRows = newRows

        if (!onAlt) {
            curRow = newMainRow
            curCol = newMainCol
            aboutToWrap = newMainWrap
        } else {
            curRow = newAltRow
            curCol = curCol.coerceIn(0, newCols - 1)
            if (aboutToWrap && curCol != newCols - 1) aboutToWrap = false
            if (savedMain.valid) {
                savedMain.row = newMainRow
                savedMain.col = newMainCol
                savedMain.aboutToWrap = newMainWrap
            }
        }
        clampSaved(savedMain)
        clampSaved(savedAlt)

        topMargin = 0
        bottomMargin = mRows - 1

        val oldStops = tabStops
        tabStops = BooleanArray(newCols)
        for (c in 0 until newCols) {
            tabStops[c] = if (c < oldCols) oldStops[c] else (c % 8 == 0 && c != 0)
        }
    }

    /** Full reset (RIS) while keeping the scrollback. */
    fun reset() {
        state = GROUND
        utf8Need = 0
        fullReset()
    }

    fun clearScrollback() {
        mainBuffer.clearHistory()
    }

    // ---- inline images (iTerm2 protocol) -------------------------------------------------
    private val images = HashMap<Int, TerminalImage>()
    private val imagesByHash = HashMap<Int, MutableList<TerminalImage>>()
    private var nextImageSlot = 0
    private var imageBytes = 0L
    // Text area size in pixels (0 = unknown); cell sizes are derived from it.
    private var pixelWidth = 0
    private var pixelHeight = 0
    private val cellPixelWidth: Int get() = if (pixelWidth > 0) pixelWidth / mColumns else 0
    private val cellPixelHeight: Int get() = if (pixelHeight > 0) pixelHeight / mRows else 0

    // iTerm2 multipart file transfer (MultipartFile=, FilePart=..., FileEnd) in progress.
    private var multipartArgs: String? = null
    private val multipartData = StringBuilder()

    /** The image whose tiles carry [slot] (see [ImageCell]), or null once it is gone. */
    fun getImage(slot: Int): TerminalImage? = images[slot]

    /**
     * Size of the text area (columns × rows cells) in pixels, as the PTY reports it. Images
     * sized in pixels or by their own dimensions and the CSI 14 t / 16 t replies use it.
     */
    fun setPixelSize(width: Int, height: Int) {
        pixelWidth = width.coerceAtLeast(0)
        pixelHeight = height.coerceAtLeast(0)
    }

    /** Same as [setPixelSize] with the size of one cell. */
    fun setCellPixelSize(width: Int, height: Int) = setPixelSize(width * mColumns, height * mRows)

    // ---- cursor -------------------------------------------------------------------------
    val cursorRow: Int get() = curRow
    val cursorCol: Int get() = curCol
    val isCursorVisible: Boolean get() = cursorVisible
    val cursorShape: CursorShape get() = mCursorShape
    val isCursorBlinking: Boolean get() = cursorBlink

    /**
     * True when a character was just written to the last column with autowrap on: the cursor
     * is shown on that column but the next printable character goes to the start of the next
     * line (useful for placing IME preedit text).
     */
    val isCursorPendingWrap: Boolean get() = aboutToWrap

    // ---- modes --------------------------------------------------------------------------
    val isApplicationCursorKeys: Boolean get() = appCursorKeys
    val isApplicationKeypad: Boolean get() = appKeypad
    val isBracketedPaste: Boolean get() = bracketedPaste
    val isAlternateScreen: Boolean get() = screen === altBuffer
    val isReverseVideo: Boolean get() = reverseVideo
    val mouseTrackingMode: MouseTrackingMode get() = mouseMode
    val isMouseSgr: Boolean get() = mouseSgr
    val isFocusReporting: Boolean get() = focusReporting

    // ---- buffer -------------------------------------------------------------------------
    /** Number of scrollback lines currently addressable (0 while the alternate screen is active). */
    val historySize: Int get() = if (screen === altBuffer) 0 else mainBuffer.historySize

    /**
     * Count of lines pushed into the main screen's scrollback since creation (lines that were
     * discarded because the scrollback was full still count). Lets callers keep absolute
     * anchors (selection) stable while output scrolls: `scrolledLines + row` names the same
     * line until it falls out of the scrollback. Unchanged while the alternate screen is in
     * use (it has no scrollback). [resize] adjusts it by the number of lines moved between
     * the screen and the scrollback (it decreases when growing pulls lines back); after a
     * column change (reflow) previously computed anchors are meaningless.
     */
    val scrolledLines: Long get() = mScrolledLines

    /** @param row in `-historySize until rows` */
    fun getRow(row: Int): TerminalRow {
        if (row < -historySize || row >= mRows) {
            throw IndexOutOfBoundsException("row $row not in ${-historySize} until $mRows")
        }
        return screen.row(row)
    }

    /**
     * Effective colors for a cell style: out[0] = foreground ARGB, out[1] = background ARGB.
     * Applies the palette (including OSC overrides), SGR inverse, DECSCNM reverse video,
     * FAINT (dimmed foreground) and INVISIBLE (foreground = background).
     */
    fun resolveColors(style: Long, out: IntArray) {
        val fgV = TextStyle.foreground(style)
        val bgV = TextStyle.background(style)
        val flags = TextStyle.flags(style)
        var fg = if (fgV and TextStyle.RGB_BIT != 0) OPAQUE or (fgV and 0xFFFFFF) else palette[fgV.coerceIn(0, 257)]
        var bg = if (bgV and TextStyle.RGB_BIT != 0) OPAQUE or (bgV and 0xFFFFFF) else palette[bgV.coerceIn(0, 257)]
        if (flags and TextStyle.INVERSE != 0) {
            val t = fg
            fg = bg
            bg = t
        }
        if (reverseVideo) {
            val t = fg
            fg = bg
            bg = t
        }
        if (flags and TextStyle.FAINT != 0) fg = blend(fg, bg)
        if (flags and TextStyle.INVISIBLE != 0) fg = bg
        out[0] = fg
        out[1] = bg
    }

    /**
     * Text between two cell positions (start inclusive, end exclusive, row-major order).
     * Soft-wrapped lines are joined without a newline, hard line ends become '\n', trailing
     * blanks of each line are trimmed, wide-char spacers are skipped, combining marks kept.
     */
    fun getText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        var sr = startRow
        var sc = startCol
        var er = endRow
        var ec = endCol
        if (sr > er || (sr == er && sc > ec)) {
            sr = endRow
            sc = endCol
            er = startRow
            ec = startCol
        }
        val minRow = -historySize
        if (sr < minRow) {
            sr = minRow
            sc = 0
        }
        if (er >= mRows) {
            er = mRows - 1
            ec = mColumns
        }
        if (sr > er) return ""
        val sb = StringBuilder()
        for (r in sr..er) {
            val row = screen.row(r)
            var from = if (r == sr) sc.coerceIn(0, mColumns) else 0
            val to = if (r == er) ec.coerceIn(0, mColumns) else mColumns
            if (from in 1 until mColumns && row.text[from] == TerminalRow.WIDE_SPACER) from--
            val lineStart = sb.length
            for (c in from until to) {
                val cp = row.text[c]
                if (cp == TerminalRow.WIDE_SPACER) continue
                if (cp == KittyPlaceholder.CODE_POINT) {
                    sb.append(' ')
                    continue
                }
                if (cp == 0 || ImageCell.isImage(cp)) sb.append(' ') else sb.appendCodePoint(cp)
                val comb = row.combining?.get(c)
                if (comb != null) sb.append(comb)
            }
            if (r < er) {
                if (!(row.isWrapped && to >= mColumns)) {
                    trimTrailing(sb, lineStart)
                    sb.append('\n')
                }
            } else {
                trimTrailing(sb, lineStart)
            }
        }
        return sb.toString()
    }

    // ---- input encoding (depends on current modes) -------------------------------------
    fun encodeKey(key: TerminalKey, modifiers: Int = 0): ByteArray {
        val mods = modifiers and (KeyMod.SHIFT or KeyMod.ALT or KeyMod.CTRL)
        val m = if (mods != 0) mods + 1 else 0
        val alt = mods and KeyMod.ALT != 0
        val s: String = when (key) {
            TerminalKey.UP -> cursorKey('A', m)
            TerminalKey.DOWN -> cursorKey('B', m)
            TerminalKey.RIGHT -> cursorKey('C', m)
            TerminalKey.LEFT -> cursorKey('D', m)
            TerminalKey.HOME -> cursorKey('H', m)
            TerminalKey.END -> cursorKey('F', m)
            TerminalKey.INSERT -> tildeKey(2, m)
            TerminalKey.DELETE -> tildeKey(3, m)
            TerminalKey.PAGE_UP -> tildeKey(5, m)
            TerminalKey.PAGE_DOWN -> tildeKey(6, m)
            TerminalKey.F1 -> ss3FunctionKey('P', m)
            TerminalKey.F2 -> ss3FunctionKey('Q', m)
            TerminalKey.F3 -> ss3FunctionKey('R', m)
            TerminalKey.F4 -> ss3FunctionKey('S', m)
            TerminalKey.F5 -> tildeKey(15, m)
            TerminalKey.F6 -> tildeKey(17, m)
            TerminalKey.F7 -> tildeKey(18, m)
            TerminalKey.F8 -> tildeKey(19, m)
            TerminalKey.F9 -> tildeKey(20, m)
            TerminalKey.F10 -> tildeKey(21, m)
            TerminalKey.F11 -> tildeKey(23, m)
            TerminalKey.F12 -> tildeKey(24, m)
            TerminalKey.BACKSPACE -> {
                val b = if (mods and KeyMod.CTRL != 0) "\b" else "\u007f"
                if (alt) "\u001b$b" else b
            }
            TerminalKey.TAB -> when {
                mods and KeyMod.SHIFT != 0 -> "\u001b[Z"
                alt -> "\u001b\t"
                else -> "\t"
            }
            TerminalKey.ENTER -> {
                val e = if (newLineMode) "\r\n" else "\r"
                if (alt) "\u001b$e" else e
            }
            TerminalKey.ESCAPE -> if (alt) "\u001b\u001b" else "\u001b"
        }
        return s.toByteArray(Charsets.UTF_8)
    }

    /** Encodes a typed character, applying CTRL (C0 mapping) and ALT (ESC prefix). */
    fun encodeCodePoint(codePoint: Int, modifiers: Int = 0): ByteArray {
        var cp = codePoint
        if (modifiers and KeyMod.CTRL != 0) {
            cp = when (codePoint) {
                in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                ' '.code, '@'.code, '2'.code -> 0
                '['.code, '3'.code -> 27
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '/'.code, '7'.code -> 31
                '?'.code, '8'.code -> 127
                else -> codePoint
            }
        }
        val sb = StringBuilder(4)
        if (modifiers and KeyMod.ALT != 0) sb.append('\u001b')
        if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) sb.appendCodePoint(cp) else sb.append('�')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Bracketed if DECSET 2004 is on; normalizes CRLF/LF to CR; strips ESC[201~ from text. */
    fun encodePaste(text: String): ByteArray {
        var t = text.replace("\r\n", "\r").replace('\n', '\r')
        if (bracketedPaste) {
            t = t.replace(PASTE_END, "").replace(PASTE_START, "")
            t = PASTE_START + t + PASTE_END
        }
        return t.toByteArray(Charsets.UTF_8)
    }

    /** @return null when the current mouse mode does not report this event. column/row 0-based. */
    fun encodeMouse(action: MouseAction, button: Int, column: Int, row: Int, modifiers: Int = 0): ByteArray? {
        val mode = mouseMode
        if (mode == MouseTrackingMode.NONE) return null
        when (action) {
            MouseAction.PRESS -> if (mode == MouseTrackingMode.X10 && button !in 0..2) return null
            MouseAction.RELEASE -> if (mode == MouseTrackingMode.X10) return null
            MouseAction.MOTION -> when (mode) {
                MouseTrackingMode.ANY_EVENT -> {}
                MouseTrackingMode.BUTTON_EVENT -> if (button !in 0..2) return null
                else -> return null
            }
            MouseAction.WHEEL_UP, MouseAction.WHEEL_DOWN -> if (mode == MouseTrackingMode.X10) return null
        }
        var cb = when (action) {
            MouseAction.WHEEL_UP -> 64
            MouseAction.WHEEL_DOWN -> 65
            else -> button.coerceIn(0, 3)
        }
        if (action == MouseAction.MOTION) cb += 32
        var modBits = 0
        if (mode != MouseTrackingMode.X10) {
            if (modifiers and KeyMod.SHIFT != 0) modBits += 4
            if (modifiers and KeyMod.ALT != 0) modBits += 8
            if (modifiers and KeyMod.CTRL != 0) modBits += 16
        }
        val x = maxOf(0, column) + 1
        val y = maxOf(0, row) + 1
        if (mouseSgr) {
            val fin = if (action == MouseAction.RELEASE) 'm' else 'M'
            return "\u001b[<${cb + modBits};$x;$y$fin".toByteArray(Charsets.UTF_8)
        }
        if (action == MouseAction.RELEASE) cb = 3
        if (x > 223 || y > 223) return null
        return byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + cb + modBits).toByte(), (32 + x).toByte(), (32 + y).toByte())
    }

    /** @return null unless focus reporting (DECSET 1004) is enabled. */
    fun encodeFocus(focused: Boolean): ByteArray? {
        if (!focusReporting) return null
        return (if (focused) "\u001b[I" else "\u001b[O").toByteArray(Charsets.UTF_8)
    }

    // =====================================================================================
    // Parser
    // =====================================================================================

    private fun process(cp: Int) {
        if (cp == 0x18 || cp == 0x1A) { // CAN, SUB abort any sequence
            state = GROUND
            return
        }
        if (cp == 0x1B) {
            when (state) {
                OSC_STRING -> {
                    state = GROUND
                    dispatchOsc(false)
                }
                DCS_PASSTHROUGH -> {
                    state = GROUND
                    dispatchDcs()
                }
                APC_STRING -> {
                    state = GROUND
                    dispatchApc()
                }
            }
            enterEscape()
            return
        }
        when (state) {
            GROUND -> when {
                cp >= 0xA0 || (cp in 0x20..0x7E) -> print(cp)
                cp < 0x20 -> execute(cp)
                // DEL and C1 controls are ignored.
            }
            ESCAPE -> escape(cp)
            ESCAPE_INTERMEDIATE -> escapeIntermediate(cp)
            CSI_ENTRY, CSI_PARAM -> csiParam(cp)
            CSI_INTERMEDIATE -> csiIntermediate(cp)
            CSI_IGNORE -> csiIgnore(cp)
            OSC_STRING -> oscString(cp)
            DCS_ENTRY, DCS_PARAM -> dcsParam(cp)
            DCS_INTERMEDIATE -> dcsIntermediateState(cp)
            DCS_PASSTHROUGH -> dcsPassthrough(cp)
            APC_STRING -> apcString(cp)
            DCS_IGNORE, SOS_STRING -> if (cp == ST_C1) state = GROUND
        }
    }

    private fun enterEscape() {
        state = ESCAPE
        intermediate = 0
        intermediateCount = 0
    }

    private fun collectIntermediate(cp: Int) {
        if (intermediateCount == 0) intermediate = cp
        intermediateCount++
    }

    private fun clearParams() {
        paramCount = 0
        paramOverflow = false
        privateMarker = 0
        intermediate = 0
        intermediateCount = 0
    }

    private fun startParam(sub: Boolean) {
        if (paramCount < MAX_PARAMS) {
            params[paramCount] = -1
            subParam[paramCount] = sub
            paramCount++
        } else {
            paramOverflow = true
        }
    }

    /** Handles digits, ';' and ':'. @return true if consumed. */
    private fun paramChar(cp: Int): Boolean {
        when (cp) {
            in '0'.code..'9'.code -> {
                if (paramCount == 0) startParam(false)
                if (!paramOverflow) {
                    val i = paramCount - 1
                    val v = params[i]
                    params[i] = if (v < 0) cp - '0'.code else minOf(v * 10 + (cp - '0'.code), MAX_PARAM_VALUE)
                }
            }
            ';'.code -> {
                if (paramCount == 0) startParam(false)
                startParam(false)
            }
            ':'.code -> {
                if (paramCount == 0) startParam(false)
                startParam(true)
            }
            else -> return false
        }
        return true
    }

    private fun escape(cp: Int) {
        when {
            cp < 0x20 -> execute(cp)
            cp <= 0x2F -> {
                collectIntermediate(cp)
                state = ESCAPE_INTERMEDIATE
            }
            cp == '['.code -> {
                clearParams()
                state = CSI_ENTRY
            }
            cp == ']'.code -> {
                stringBuffer.setLength(0)
                state = OSC_STRING
            }
            cp == 'P'.code -> {
                clearParams()
                state = DCS_ENTRY
            }
            cp == '_'.code -> {
                stringBuffer.setLength(0)
                apcOverflow = false
                state = APC_STRING
            }
            cp == 'X'.code || cp == '^'.code -> state = SOS_STRING
            cp <= 0x7E -> {
                state = GROUND
                escDispatch(cp)
            }
            cp == 0x7F -> {}
            else -> {
                state = GROUND
                process(cp)
            }
        }
    }

    private fun escapeIntermediate(cp: Int) {
        when {
            cp < 0x20 -> execute(cp)
            cp <= 0x2F -> collectIntermediate(cp)
            cp <= 0x7E -> {
                state = GROUND
                escDispatch(cp)
            }
            cp == 0x7F -> {}
            else -> {
                state = GROUND
                process(cp)
            }
        }
    }

    private fun csiParam(cp: Int) {
        when {
            cp < 0x20 -> execute(cp)
            paramChar(cp) -> state = CSI_PARAM
            cp in 0x3C..0x3F -> if (state == CSI_ENTRY) {
                privateMarker = cp
                state = CSI_PARAM
            } else {
                state = CSI_IGNORE
            }
            cp <= 0x2F -> {
                collectIntermediate(cp)
                state = CSI_INTERMEDIATE
            }
            cp <= 0x7E -> {
                state = GROUND
                csiDispatch(cp)
            }
            cp == 0x7F -> {}
            else -> {
                state = GROUND
                process(cp)
            }
        }
    }

    private fun csiIntermediate(cp: Int) {
        when {
            cp < 0x20 -> execute(cp)
            cp <= 0x2F -> collectIntermediate(cp)
            cp <= 0x3F -> state = CSI_IGNORE
            cp <= 0x7E -> {
                state = GROUND
                csiDispatch(cp)
            }
            cp == 0x7F -> {}
            else -> {
                state = GROUND
                process(cp)
            }
        }
    }

    private fun csiIgnore(cp: Int) {
        when {
            cp < 0x20 -> execute(cp)
            cp in 0x40..0x7E -> state = GROUND
            cp >= 0x80 -> {
                state = GROUND
                process(cp)
            }
        }
    }

    private fun oscString(cp: Int) {
        when {
            cp == 0x07 -> {
                state = GROUND
                dispatchOsc(true)
            }
            cp == ST_C1 -> {
                state = GROUND
                dispatchOsc(false)
            }
            cp < 0x20 || cp == 0x7F -> {}
            else -> if (stringBuffer.length < MAX_OSC_LENGTH) stringBuffer.appendCodePoint(cp)
        }
    }

    private fun dcsParam(cp: Int) {
        when {
            cp < 0x20 -> {}
            paramChar(cp) -> state = DCS_PARAM
            cp in 0x3C..0x3F -> if (state == DCS_ENTRY) {
                privateMarker = cp
                state = DCS_PARAM
            } else {
                state = DCS_IGNORE
            }
            cp <= 0x2F -> {
                collectIntermediate(cp)
                state = DCS_INTERMEDIATE
            }
            cp <= 0x7E -> hookDcs(cp)
            cp == ST_C1 -> state = GROUND
            else -> {}
        }
    }

    private fun dcsIntermediateState(cp: Int) {
        when {
            cp < 0x20 -> {}
            cp <= 0x2F -> collectIntermediate(cp)
            cp <= 0x3F -> state = DCS_IGNORE
            cp <= 0x7E -> hookDcs(cp)
            cp == ST_C1 -> state = GROUND
            else -> {}
        }
    }

    private fun hookDcs(final: Int) {
        dcsFinal = final
        dcsPrivate = privateMarker
        dcsIntermediate = intermediate
        dcsIntermediateCount = intermediateCount
        stringBuffer.setLength(0)
        state = DCS_PASSTHROUGH
    }

    private fun dcsPassthrough(cp: Int) {
        when (cp) {
            ST_C1 -> {
                state = GROUND
                dispatchDcs()
            }
            0x7F -> {}
            else -> if (stringBuffer.length < MAX_DCS_LENGTH) stringBuffer.appendCodePoint(cp)
        }
    }

    private fun apcString(cp: Int) {
        when {
            cp == ST_C1 -> {
                state = GROUND
                dispatchApc()
            }
            cp < 0x20 || cp == 0x7F -> {}
            stringBuffer.length < MAX_OSC_LENGTH -> stringBuffer.appendCodePoint(cp)
            else -> apcOverflow = true
        }
    }

    /** APC strings: only kitty graphics commands (`APC G …`) are understood. */
    private fun dispatchApc() {
        if (apcOverflow || stringBuffer.isEmpty() || stringBuffer[0] != 'G') return
        kittyGraphics(KittyCommand.parse(stringBuffer.substring(1)))
    }

    private fun dispatchDcs() {
        if (dcsFinal == 'q'.code && dcsPrivate == 0 && dcsIntermediateCount == 1 && dcsIntermediate == '$'.code) {
            decrqss(stringBuffer.toString())
        }
    }

    // =====================================================================================
    // Controls
    // =====================================================================================

    private fun execute(c: Int) {
        when (c) {
            0x07 -> listener?.onBell()
            0x08 -> {
                if (curCol > 0) curCol--
                aboutToWrap = false
            }
            0x09 -> {
                curCol = nextTabStop(curCol)
                aboutToWrap = false
            }
            0x0A, 0x0B, 0x0C -> {
                index()
                if (newLineMode) curCol = 0
                aboutToWrap = false
            }
            0x0D -> {
                curCol = 0
                aboutToWrap = false
            }
            0x0E -> glSet = 1
            0x0F -> glSet = 0
        }
    }

    private fun escDispatch(final: Int) {
        if (intermediateCount == 0) {
            when (final) {
                '7'.code -> saveCursor()
                '8'.code -> restoreCursor()
                'D'.code -> {
                    index()
                    aboutToWrap = false
                }
                'E'.code -> {
                    index()
                    curCol = 0
                    aboutToWrap = false
                }
                'H'.code -> tabStops[curCol] = true
                'M'.code -> {
                    reverseIndex()
                    aboutToWrap = false
                }
                'c'.code -> fullReset()
                '='.code -> appKeypad = true
                '>'.code -> appKeypad = false
                'N'.code -> singleShift = 2
                'O'.code -> singleShift = 3
                'Z'.code -> reply(DA1_REPLY)
            }
        } else if (intermediateCount == 1) {
            when (intermediate) {
                '('.code -> charsets[0] = charsetFor(final)
                ')'.code -> charsets[1] = charsetFor(final)
                '*'.code -> charsets[2] = charsetFor(final)
                '+'.code -> charsets[3] = charsetFor(final)
                '#'.code -> if (final == '8'.code) decaln()
            }
        }
    }

    private fun charsetFor(final: Int): Int = when (final) {
        '0'.code -> CHARSET_DEC_GRAPHICS
        'A'.code -> CHARSET_UK
        else -> CHARSET_ASCII
    }

    private fun csiDispatch(final: Int) {
        if (intermediateCount > 1) return
        when (privateMarker) {
            0 -> if (intermediateCount == 0) csiNormal(final) else csiWithIntermediate(final)
            '?'.code -> if (intermediateCount == 0) {
                csiPrivate(final)
            } else if (intermediate == '$'.code && final == 'p'.code) {
                decrqm(private = true)
            }
            '>'.code -> if (intermediateCount == 0) csiGreater(final)
            // '<' and '=' sequences (kitty keyboard protocol, tertiary DA, ...) are ignored.
        }
    }

    private fun csiNormal(final: Int) {
        when (final) {
            '@'.code -> insertChars(pMin1(0))
            'A'.code -> cursorUp(pMin1(0))
            'B'.code, 'e'.code -> cursorDown(pMin1(0))
            'C'.code, 'a'.code -> cursorForward(pMin1(0))
            'D'.code -> cursorBackward(pMin1(0))
            'E'.code -> {
                cursorDown(pMin1(0))
                curCol = 0
            }
            'F'.code -> {
                cursorUp(pMin1(0))
                curCol = 0
            }
            'G'.code, '`'.code -> setCursorCol(pMin1(0) - 1)
            'H'.code, 'f'.code -> setCursorPosition(pMin1(0) - 1, pMin1(1) - 1)
            'I'.code -> {
                repeat(minOf(pMin1(0), mColumns)) { curCol = nextTabStop(curCol) }
                aboutToWrap = false
            }
            'J'.code -> eraseDisplay(p(0, 0))
            'K'.code -> eraseLine(p(0, 0))
            'L'.code -> insertLines(pMin1(0))
            'M'.code -> deleteLines(pMin1(0))
            'P'.code -> deleteChars(pMin1(0))
            'S'.code -> scrollUpRegion(minOf(pMin1(0), mRows))
            'T'.code -> if (paramCount <= 1) {
                screen.scrollDown(topMargin, bottomMargin, minOf(pMin1(0), mRows), eraseStyle())
            }
            'X'.code -> eraseChars(pMin1(0))
            'Z'.code -> {
                repeat(minOf(pMin1(0), mColumns)) { curCol = prevTabStop(curCol) }
                aboutToWrap = false
            }
            'b'.code -> if (lastPrinted >= 0) {
                val n = minOf(pMin1(0), mColumns * mRows)
                val cp = lastPrinted
                repeat(n) { printMapped(cp) }
            }
            'c'.code -> if (p(0, 0) == 0) reply(DA1_REPLY)
            'd'.code -> setCursorRow(pMin1(0) - 1)
            'g'.code -> when (p(0, 0)) {
                0 -> tabStops[curCol] = false
                3 -> tabStops.fill(false)
            }
            'h'.code -> setAnsiModes(true)
            'l'.code -> setAnsiModes(false)
            'm'.code -> sgr()
            'n'.code -> when (p(0, 0)) {
                5 -> reply("\u001b[0n")
                6 -> reply("\u001b[${reportRow()};${curCol + 1}R")
            }
            'r'.code -> setMargins()
            's'.code -> if (paramCount == 0) saveCursor()
            't'.code -> windowOps()
            'u'.code -> if (paramCount == 0) restoreCursor()
        }
    }

    private fun csiWithIntermediate(final: Int) {
        when {
            intermediate == ' '.code && final == 'q'.code -> decscusr(p(0, 0))
            intermediate == '!'.code && final == 'p'.code -> softReset()
            intermediate == '$'.code && final == 'p'.code -> decrqm(private = false)
        }
    }

    private fun csiPrivate(final: Int) {
        when (final) {
            'h'.code -> for (i in 0 until maxOf(paramCount, 1)) setDecMode(p(i, 0), true)
            'l'.code -> for (i in 0 until maxOf(paramCount, 1)) setDecMode(p(i, 0), false)
            'n'.code -> if (p(0, 0) == 6) reply("\u001b[?${reportRow()};${curCol + 1}R")
            'J'.code -> eraseDisplay(p(0, 0)) // DECSED: no protected cells, same as ED
            'K'.code -> eraseLine(p(0, 0)) // DECSEL
        }
    }

    private fun csiGreater(final: Int) {
        when (final) {
            'c'.code -> if (p(0, 0) == 0) reply(DA2_REPLY)
            'q'.code -> if (p(0, 0) == 0) reply("\u001bP>|Tern\u001b\\")
        }
    }

    private fun p(i: Int, default: Int): Int = if (i < paramCount && params[i] >= 0) params[i] else default

    /** Parameter where 0 and "missing" both mean 1. */
    private fun pMin1(i: Int): Int {
        val v = p(i, 1)
        return if (v == 0) 1 else v
    }

    private fun reportRow(): Int = if (originMode) curRow - topMargin + 1 else curRow + 1

    private fun reply(s: String) {
        output.write(s.toByteArray(Charsets.UTF_8))
    }

    // =====================================================================================
    // Printing
    // =====================================================================================

    private fun print(codePoint: Int) {
        var cp = codePoint
        if (cp < 0x80) {
            val set = if (singleShift >= 0) charsets[singleShift] else charsets[glSet]
            singleShift = -1
            if (set != CHARSET_ASCII) cp = mapCharset(set, cp)
        }
        printMapped(cp)
    }

    private fun printMapped(cp: Int) {
        val w = if (cp < 0x7F) 1 else WcWidth.width(cp)
        if (w == 0) {
            attachCombining(cp)
            return
        }
        lastPrinted = cp
        var row = screen.row(curRow)
        if (aboutToWrap) {
            aboutToWrap = false
            if (autoWrap) {
                row.isWrapped = true
                index()
                curCol = 0
                row = screen.row(curRow)
            }
        }
        if (w == 2 && curCol >= mColumns - 1) {
            if (autoWrap) {
                row.isWrapped = true
                index()
                curCol = 0
                row = screen.row(curRow)
            } else {
                curCol = mColumns - 2
            }
        }
        if (insertMode) insertCells(row, curCol, w, eraseStyle())
        clearWideOverlap(row, curCol, w)
        row.setCell(curCol, cp, style)
        if (w == 2) row.setCell(curCol + 1, TerminalRow.WIDE_SPACER, style)
        val next = curCol + w
        if (next >= mColumns) {
            curCol = mColumns - 1
            aboutToWrap = autoWrap
        } else {
            curCol = next
        }
    }

    /** Attach a zero-width code point to the character before the cursor. */
    private fun attachCombining(cp: Int) {
        var r = curRow
        var c = if (aboutToWrap) curCol else curCol - 1
        var row = screen.row(r)
        if (c < 0) {
            if (r > 0 && screen.row(r - 1).isWrapped) {
                r--
                row = screen.row(r)
                c = mColumns - 1
            } else {
                return
            }
        }
        if (row.text[c] == TerminalRow.WIDE_SPACER && c > 0) c--
        val base = row.text[c]
        if (base == 0 || base == TerminalRow.WIDE_SPACER || ImageCell.isImage(base)) return
        row.appendCombining(c, cp)
    }

    /** Before writing [w] cells at [col], blank the halves of wide chars that would be split. */
    private fun clearWideOverlap(row: TerminalRow, col: Int, w: Int) {
        if (col > 0 && row.text[col] == TerminalRow.WIDE_SPACER) row.setCell(col - 1, 0, row.styles[col - 1])
        val end = col + w
        if (end < mColumns && row.text[end] == TerminalRow.WIDE_SPACER) row.setCell(end, 0, row.styles[end])
    }

    // =====================================================================================
    // Cursor movement & scrolling
    // =====================================================================================

    private fun index() {
        if (curRow == bottomMargin) {
            scrollUpRegion(1)
        } else if (curRow < mRows - 1) {
            curRow++
        }
    }

    private fun reverseIndex() {
        if (curRow == topMargin) {
            screen.scrollDown(topMargin, bottomMargin, 1, eraseStyle())
        } else if (curRow > 0) {
            curRow--
        }
    }

    private fun scrollUpRegion(n: Int) {
        val main = screen === mainBuffer
        val pushed = screen.scrollUp(topMargin, bottomMargin, n, eraseStyle(), toHistory = main && topMargin == 0)
        if (main) mScrolledLines += pushed
    }

    private fun cursorUp(n: Int) {
        val top = if (curRow >= topMargin) topMargin else 0
        curRow = maxOf(top, curRow - n)
        aboutToWrap = false
    }

    private fun cursorDown(n: Int) {
        val bottom = if (curRow <= bottomMargin) bottomMargin else mRows - 1
        curRow = minOf(bottom, curRow + n)
        aboutToWrap = false
    }

    private fun cursorForward(n: Int) {
        curCol = minOf(mColumns - 1, curCol + n)
        aboutToWrap = false
    }

    private fun cursorBackward(n: Int) {
        curCol = maxOf(0, curCol - n)
        aboutToWrap = false
    }

    private fun setCursorCol(c: Int) {
        curCol = c.coerceIn(0, mColumns - 1)
        aboutToWrap = false
    }

    private fun setCursorRow(r: Int) {
        curRow = if (originMode) (topMargin + r).coerceIn(topMargin, bottomMargin) else r.coerceIn(0, mRows - 1)
        aboutToWrap = false
    }

    private fun setCursorPosition(r: Int, c: Int) {
        setCursorRow(r)
        setCursorCol(c)
    }

    private fun nextTabStop(col: Int): Int {
        var c = col + 1
        while (c < mColumns - 1 && !tabStops[c]) c++
        return minOf(c, mColumns - 1)
    }

    private fun prevTabStop(col: Int): Int {
        var c = col - 1
        while (c > 0 && !tabStops[c]) c--
        return maxOf(c, 0)
    }

    private fun resetTabStops() {
        tabStops = BooleanArray(mColumns) { it % 8 == 0 && it != 0 }
    }

    private fun setMargins() {
        var top = p(0, 1)
        if (top == 0) top = 1
        var bottom = p(1, mRows)
        if (bottom == 0 || bottom > mRows) bottom = mRows
        if (top < bottom) {
            topMargin = top - 1
            bottomMargin = bottom - 1
            setCursorPosition(0, 0)
        }
    }

    // =====================================================================================
    // Editing
    // =====================================================================================

    /** Background-color-erase style: current background, default foreground, no attributes. */
    private fun eraseStyle(): Long =
        TextStyle.encode(TextStyle.COLOR_DEFAULT_FG, TextStyle.background(style), 0)

    private fun eraseCells(row: TerminalRow, from: Int, to: Int, fill: Long) {
        var f = from.coerceIn(0, mColumns)
        var t = to.coerceIn(0, mColumns)
        if (f >= t) return
        if (f > 0 && row.text[f] == TerminalRow.WIDE_SPACER) f--
        if (t < mColumns && row.text[t] == TerminalRow.WIDE_SPACER) t++
        row.erase(f, t, fill)
    }

    private fun eraseDisplay(mode: Int) {
        val fill = eraseStyle()
        when (mode) {
            0 -> {
                val row = screen.row(curRow)
                eraseCells(row, curCol, mColumns, fill)
                row.isWrapped = false
                for (r in curRow + 1 until mRows) screen.row(r).clear(fill)
            }
            1 -> {
                for (r in 0 until curRow) screen.row(r).clear(fill)
                eraseCells(screen.row(curRow), 0, curCol + 1, fill)
            }
            2 -> for (r in 0 until mRows) screen.row(r).clear(fill)
            3 -> mainBuffer.clearHistory()
        }
        aboutToWrap = false
    }

    private fun eraseLine(mode: Int) {
        val fill = eraseStyle()
        val row = screen.row(curRow)
        when (mode) {
            0 -> {
                eraseCells(row, curCol, mColumns, fill)
                row.isWrapped = false
            }
            1 -> eraseCells(row, 0, curCol + 1, fill)
            2 -> row.clear(fill)
        }
        aboutToWrap = false
    }

    private fun eraseChars(n: Int) {
        eraseCells(screen.row(curRow), curCol, curCol + minOf(n, mColumns), eraseStyle())
        aboutToWrap = false
    }

    /** Shift cells right from [col] by [n], opening blanks; cells pushed past the edge are lost. */
    private fun insertCells(row: TerminalRow, col: Int, count: Int, fill: Long) {
        val n = minOf(count, mColumns - col)
        if (n <= 0) return
        if (col > 0 && row.text[col] == TerminalRow.WIDE_SPACER) {
            row.setCell(col - 1, 0, row.styles[col - 1])
            row.setCell(col, 0, row.styles[col])
        }
        val moveLen = mColumns - col - n
        if (moveLen > 0) {
            val cut = row.text[col + moveLen] == TerminalRow.WIDE_SPACER
            row.copyFrom(row, col, col + n, moveLen)
            if (cut) row.setCell(mColumns - 1, 0, row.styles[mColumns - 1])
        }
        row.erase(col, col + n, fill)
    }

    private fun insertChars(n: Int) {
        insertCells(screen.row(curRow), curCol, n, eraseStyle())
        aboutToWrap = false
    }

    private fun deleteChars(count: Int) {
        val row = screen.row(curRow)
        val col = curCol
        val n = minOf(count, mColumns - col)
        if (col > 0 && row.text[col] == TerminalRow.WIDE_SPACER) row.setCell(col - 1, 0, row.styles[col - 1])
        val moveLen = mColumns - col - n
        if (moveLen > 0) row.copyFrom(row, col + n, col, moveLen)
        row.erase(mColumns - n, mColumns, eraseStyle())
        if (row.text[col] == TerminalRow.WIDE_SPACER) row.setCell(col, 0, row.styles[col])
        aboutToWrap = false
    }

    private fun insertLines(n: Int) {
        if (curRow < topMargin || curRow > bottomMargin) return
        screen.scrollDown(curRow, bottomMargin, minOf(n, mRows), eraseStyle())
        curCol = 0
        aboutToWrap = false
    }

    private fun deleteLines(n: Int) {
        if (curRow < topMargin || curRow > bottomMargin) return
        screen.scrollUp(curRow, bottomMargin, minOf(n, mRows), eraseStyle(), toHistory = false)
        curCol = 0
        aboutToWrap = false
    }

    private fun decaln() {
        topMargin = 0
        bottomMargin = mRows - 1
        for (r in 0 until mRows) {
            val row = screen.row(r)
            row.clear(TextStyle.DEFAULT)
            row.text.fill('E'.code)
        }
        curRow = 0
        curCol = 0
        aboutToWrap = false
    }

    // =====================================================================================
    // Modes
    // =====================================================================================

    private fun setAnsiModes(value: Boolean) {
        for (i in 0 until paramCount) {
            when (p(i, 0)) {
                4 -> insertMode = value
                20 -> newLineMode = value
            }
        }
    }

    private fun setDecMode(mode: Int, value: Boolean) {
        when (mode) {
            1 -> appCursorKeys = value
            5 -> if (reverseVideo != value) {
                reverseVideo = value
                listener?.onColorsChanged()
            }
            6 -> {
                originMode = value
                setCursorPosition(0, 0)
            }
            7 -> autoWrap = value
            9 -> mouseMode = if (value) MouseTrackingMode.X10 else MouseTrackingMode.NONE
            12 -> cursorBlink = value
            25 -> cursorVisible = value
            47 -> if (value) switchToAlt(clear = false) else switchToMain()
            66 -> appKeypad = value
            1000 -> mouseMode = if (value) MouseTrackingMode.NORMAL else MouseTrackingMode.NONE
            1002 -> mouseMode = if (value) MouseTrackingMode.BUTTON_EVENT else MouseTrackingMode.NONE
            1003 -> mouseMode = if (value) MouseTrackingMode.ANY_EVENT else MouseTrackingMode.NONE
            1004 -> focusReporting = value
            1006 -> mouseSgr = value
            1047 -> if (value) {
                switchToAlt(clear = false)
            } else {
                if (screen === altBuffer) altBuffer.clearScreen(eraseStyle())
                switchToMain()
            }
            1048 -> if (value) saveCursor() else restoreCursor()
            1049 -> if (value) {
                saveCursor()
                switchToAlt(clear = true)
            } else {
                switchToMain()
                restoreCursor()
            }
            2004 -> bracketedPaste = value
        }
    }

    private fun switchToAlt(clear: Boolean) {
        screen = altBuffer
        if (clear) altBuffer.clearScreen(eraseStyle())
    }

    private fun switchToMain() {
        screen = mainBuffer
    }

    private fun decrqm(private: Boolean) {
        val mode = p(0, 0)
        fun b(v: Boolean) = if (v) 1 else 2
        if (private) {
            val value = when (mode) {
                1 -> b(appCursorKeys)
                5 -> b(reverseVideo)
                6 -> b(originMode)
                7 -> b(autoWrap)
                9 -> b(mouseMode == MouseTrackingMode.X10)
                12 -> b(cursorBlink)
                25 -> b(cursorVisible)
                47, 1047, 1049 -> b(screen === altBuffer)
                66 -> b(appKeypad)
                1000 -> b(mouseMode == MouseTrackingMode.NORMAL)
                1002 -> b(mouseMode == MouseTrackingMode.BUTTON_EVENT)
                1003 -> b(mouseMode == MouseTrackingMode.ANY_EVENT)
                1004 -> b(focusReporting)
                1006 -> b(mouseSgr)
                2004 -> b(bracketedPaste)
                else -> 0
            }
            reply("\u001b[?$mode;$value\$y")
        } else {
            val value = when (mode) {
                4 -> b(insertMode)
                20 -> b(newLineMode)
                else -> 0
            }
            reply("\u001b[$mode;$value\$y")
        }
    }

    private fun decscusr(ps: Int) {
        when (ps) {
            0, 1 -> {
                mCursorShape = CursorShape.BLOCK
                cursorBlink = true
            }
            2 -> {
                mCursorShape = CursorShape.BLOCK
                cursorBlink = false
            }
            3 -> {
                mCursorShape = CursorShape.UNDERLINE
                cursorBlink = true
            }
            4 -> {
                mCursorShape = CursorShape.UNDERLINE
                cursorBlink = false
            }
            5 -> {
                mCursorShape = CursorShape.BAR
                cursorBlink = true
            }
            6 -> {
                mCursorShape = CursorShape.BAR
                cursorBlink = false
            }
        }
    }

    private fun decscusrValue(): Int {
        val base = when (mCursorShape) {
            CursorShape.BLOCK -> 1
            CursorShape.UNDERLINE -> 3
            CursorShape.BAR -> 5
        }
        return if (cursorBlink) base else base + 1
    }

    private fun windowOps() {
        when (p(0, 0)) {
            // Text area and cell size in pixels (image tools size their pictures with these);
            // unanswered while the size is unknown rather than made up.
            14 -> if (pixelSizeKnown) reply("\u001b[4;$pixelHeight;${pixelWidth}t")
            16 -> if (pixelSizeKnown) reply("\u001b[6;$cellPixelHeight;${cellPixelWidth}t")
            18 -> reply("\u001b[8;$mRows;${mColumns}t")
            19 -> reply("\u001b[9;$mRows;${mColumns}t")
            22 -> {
                val which = p(1, 0)
                if (which == 0 || which == 2) {
                    if (titleStack.size >= MAX_TITLE_STACK) titleStack.removeFirst()
                    titleStack.addLast(mTitle)
                }
            }
            23 -> {
                val which = p(1, 0)
                if (which == 0 || which == 2) {
                    val t = titleStack.removeLastOrNull()
                    if (t != null) setTitle(t)
                }
            }
        }
    }

    // =====================================================================================
    // Cursor save/restore & resets
    // =====================================================================================

    private class SavedCursor {
        var valid = false
        var row = 0
        var col = 0
        var style = TextStyle.DEFAULT
        var aboutToWrap = false
        var originMode = false
        val charsets = IntArray(4)
        var gl = 0
    }

    private fun saveCursor() {
        val s = if (screen === mainBuffer) savedMain else savedAlt
        s.valid = true
        s.row = curRow
        s.col = curCol
        s.style = style
        s.aboutToWrap = aboutToWrap
        s.originMode = originMode
        charsets.copyInto(s.charsets)
        s.gl = glSet
    }

    private fun restoreCursor() {
        val s = if (screen === mainBuffer) savedMain else savedAlt
        if (!s.valid) {
            curRow = 0
            curCol = 0
            style = TextStyle.DEFAULT
            aboutToWrap = false
            originMode = false
            charsets.fill(CHARSET_ASCII)
            glSet = 0
            return
        }
        curRow = s.row.coerceIn(0, mRows - 1)
        curCol = s.col.coerceIn(0, mColumns - 1)
        style = s.style
        originMode = s.originMode
        aboutToWrap = s.aboutToWrap && curCol == mColumns - 1
        s.charsets.copyInto(charsets)
        glSet = s.gl
    }

    private fun clampSaved(s: SavedCursor) {
        s.row = s.row.coerceIn(0, mRows - 1)
        s.col = s.col.coerceIn(0, mColumns - 1)
    }

    /** DECSTR. */
    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autoWrap = true
        appCursorKeys = false
        appKeypad = false
        topMargin = 0
        bottomMargin = mRows - 1
        charsets.fill(CHARSET_ASCII)
        glSet = 0
        singleShift = -1
        style = TextStyle.DEFAULT
        aboutToWrap = false
        val s = if (screen === mainBuffer) savedMain else savedAlt
        s.valid = false
    }

    /** RIS. Keeps the scrollback and the title. */
    private fun fullReset() {
        images.clear()
        multipartArgs = null
        multipartData.setLength(0)
        imagesByHash.clear()
        imageBytes = 0
        kittyImages.clear()
        kittyBytes = 0
        kittyLoad = null
        kittyData.setLength(0)
        kittyTiles.clear()
        screen = mainBuffer
        mainBuffer.clearScreen(TextStyle.DEFAULT)
        altBuffer.clearScreen(TextStyle.DEFAULT)
        curRow = 0
        curCol = 0
        aboutToWrap = false
        style = TextStyle.DEFAULT
        topMargin = 0
        bottomMargin = mRows - 1
        autoWrap = true
        originMode = false
        insertMode = false
        newLineMode = false
        appCursorKeys = false
        appKeypad = false
        bracketedPaste = false
        reverseVideo = false
        cursorVisible = true
        mCursorShape = CursorShape.BLOCK
        cursorBlink = false
        mouseMode = MouseTrackingMode.NONE
        mouseSgr = false
        focusReporting = false
        charsets.fill(CHARSET_ASCII)
        glSet = 0
        singleShift = -1
        resetTabStops()
        savedMain.valid = false
        savedAlt.valid = false
        lastPrinted = -1
        titleStack.clear()
        resetPalette()
        listener?.onColorsChanged()
    }

    // =====================================================================================
    // SGR
    // =====================================================================================

    private fun sgr() {
        if (paramCount == 0) {
            style = TextStyle.DEFAULT
            return
        }
        var flags = TextStyle.flags(style)
        var fg = TextStyle.foreground(style)
        var bg = TextStyle.background(style)
        var i = 0
        while (i < paramCount) {
            val code = if (params[i] < 0) 0 else params[i]
            var groupEnd = i + 1
            while (groupEnd < paramCount && subParam[groupEnd]) groupEnd++
            val hasSub = groupEnd > i + 1
            var next = groupEnd
            when (code) {
                0 -> {
                    flags = 0
                    fg = TextStyle.COLOR_DEFAULT_FG
                    bg = TextStyle.COLOR_DEFAULT_BG
                }
                1 -> flags = flags or TextStyle.BOLD
                2 -> flags = flags or TextStyle.FAINT
                3 -> flags = flags or TextStyle.ITALIC
                4 -> {
                    flags = flags and TextStyle.ANY_UNDERLINE.inv()
                    val kind = if (hasSub) maxOf(0, params[i + 1]) else 1
                    flags = flags or when (kind) {
                        0 -> 0
                        2 -> TextStyle.DOUBLE_UNDERLINE
                        3 -> TextStyle.CURLY_UNDERLINE
                        else -> TextStyle.UNDERLINE
                    }
                }
                5, 6 -> flags = flags or TextStyle.BLINK
                7 -> flags = flags or TextStyle.INVERSE
                8 -> flags = flags or TextStyle.INVISIBLE
                9 -> flags = flags or TextStyle.STRIKETHROUGH
                21 -> flags = (flags and TextStyle.ANY_UNDERLINE.inv()) or TextStyle.DOUBLE_UNDERLINE
                22 -> flags = flags and (TextStyle.BOLD or TextStyle.FAINT).inv()
                23 -> flags = flags and TextStyle.ITALIC.inv()
                24 -> flags = flags and TextStyle.ANY_UNDERLINE.inv()
                25 -> flags = flags and TextStyle.BLINK.inv()
                27 -> flags = flags and TextStyle.INVERSE.inv()
                28 -> flags = flags and TextStyle.INVISIBLE.inv()
                29 -> flags = flags and TextStyle.STRIKETHROUGH.inv()
                in 30..37 -> fg = code - 30
                38, 48, 58 -> {
                    val color: Int
                    if (hasSub) {
                        color = extendedColorFromGroup(i + 1, groupEnd)
                    } else {
                        color = extendedColorFromParams(i + 1)
                        next = i + 1 + extConsumed
                    }
                    if (color >= 0) {
                        if (code == 38) fg = color else if (code == 48) bg = color
                    }
                }
                39 -> fg = TextStyle.COLOR_DEFAULT_FG
                in 40..47 -> bg = code - 40
                49 -> bg = TextStyle.COLOR_DEFAULT_BG
                in 90..97 -> fg = code - 90 + 8
                in 100..107 -> bg = code - 100 + 8
            }
            i = next
        }
        style = TextStyle.encode(fg, bg, flags)
    }

    /** `38:5:n`, `38:2:cs:r:g:b` or `38:2:r:g:b`; params[from until to] are the sub-params. */
    private fun extendedColorFromGroup(from: Int, to: Int): Int {
        val n = to - from
        if (n < 1) return -1
        return when (params[from]) {
            5 -> if (n >= 2) indexColor(params[from + 1]) else -1
            2 -> when {
                n >= 5 -> rgbColor(params[from + 2], params[from + 3], params[from + 4])
                n == 4 -> rgbColor(params[from + 1], params[from + 2], params[from + 3])
                else -> -1
            }
            else -> -1
        }
    }

    /** Params consumed by the last [extendedColorFromParams] call. */
    private var extConsumed = 0

    /** `38;5;n` or `38;2;r;g;b` starting at [from]; sets [extConsumed]. */
    private fun extendedColorFromParams(from: Int): Int {
        if (from >= paramCount) {
            extConsumed = 0
            return -1
        }
        return when (params[from]) {
            5 -> {
                extConsumed = minOf(2, paramCount - from)
                if (from + 1 < paramCount) indexColor(params[from + 1]) else -1
            }
            2 -> {
                extConsumed = minOf(4, paramCount - from)
                if (from + 3 < paramCount) rgbColor(params[from + 1], params[from + 2], params[from + 3]) else -1
            }
            else -> {
                extConsumed = 1
                -1
            }
        }
    }

    private fun indexColor(v: Int): Int = if (v in 0..255) v else -1

    private fun rgbColor(r: Int, g: Int, b: Int): Int {
        fun c(v: Int) = v.coerceIn(0, 255)
        return TextStyle.RGB_BIT or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun sgrReport(): String {
        val sb = StringBuilder("0")
        val f = TextStyle.flags(style)
        if (f and TextStyle.BOLD != 0) sb.append(";1")
        if (f and TextStyle.FAINT != 0) sb.append(";2")
        if (f and TextStyle.ITALIC != 0) sb.append(";3")
        if (f and TextStyle.UNDERLINE != 0) sb.append(";4")
        if (f and TextStyle.DOUBLE_UNDERLINE != 0) sb.append(";21")
        if (f and TextStyle.CURLY_UNDERLINE != 0) sb.append(";4:3")
        if (f and TextStyle.BLINK != 0) sb.append(";5")
        if (f and TextStyle.INVERSE != 0) sb.append(";7")
        if (f and TextStyle.INVISIBLE != 0) sb.append(";8")
        if (f and TextStyle.STRIKETHROUGH != 0) sb.append(";9")
        appendColorReport(sb, TextStyle.foreground(style), 30, 90, 38, TextStyle.COLOR_DEFAULT_FG)
        appendColorReport(sb, TextStyle.background(style), 40, 100, 48, TextStyle.COLOR_DEFAULT_BG)
        return sb.toString()
    }

    private fun appendColorReport(sb: StringBuilder, v: Int, base: Int, brightBase: Int, ext: Int, default: Int) {
        when {
            v and TextStyle.RGB_BIT != 0 ->
                sb.append(';').append(ext).append(";2;").append((v shr 16) and 0xFF).append(';')
                    .append((v shr 8) and 0xFF).append(';').append(v and 0xFF)
            v == default -> {}
            v < 8 -> sb.append(';').append(base + v)
            v < 16 -> sb.append(';').append(brightBase + v - 8)
            else -> sb.append(';').append(ext).append(";5;").append(v)
        }
    }

    private fun decrqss(pt: String) {
        val resp = when (pt) {
            "m" -> sgrReport() + "m"
            "r" -> "${topMargin + 1};${bottomMargin + 1}r"
            " q" -> "${decscusrValue()} q"
            else -> null
        }
        if (resp == null) reply("\u001bP0\$r\u001b\\") else reply("\u001bP1\$r$resp\u001b\\")
    }

    // =====================================================================================
    // OSC
    // =====================================================================================

    private fun dispatchOsc(bel: Boolean) {
        val s = stringBuffer
        var i = 0
        var ps = 0
        while (i < s.length && s[i] in '0'..'9' && i < 6) {
            ps = ps * 10 + (s[i] - '0')
            i++
        }
        if (i == 0) return
        val pt = when {
            i == s.length -> ""
            s[i] == ';' -> s.substring(i + 1)
            else -> return
        }
        val term = if (bel) "\u0007" else "\u001b\\"
        when (ps) {
            0, 2 -> setTitle(pt)
            4 -> oscPalette(pt, term)
            7 -> oscWorkingDirectory(pt)
            10, 11, 12 -> oscDynamicColors(ps, pt, term)
            52 -> oscClipboard(pt)
            1337 -> oscITerm2(pt, term)
            104 -> oscResetPalette(pt)
            110 -> {
                palette[256] = scheme.foreground
                listener?.onColorsChanged()
            }
            111 -> {
                palette[257] = scheme.background
                listener?.onColorsChanged()
            }
            112 -> {
                hasCursorColorOverride = false
                listener?.onColorsChanged()
            }
        }
    }

    private fun setTitle(t: String) {
        val title = if (t.length > MAX_TITLE_LENGTH) t.substring(0, MAX_TITLE_LENGTH) else t
        if (title != mTitle) {
            mTitle = title
            listener?.onTitleChanged(title)
        }
    }

    private fun oscPalette(pt: String, term: String) {
        val parts = pt.split(';')
        var k = 0
        var changed = false
        while (k + 1 < parts.size) {
            val idx = parts[k].toIntOrNull()
            val spec = parts[k + 1]
            if (idx != null && idx in 0..255) {
                if (spec == "?") {
                    reply("\u001b]4;$idx;${colorSpec(palette[idx])}$term")
                } else {
                    val c = parseColor(spec)
                    if (c != null) {
                        palette[idx] = c
                        changed = true
                    }
                }
            }
            k += 2
        }
        if (changed) listener?.onColorsChanged()
    }

    private fun oscDynamicColors(ps: Int, pt: String, term: String) {
        var code = ps
        var changed = false
        for (part in pt.split(';')) {
            if (code > 12) break
            if (part == "?") {
                val c = when (code) {
                    10 -> palette[256]
                    11 -> palette[257]
                    else -> cursorColor
                }
                reply("\u001b]$code;${colorSpec(c)}$term")
            } else {
                val c = parseColor(part)
                if (c != null) {
                    when (code) {
                        10 -> palette[256] = c
                        11 -> palette[257] = c
                        12 -> {
                            cursorColorOverride = c
                            hasCursorColorOverride = true
                        }
                    }
                    changed = true
                }
            }
            code++
        }
        if (changed) listener?.onColorsChanged()
    }

    private fun oscResetPalette(pt: String) {
        if (pt.isEmpty()) {
            for (i in 0..255) palette[i] = baseColor(i)
        } else {
            for (part in pt.split(';')) {
                val idx = part.toIntOrNull() ?: continue
                if (idx in 0..255) palette[idx] = baseColor(idx)
            }
        }
        listener?.onColorsChanged()
    }

    /**
     * iTerm2 inline image: `File=key=value;...:<base64 file>`. Only `inline=1` images are shown;
     * `width`/`height` are cells (`N`), pixels (`Npx`), percent of the screen (`N%`) or `auto`.
     */
    private val pixelSizeKnown: Boolean get() = cellPixelWidth > 0 && cellPixelHeight > 0

    /** iTerm2's OSC 1337: inline images (also sent in parts) and the cell size query. */
    private fun oscITerm2(pt: String, term: String) {
        when {
            pt.startsWith("File=") -> oscInlineImage(pt.substring(5))
            pt.startsWith("MultipartFile=") -> {
                multipartArgs = pt.substring(14)
                multipartData.setLength(0)
            }
            pt.startsWith("FilePart=") -> {
                if (multipartArgs == null) return
                if (multipartData.length + pt.length - 9 > MAX_MULTIPART_LENGTH) {
                    multipartArgs = null
                    multipartData.setLength(0)
                } else {
                    multipartData.append(pt, 9, pt.length)
                }
            }
            pt == "FileEnd" -> {
                val args = multipartArgs ?: return
                multipartArgs = null
                val data = multipartData.toString()
                multipartData.setLength(0)
                oscInlineImage("$args:$data")
            }
            // Reply: cell height and width in points, and pixels per point.
            pt == "ReportCellSize" -> if (pixelSizeKnown) reply("\u001b]1337;ReportCellSize=$cellPixelHeight.0;$cellPixelWidth.0;1.0$term")
        }
    }

    private fun oscInlineImage(spec: String) {
        val colon = spec.indexOf(':')
        if (colon < 0) return
        val args = HashMap<String, String>()
        for (part in spec.substring(0, colon).split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) args[part.substring(0, eq)] = part.substring(eq + 1)
        }
        if (args["inline"] != "1") return
        val data = try {
            java.util.Base64.getMimeDecoder().decode(spec.substring(colon + 1))
        } catch (_: IllegalArgumentException) {
            return
        }
        if (data.isEmpty()) return
        val pixels = imagePixelSize(data)
        val cellW = if (cellPixelWidth > 0) cellPixelWidth else DEFAULT_CELL_PX_W
        val cellH = if (cellPixelHeight > 0) cellPixelHeight else DEFAULT_CELL_PX_H
        var columns = imageExtent(args["width"], pixels?.first, cellW, mColumns)
        var rows = imageExtent(args["height"], pixels?.second, cellH, mRows)
        val keepAspect = args["preserveAspectRatio"] != "0"
        if (pixels != null && keepAspect) {
            // Only one side given: derive the other from the image's aspect ratio.
            val (pw, ph) = pixels
            if (args["width"].isAuto() && !args["height"].isAuto() && ph > 0) columns = ceilDiv(rows.toLong() * cellH * pw, ph.toLong() * cellW)
            if (args["height"].isAuto() && !args["width"].isAuto() && pw > 0) rows = ceilDiv(columns.toLong() * cellW * ph, pw.toLong() * cellH)
        }
        columns = columns.coerceIn(1, ImageCell.MAX_SPAN)
        rows = rows.coerceIn(1, ImageCell.MAX_SPAN)
        placeImage(storeImage(data, columns, rows, keepAspect), columns, rows)
    }

    private fun String?.isAuto() = this == null || this == "auto"

    private fun ceilDiv(a: Long, b: Long): Int = ((a + b - 1) / b).toInt().coerceAtLeast(1)

    private fun imageExtent(value: String?, pixels: Int?, cellPx: Int, screenCells: Int): Int = when {
        value.isAuto() -> if (pixels != null) ceilDiv(pixels.toLong(), cellPx.toLong()) else 1
        value!!.endsWith("px") -> value.dropLast(2).toIntOrNull()?.let { ceilDiv(it.toLong(), cellPx.toLong()) } ?: 1
        value.endsWith("%") -> value.dropLast(1).toIntOrNull()?.let { ceilDiv(it.toLong() * screenCells, 100) } ?: 1
        else -> value.toIntOrNull() ?: 1
    }

    /** Registers [data], reusing the slot of an identical image (programs redraw the same ones). */
    private fun storeImage(data: ByteArray, columns: Int, rows: Int, keepAspect: Boolean): TerminalImage {
        val hash = data.contentHashCode()
        imagesByHash[hash]?.firstOrNull {
            it.columns == columns && it.rows == rows && it.preserveAspectRatio == keepAspect && it.data.contentEquals(data)
        }?.let { return it }
        if (images.size >= MAX_IMAGES || imageBytes + data.size > MAX_IMAGE_BYTES) collectImages()
        var slot = nextImageSlot
        while (images.containsKey(slot)) slot = (slot + 1) % (ImageCell.MAX_SLOT + 1)
        nextImageSlot = (slot + 1) % (ImageCell.MAX_SLOT + 1)
        kittyTiles.remove(slot)
        val image = TerminalImage(slot, data, columns, rows, keepAspect)
        images[slot] = image
        imagesByHash.getOrPut(hash) { ArrayList(1) }.add(image)
        imageBytes += data.size
        return image
    }

    /** Forgets images no cell shows any more (scrolled out of the history, overwritten). */
    private fun collectImages() {
        val used = HashSet<Int>()
        for (buffer in arrayOf(mainBuffer, altBuffer)) {
            for (r in -buffer.historySize until mRows) {
                val text = buffer.row(r).text
                for (v in text) if (ImageCell.isImage(v)) used += ImageCell.slot(v)
            }
        }
        val gone = images.keys.filter { it !in used }
        kittyTiles.keys.removeAll(gone.toSet())
        for (slot in gone) {
            val image = images.remove(slot)!!
            imageBytes -= image.data.size
            val hash = image.data.contentHashCode()
            imagesByHash[hash]?.let { list ->
                list.remove(image)
                if (list.isEmpty()) imagesByHash.remove(hash)
            }
        }
    }

    /** Puts the tiles of [image] at the cursor, scrolling as needed; the cursor ends after its last row. */
    private fun placeImage(image: TerminalImage, columns: Int, rows: Int) {
        if (aboutToWrap) {
            aboutToWrap = false
            if (autoWrap) {
                screen.row(curRow).isWrapped = true
                index()
                curCol = 0
            }
        }
        val startCol = curCol
        for (y in 0 until rows) {
            if (y > 0) {
                index()
                curCol = startCol
            }
            val row = screen.row(curRow)
            val width = minOf(columns, mColumns - startCol)
            clearWideOverlap(row, startCol, width)
            for (x in 0 until width) row.setCell(startCol + x, ImageCell.encode(image.slot, x, y), style)
        }
        val next = startCol + columns
        if (next >= mColumns) {
            curCol = mColumns - 1
            aboutToWrap = autoWrap
        } else {
            curCol = next
        }
    }

    // ---- kitty graphics protocol ---------------------------------------------------------
    // https://sw.kovidgoyal.net/kitty/graphics-protocol/ : direct transmission (t=d) of PNG, RGB
    // and RGBA data (optionally zlib), placements at the cursor as image tiles, virtual
    // placements for Unicode placeholders, deletion and the query action. No animation, no
    // file or shared memory transmission, no source rectangles or pixel offsets.
    private val kittyImages = LinkedHashMap<Long, KittyImage>()
    private var kittyBytes = 0L
    private var kittyNextId = KITTY_FIRST_ID - 1
    private var kittyLoad: KittyCommand? = null
    private val kittyData = StringBuilder()
    /** Tile images ([ImageCell] slots) placed by kitty commands, for deletion. */
    private val kittyTiles = HashMap<Int, KittyTile>()

    /** Image of a kitty virtual placement that placeholder cells show, or null when there is none. */
    fun getPlaceholderImage(imageId: Long, placementId: Long): TerminalImage? {
        val image = kittyImages[imageId] ?: return null
        return if (placementId != 0L) image.placements[placementId] else image.placements.values.firstOrNull()
    }

    private fun kittyGraphics(cmd: KittyCommand) {
        val load = kittyLoad
        if (load != null && cmd.onlyKeys("mq")) {
            val quiet = if (cmd.has('q')) cmd.int('q').toInt() else load.int('q').toInt()
            if (kittyData.length + cmd.payload.length > MAX_KITTY_LOAD) {
                kittyLoad = null
                kittyData.setLength(0)
                kittyReply(load.int('i'), load.int('I'), load.int('p'), quiet, "EFBIG:Image data too large")
                return
            }
            kittyData.append(cmd.payload)
            if (cmd.int('m') == 1L) return
            kittyLoad = null
            val data = kittyData.toString()
            kittyData.setLength(0)
            kittyTransmit(load, data, quiet)
            return
        }
        kittyLoad = null
        kittyData.setLength(0)
        when (cmd.char('a', 't')) {
            't', 'T', 'q' -> if (cmd.int('m') == 1L) {
                kittyLoad = cmd
                kittyData.append(cmd.payload)
            } else {
                kittyTransmit(cmd, cmd.payload, cmd.int('q').toInt())
            }
            'p' -> kittyPut(cmd)
            'd' -> kittyDelete(cmd)
        }
    }

    private fun kittyTransmit(cmd: KittyCommand, payload: String, quiet: Int) {
        val action = cmd.char('a', 't')
        val id = cmd.int('i')
        val number = cmd.int('I')
        val placement = cmd.int('p')
        fun fail(error: String) = kittyReply(id, if (action == 'q') 0 else number, placement, quiet, error)
        if (id != 0L && number != 0L) return fail("EINVAL:Must not specify both image id and image number")
        if (action == 'q' && id == 0L) return
        if (cmd.char('t', 'd') != 'd') return fail("EINVAL:Only direct transmission is supported")
        var data = try {
            java.util.Base64.getMimeDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return fail("EINVAL:Invalid base64 data")
        }
        if (cmd.char('o', ' ') == 'z') data = inflateZlib(data, MAX_KITTY_DECODED) ?: return fail("EINVAL:Invalid zlib data")
        val png: ByteArray
        val width: Int
        val height: Int
        when (val format = if (cmd.has('f')) cmd.int('f') else 32L) {
            100L -> {
                val size = if (isPng(data)) imagePixelSize(data) else null
                if (size == null || size.first <= 0 || size.second <= 0) return fail("EINVAL:Not a PNG image")
                png = data
                width = size.first
                height = size.second
            }
            24L, 32L -> {
                val channels = if (format == 24L) 3 else 4
                width = cmd.int('s').coerceAtMost(MAX_KITTY_SIDE).toInt()
                height = cmd.int('v').coerceAtMost(MAX_KITTY_SIDE).toInt()
                if (width <= 0 || height <= 0 || width.toLong() * height * channels > data.size) {
                    return fail("EINVAL:Insufficient image data")
                }
                png = encodePng(width, height, channels, data)
            }
            else -> return fail("EINVAL:Unsupported image format")
        }
        if (action == 'q') return kittyReply(id, 0, 0, quiet, null)
        val image = KittyImage(if (id != 0L) id else newKittyId(), number, png, width, height)
        storeKittyImage(image)
        // An image sent with neither id nor number gets no reply; one with a number learns its id.
        kittyReply(if (id != 0L || number != 0L) image.id else 0, number, placement, quiet, null)
        if (action == 'T') kittyPlace(cmd, image)
    }

    private fun kittyPut(cmd: KittyCommand) {
        val id = cmd.int('i')
        val number = cmd.int('I')
        val placement = cmd.int('p')
        val quiet = cmd.int('q').toInt()
        if (id == 0L && number == 0L) return
        if (id != 0L && number != 0L) return kittyReply(id, number, placement, quiet, "EINVAL:Must not specify both image id and image number")
        val image = if (id != 0L) kittyImages[id] else kittyImages.values.lastOrNull { it.number == number }
        if (image == null) return kittyReply(id, number, placement, quiet, "ENOENT:Image not found")
        kittyPlace(cmd, image)
        kittyReply(image.id, number, placement, quiet, null)
    }

    /**
     * Places [image] at the cursor as tiles, or registers a virtual placement (U=1) for
     * Unicode placeholders. Sizes left out follow the image's pixel size and aspect ratio.
     */
    private fun kittyPlace(cmd: KittyCommand, image: KittyImage) {
        val cellW = (if (cellPixelWidth > 0) cellPixelWidth else DEFAULT_CELL_PX_W).toLong()
        val cellH = (if (cellPixelHeight > 0) cellPixelHeight else DEFAULT_CELL_PX_H).toLong()
        val pw = image.pixelWidth.toLong()
        val ph = image.pixelHeight.toLong()
        var columns = cmd.int('c').coerceIn(0, MAX_KITTY_SIDE).toInt()
        var rows = cmd.int('r').coerceIn(0, MAX_KITTY_SIDE).toInt()
        if (cmd.int('U') == 1L) {
            if (columns == 0) columns = ceilDiv(pw, cellW)
            if (rows == 0) rows = ceilDiv(ph, cellH)
            val max = KittyPlaceholder.MAX_VALUE + 1
            image.placements[cmd.int('p')] = TerminalImage(-1, image.data, columns.coerceIn(1, max), rows.coerceIn(1, max), true, centered = true)
            return
        }
        val fill = columns > 0 && rows > 0
        when {
            columns == 0 && rows == 0 -> {
                columns = ceilDiv(pw, cellW)
                rows = ceilDiv(ph, cellH)
            }
            columns == 0 -> columns = ceilDiv(rows * cellH * pw, ph * cellW)
            rows == 0 -> rows = ceilDiv(columns * cellW * ph, pw * cellH)
        }
        columns = columns.coerceIn(1, ImageCell.MAX_SPAN)
        rows = rows.coerceIn(1, ImageCell.MAX_SPAN)
        val tiles = storeImage(image.data, columns, rows, keepAspect = !fill)
        kittyTiles[tiles.slot] = KittyTile(image.id, cmd.int('p'))
        if (cmd.int('C') == 1L) placeImageKeepingCursor(tiles, columns, rows) else placeImage(tiles, columns, rows)
    }

    /** Like [placeImage] but leaves the cursor where it is; rows below the screen are cut off. */
    private fun placeImageKeepingCursor(image: TerminalImage, columns: Int, rows: Int) {
        val width = minOf(columns, mColumns - curCol)
        for (y in 0 until minOf(rows, mRows - curRow)) {
            val row = screen.row(curRow + y)
            clearWideOverlap(row, curCol, width)
            for (x in 0 until width) row.setCell(curCol + x, ImageCell.encode(image.slot, x, y), style)
        }
    }

    private fun kittyDelete(cmd: KittyCommand) {
        val what = cmd.char('d', 'a')
        val free = what.isUpperCase()
        when (what.lowercaseChar()) {
            'a' -> deleteKittyTiles(free) { _, _ -> true }
            'i' -> deleteKittyImage(kittyImages[cmd.int('i')] ?: return, cmd.int('p'), free)
            'n' -> deleteKittyImage(kittyImages.values.lastOrNull { it.number == cmd.int('I') } ?: return, cmd.int('p'), free)
            'r' -> {
                val range = cmd.int('x')..cmd.int('y')
                for (image in kittyImages.values.filter { it.id in range }) deleteKittyImage(image, 0, free)
            }
            'c' -> deleteKittyTiles(free) { r, c -> r == curRow && c == curCol }
            'p' -> deleteKittyTiles(free) { r, c -> r.toLong() == cmd.int('y') - 1 && c.toLong() == cmd.int('x') - 1 }
            'x' -> deleteKittyTiles(free) { _, c -> c.toLong() == cmd.int('x') - 1 }
            'y' -> deleteKittyTiles(free) { r, _ -> r.toLong() == cmd.int('y') - 1 }
        }
    }

    /** Removes the kitty tile images with a tile on a screen cell matching [hit]. */
    private fun deleteKittyTiles(free: Boolean, hit: (row: Int, col: Int) -> Boolean) {
        val slots = HashSet<Int>()
        for (r in 0 until mRows) {
            val text = screen.row(r).text
            for (c in 0 until mColumns) {
                val v = text[c]
                if (ImageCell.isImage(v) && ImageCell.slot(v) in kittyTiles && hit(r, c)) slots += ImageCell.slot(v)
            }
        }
        if (slots.isEmpty()) return
        clearTiles(slots)
        if (!free) return
        for (slot in slots) {
            val image = kittyImages[kittyTiles[slot]?.imageId ?: continue] ?: continue
            if (image.placements.isEmpty()) removeKittyImage(image.id)
        }
    }

    /** Deletes the placements of [image] (only [placementId] when not 0), and with [free] the image too. */
    private fun deleteKittyImage(image: KittyImage, placementId: Long, free: Boolean) {
        if (placementId != 0L) image.placements.remove(placementId) else image.placements.clear()
        val slots = kittyTiles.filter { (_, t) -> t.imageId == image.id && (placementId == 0L || t.placementId == placementId) }.keys
        if (slots.isNotEmpty()) clearTiles(slots)
        if (free && image.placements.isEmpty()) removeKittyImage(image.id)
    }

    private fun clearTiles(slots: Set<Int>) {
        for (r in 0 until mRows) {
            val row = screen.row(r)
            for (c in 0 until mColumns) {
                val v = row.text[c]
                if (ImageCell.isImage(v) && ImageCell.slot(v) in slots) row.setCell(c, 0, row.styles[c])
            }
        }
    }

    private fun storeKittyImage(image: KittyImage) {
        removeKittyImage(image.id)
        kittyImages[image.id] = image
        kittyBytes += image.data.size
        val oldest = kittyImages.values.iterator()
        while (kittyBytes > MAX_KITTY_BYTES && kittyImages.size > 1) {
            val victim = oldest.next()
            oldest.remove()
            kittyBytes -= victim.data.size
        }
    }

    private fun removeKittyImage(id: Long) {
        kittyImages.remove(id)?.let { kittyBytes -= it.data.size }
    }

    private fun newKittyId(): Long {
        do {
            kittyNextId = if (kittyNextId >= 0xFFFFFFFFL) KITTY_FIRST_ID else kittyNextId + 1
        } while (kittyImages.containsKey(kittyNextId))
        return kittyNextId
    }

    /** `APC G i=…,I=…,p=…;OK|ERROR:message ST`, unless there is no id or [quiet] silences it. */
    private fun kittyReply(id: Long, number: Long, placement: Long, quiet: Int, error: String?) {
        if (id == 0L && number == 0L) return
        if (quiet >= 2 || (error == null && quiet >= 1)) return
        val sb = StringBuilder("\u001b_G")
        if (id != 0L) sb.append("i=").append(id)
        if (number != 0L) sb.append(if (id != 0L) ",I=" else "I=").append(number)
        if (placement != 0L) sb.append(",p=").append(placement)
        sb.append(';').append(error ?: "OK").append("\u001b\\")
        reply(sb.toString())
    }

    private fun isPng(data: ByteArray): Boolean =
        data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()

    private fun oscWorkingDirectory(pt: String) {
        if (!pt.startsWith("file://")) return
        val rest = pt.substring(7)
        val slash = rest.indexOf('/')
        if (slash < 0) return
        val path = percentDecode(rest.substring(slash)) ?: return
        listener?.onWorkingDirectoryChanged(path)
    }

    private fun oscClipboard(pt: String) {
        val semi = pt.indexOf(';')
        if (semi < 0) return
        val data = pt.substring(semi + 1)
        if (data == "?") return // Reading the clipboard is not allowed.
        val bytes = try {
            Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            return
        }
        listener?.onClipboardWrite(String(bytes, Charsets.UTF_8))
    }

    private fun resetPalette() {
        for (i in 0..255) palette[i] = baseColor(i)
        palette[256] = scheme.foreground
        palette[257] = scheme.background
        hasCursorColorOverride = false
    }

    private fun baseColor(i: Int): Int {
        if (i < 16) return scheme.ansi[i]
        if (i < 232) {
            val j = i - 16
            return rgb(cubeLevel(j / 36), cubeLevel((j / 6) % 6), cubeLevel(j % 6))
        }
        val v = 8 + 10 * (i - 232)
        return rgb(v, v, v)
    }

    // =====================================================================================
    // Key helpers
    // =====================================================================================

    private fun cursorKey(c: Char, m: Int): String = when {
        m != 0 -> "\u001b[1;$m$c"
        appCursorKeys -> "\u001bO$c"
        else -> "\u001b[$c"
    }

    private fun tildeKey(n: Int, m: Int): String = if (m != 0) "\u001b[$n;$m~" else "\u001b[$n~"

    private fun ss3FunctionKey(c: Char, m: Int): String = if (m != 0) "\u001b[1;$m$c" else "\u001bO$c"

    companion object {
        private const val MIN_COLUMNS = 2
        private const val MIN_ROWS = 1
        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 65535
        private const val MAX_OSC_LENGTH = 1 shl 22
        private const val MAX_MULTIPART_LENGTH = 1 shl 23

        /** Bounds for inline images kept alive; unreferenced ones are dropped first. */
        private const val MAX_IMAGES = 256
        private const val MAX_IMAGE_BYTES = 32L shl 20

        /** kitty graphics: base64 text of one chunked upload, decoded size, kept image data, pixel or cell extent. */
        private const val MAX_KITTY_LOAD = 1 shl 24
        private const val MAX_KITTY_DECODED = 1 shl 25
        private const val MAX_KITTY_BYTES = 32L shl 20
        private const val MAX_KITTY_SIDE = 10000L

        /** Ids the terminal assigns to images sent with only a number (I=). */
        private const val KITTY_FIRST_ID = 0x40000000L
        private const val DEFAULT_CELL_PX_W = 10
        private const val DEFAULT_CELL_PX_H = 20
        private const val MAX_DCS_LENGTH = 4096
        private const val MAX_TITLE_LENGTH = 1024
        private const val MAX_TITLE_STACK = 10
        private const val REPLACEMENT = 0xFFFD
        private const val ST_C1 = 0x9C
        private const val OPAQUE = 0xFF000000.toInt()

        private const val GROUND = 0
        private const val ESCAPE = 1
        private const val ESCAPE_INTERMEDIATE = 2
        private const val CSI_ENTRY = 3
        private const val CSI_PARAM = 4
        private const val CSI_INTERMEDIATE = 5
        private const val CSI_IGNORE = 6
        private const val OSC_STRING = 7
        private const val DCS_ENTRY = 8
        private const val DCS_PARAM = 9
        private const val DCS_INTERMEDIATE = 10
        private const val DCS_PASSTHROUGH = 11
        private const val DCS_IGNORE = 12
        private const val SOS_STRING = 13
        private const val APC_STRING = 14

        private const val CHARSET_ASCII = 0
        private const val CHARSET_DEC_GRAPHICS = 1
        private const val CHARSET_UK = 2

        private const val DA1_REPLY = "\u001b[?62;22c"
        private const val DA2_REPLY = "\u001b[>1;10;0c"
        private const val PASTE_START = "\u001b[200~"
        private const val PASTE_END = "\u001b[201~"

        /** DEC Special Graphics for 0x5F..0x7E. */
        private val DEC_GRAPHICS = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )

        private fun mapCharset(set: Int, cp: Int): Int = when (set) {
            CHARSET_DEC_GRAPHICS -> if (cp in 0x5F..0x7E) DEC_GRAPHICS[cp - 0x5F] else cp
            CHARSET_UK -> if (cp == '#'.code) 0x00A3 else cp
            else -> cp
        }

        private fun cubeLevel(v: Int): Int = if (v == 0) 0 else 55 + 40 * v

        private fun rgb(r: Int, g: Int, b: Int): Int = OPAQUE or (r shl 16) or (g shl 8) or b

        private fun blend(fg: Int, bg: Int): Int {
            val r = (((fg shr 16) and 0xFF) + ((bg shr 16) and 0xFF)) / 2
            val g = (((fg shr 8) and 0xFF) + ((bg shr 8) and 0xFF)) / 2
            val b = ((fg and 0xFF) + (bg and 0xFF)) / 2
            return rgb(r, g, b)
        }

        private fun trimTrailing(sb: StringBuilder, from: Int) {
            var end = sb.length
            while (end > from && sb[end - 1] == ' ') end--
            sb.setLength(end)
        }

        private fun hex4(v: Int): String {
            val s = Integer.toHexString(v)
            return "0000".substring(s.length) + s
        }

        private fun colorSpec(c: Int): String {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return "rgb:${hex4(r * 257)}/${hex4(g * 257)}/${hex4(b * 257)}"
        }

        /** XParseColor subset: `rgb:h/h/h` (1-4 hex digits each, scaled) and `#RGB`..`#RRRRGGGGBBBB`. */
        internal fun parseColor(spec: String): Int? {
            if (spec.startsWith("rgb:")) {
                val parts = spec.substring(4).split('/')
                if (parts.size != 3) return null
                val out = IntArray(3)
                for (k in 0..2) {
                    val p = parts[k]
                    if (p.isEmpty() || p.length > 4) return null
                    val v = p.toIntOrNull(16) ?: return null
                    val max = (1 shl (4 * p.length)) - 1
                    out[k] = (v * 255 + max / 2) / max
                }
                return rgb(out[0], out[1], out[2])
            }
            if (spec.startsWith("#")) {
                val hex = spec.substring(1)
                if (hex.isEmpty() || hex.length % 3 != 0 || hex.length > 12) return null
                val n = hex.length / 3
                val out = IntArray(3)
                for (k in 0..2) {
                    val v = hex.substring(k * n, k * n + n).toIntOrNull(16) ?: return null
                    val v16 = v shl (16 - 4 * n)
                    out[k] = v16 shr 8
                }
                return rgb(out[0], out[1], out[2])
            }
            return null
        }

        private fun percentDecode(s: String): String? {
            val bytes = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    bytes.write(v)
                    i += 3
                } else {
                    val cp = s.codePointAt(i)
                    val enc = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    bytes.write(enc, 0, enc.size)
                    i += Character.charCount(cp)
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
