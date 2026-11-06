// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.termview

import java.util.WeakHashMap
import io.github.luftaquila.tern.terminal.TerminalImage
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.terminal.ImageCell
import android.util.LruCache
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.view.WindowInsetsCompat
import io.github.luftaquila.tern.input.HangulKeymap
import io.github.luftaquila.tern.input.ImeTextProcessor
import io.github.luftaquila.tern.input.StickyModifiers
import io.github.luftaquila.tern.input.TerminalInputConnection
import io.github.luftaquila.tern.terminal.KeyMod
import io.github.luftaquila.tern.terminal.MouseAction
import io.github.luftaquila.tern.terminal.MouseButton
import io.github.luftaquila.tern.terminal.MouseTrackingMode
import io.github.luftaquila.tern.terminal.CursorShape
import io.github.luftaquila.tern.terminal.TerminalEmulator
import io.github.luftaquila.tern.terminal.TerminalKey
import io.github.luftaquila.tern.terminal.TerminalRow
import io.github.luftaquila.tern.terminal.TextStyle
import io.github.luftaquila.tern.terminal.WcWidth
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a [TerminalBackend]'s emulator and turns touch, hardware keys and IME input into
 * terminal input. IME composition (Hangul) is drawn inline at the cursor as preedit.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, val modifiers: StickyModifiers) : View(context) {

    // ---- configuration (set by the screen) -------------------------------------------
    var backspaceSendsCtrlH = false

    /** Touch scrolling speed setting (1 = default, [SCROLL_BASE]); applies to drags and flings. */
    var scrollSpeed = 1f

    /** Sets IME_FLAG_NO_PERSONALIZED_LEARNING (Gboard: incognito, no voice typing). */
    var imeIncognito: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            imm.restartInput(this)
        }
    var cursorBlink = true
        set(value) {
            field = value
            restartBlink()
        }

    /** Called when pinch-zoom finished with the new font size in sp. */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** Called for every key/char the user sends (to e.g. hide overlays). */
    var onUserInput: (() -> Unit)? = null

    var backend: TerminalBackend? = null
        set(value) {
            if (field === value) return
            field?.removeScreenListener(screenListener)
            field = value
            value?.addScreenListener(screenListener)
            scrollOffset = 0
            clearPredictedEcho()
            clearSelection()
            lastColumns = -1
            lastRows = -1
            lastPixelWidth = -1
            lastPixelHeight = -1
            if (width > 0 && height > 0) updateGrid()
            invalidate()
        }

    // ---- metrics ------------------------------------------------------------------------
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /**
     * The fallback font (setting, Jetendard by default), for characters the main font lacks, e.g.
     * scripts a Latin-only coding font has no glyphs for. Unused when [hasFallback] is false:
     * those characters then go to Android's system fonts.
     */
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = context.resources.getFont(io.github.luftaquila.tern.R.font.jetendard)
    }
    private var hasFallback = true
    private var configuredTypeface: Typeface? = null
    private var configuredFallback: Typeface? = null
    private var configuredLigatures = true

    /** Symbols Nerd Font, for Nerd Font icons (private use area). */
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = context.resources.getFont(io.github.luftaquila.tern.R.font.symbols_nerd_font)
    }

    /**
     * Noto Sans Symbols 2, for narrow symbols that neither the primary font nor Jetendard has and
     * that Android either lacks (⏵ in Claude Code's "auto mode on") or only has as color emoji (⏸).
     */
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = context.resources.getFont(io.github.luftaquila.tern.R.font.noto_sans_symbols2)
    }

    /** Baseline-relative vertical center of the icon font's em box (scaling pivot). */
    private var iconCenterOffset = 0f

    /** From the baseline to the middle of the line (negative: above), the pivot for shrinking symbols. */
    private var textCenterOffset = 0f

    /** Code point → which paint draws it ([SRC_PRIMARY], [SRC_FALLBACK], [SRC_ICON], [SRC_SYMBOL]). */
    private val glyphSources = SparseIntArray()

    /** Per paint, the font its typeface itself provides (Android 12+, see [hasOwnGlyph]). */
    private val ownFonts = HashMap<Paint, android.graphics.fonts.Font>()
    private val fillPaint = Paint()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    private var asciiMonospace = true
    private val advanceCache = SparseBooleanArray()
    private val padX = (4 * density).roundToInt()
    private val padY = padY(density)
    private var columns = 1
    private var rows = 1
    private var lastColumns = -1
    private var lastRows = -1
    private var lastPixelWidth = -1
    private var lastPixelHeight = -1

    var fontSizeSp: Float = 13f
        private set

    // ---- state ----------------------------------------------------------------------
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var inputConnection: TerminalInputConnection? = null
    private var scrollOffset = 0
    private var scrollRemainder = 0f
    private var lastScrolledLines = 0L
    private var cursorVisiblePhase = true
    private var lastInputTime = 0L
    private val colors = IntArray(2)
    private val charBuf = CharArray(1024)
    private val tmpRect = RectF()

    private val screenListener: () -> Unit = { postInvalidateOnAnimation() }

    private val imeProcessor: ImeTextProcessor = ImeTextProcessor(object : ImeTextProcessor.Sink {
        override fun sendText(text: String, allowModifiers: Boolean): Boolean {
            // Committed composition (the preedit the user was looking at), and whatever follows
            // it before the echo arrives, stays on screen as predicted echo.
            val predict = (imeProcessor.preedit.isNotEmpty() || pendingEcho.isNotEmpty()) &&
                text.none { it < ' ' || it == '\u007f' }
            if (predict) predictEcho(text)
            val applied = writeTyped(text, allowModifiers)
            if (applied) clearPredictedEcho() // a modifier changed what was sent
            noteUserInput()
            return applied
        }

        override fun sendCursorMove(delta: Int) {
            val be = backend ?: return
            val key = if (delta < 0) TerminalKey.LEFT else TerminalKey.RIGHT
            val seq = synchronized(be.lock) { be.emulator.encodeKey(key, 0) }
            repeat(kotlin.math.abs(delta)) { write(seq) }
        }

        override fun sendBackspaces(count: Int) {
            val b = backspaceByte()
            write(ByteArray(count) { b })
        }

        override fun sendForwardDeletes(count: Int) {
            val be = backend ?: return
            val seq = synchronized(be.lock) { be.emulator.encodeKey(TerminalKey.DELETE, 0) }
            repeat(count) { write(seq) }
        }

        override fun onPreeditChanged(preedit: String) {
            if (preedit.isNotEmpty()) scrollToBottom()
            lastInputTime = SystemClock.uptimeMillis() // keep the cursor from blinking while composing
            invalidate()
        }

        override fun hasPendingModifiers(): Boolean = modifiers.isActive && !committingComposition
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        configureFont(Typeface.MONOSPACE, fallbackPaint.typeface, 13f, ligatures = true)
    }

    /**
     * Fonts: [typeface] for the text, [fallback] for characters it lacks (null: Android's system
     * fonts only), and whether programming [ligatures] are used. Does nothing when nothing
     * changed, so it can be called on every recomposition.
     */
    fun configureFont(typeface: Typeface, fallback: Typeface?, sizeSp: Float, ligatures: Boolean) {
        if (typeface === configuredTypeface && fallback === configuredFallback && ligatures == configuredLigatures &&
            sizeSp == fontSizeSp
        ) {
            return
        }
        configuredTypeface = typeface
        configuredFallback = fallback
        configuredLigatures = ligatures
        textPaint.typeface = typeface
        if (fallback != null) fallbackPaint.typeface = fallback
        hasFallback = fallback != null
        val features = if (ligatures) null else NO_LIGATURES
        textPaint.fontFeatureSettings = features
        fallbackPaint.fontFeatureSettings = features
        glyphSources.clear()
        ownFonts.remove(textPaint)
        ownFonts.remove(fallbackPaint)
        applyFontSize(sizeSp)
    }

    fun applyFontSize(sizeSp: Float) {
        fontSizeSp = sizeSp
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        textPaint.textSize = px
        fallbackPaint.textSize = px
        iconPaint.textSize = px
        symbolPaint.textSize = px
        val ifm = iconPaint.fontMetrics
        iconCenterOffset = (ifm.ascent + ifm.descent) / 2f
        val fm = textPaint.fontMetrics
        textCenterOffset = (fm.ascent + fm.descent) / 2f
        cellWidth = textPaint.measureText("M")
        cellHeight = lineHeight(fm)
        baseline = -fm.ascent
        val iw = textPaint.measureText("i")
        val ww = textPaint.measureText("W")
        asciiMonospace = abs(iw - cellWidth) < 0.01f && abs(ww - cellWidth) < 0.01f
        advanceCache.clear()
        if (width > 0 && height > 0) updateGrid()
        invalidate()
    }

    // =====================================================================================
    // Layout
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGrid()
    }

    private fun updateGrid() {
        columns = max(1, ((width - 2 * padX) / cellWidth).toInt())
        rows = max(1, ((height - 2 * padY) / cellHeight).toInt())
        val be = backend ?: return
        // The text area in pixels (what TIOCGWINSZ and CSI 14 t report): programs that draw
        // images derive the cell size from it, so it must not include padding or slack.
        val pixelWidth = (columns * cellWidth).roundToInt()
        val pixelHeight = (rows * cellHeight).roundToInt()
        if (columns != lastColumns || rows != lastRows || pixelWidth != lastPixelWidth || pixelHeight != lastPixelHeight) {
            if (columns != lastColumns) {
                clearSelection()
                clearPredictedEcho()
            }
            lastColumns = columns
            lastRows = rows
            lastPixelWidth = pixelWidth
            lastPixelHeight = pixelHeight
            be.resize(columns, rows, pixelWidth, pixelHeight)
        }
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    override fun onDraw(canvas: Canvas) {
        // Compose does not clip AndroidView content to its bounds; drawColor() would flood
        // the whole window (and paint over the top bar). Clip explicitly.
        canvas.clipRect(0, 0, width, height)
        val be = backend
        if (be == null) {
            fillPaint.color = 0xFF0F1218.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            return
        }
        synchronized(be.lock) {
            val em = be.emulator
            // Keep the viewport anchored while output scrolls (when the user is reading history).
            val scrolled = em.scrolledLines
            if (scrollOffset > 0) scrollOffset += (scrolled - lastScrolledLines).toInt()
            lastScrolledLines = scrolled
            scrollOffset = scrollOffset.coerceIn(0, em.historySize)

            em.resolveColors(TextStyle.DEFAULT, colors)
            val defaultBg = colors[1]
            fillPaint.color = defaultBg
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

            val visibleRows = min(rows, em.rows)
            val cols = min(columns, em.columns)
            for (screenRow in 0 until visibleRows) {
                val row = screenRow - scrollOffset
                if (row < -em.historySize || row >= em.rows) continue
                drawRow(canvas, em, em.getRow(row), screenRow, cols, defaultBg)
            }
            drawSelection(canvas, em)
            reconcileEcho(em)
            val preedit = imeProcessor.preedit
            val echo = if (pendingEcho.isEmpty()) "" else pendingEcho.joinToString("")
            if (preedit.isNotEmpty() || echo.isNotEmpty()) {
                drawInputOverlay(canvas, em, echo, preedit, cols, defaultBg)
            } else {
                drawCursor(canvas, em, cols)
            }
        }
    }

    private fun cellX(col: Int) = padX + col * cellWidth
    private fun cellY(row: Int) = padY + row * cellHeight

    private fun drawRow(canvas: Canvas, em: TerminalEmulator, line: TerminalRow, screenRow: Int, cols: Int, defaultBg: Int) {
        val n = min(cols, line.columns)
        val top = cellY(screenRow)
        val bottom = top + cellHeight
        // Backgrounds, merged into runs.
        var runStart = 0
        var runColor = defaultBg
        for (c in 0..n) {
            val bg = if (c < n) {
                em.resolveColors(line.getStyle(c), colors); colors[1]
            } else defaultBg - 1 // force flush
            if (c == n || bg != runColor) {
                if (runColor != defaultBg && c > runStart) {
                    fillPaint.color = runColor
                    canvas.drawRect(floor(cellX(runStart)), top, floor(cellX(c)), bottom, fillPaint)
                }
                runStart = c
                runColor = bg
            }
        }
        drawImages(canvas, em, line, n, top)
        // Text.
        val y = top + baseline
        var c = 0
        while (c < n) {
            val cp = line.getCodePoint(c)
            if (cp == TerminalRow.WIDE_SPACER || ImageCell.isImage(cp)) { c++; continue }
            val style = line.getStyle(c)
            val flags = TextStyle.flags(style)
            val wide = c + 1 < n && line.getCodePoint(c + 1) == TerminalRow.WIDE_SPACER
            val combining = line.getCombining(c)
            if ((cp == 0 || cp == ' '.code) && combining == null) {
                if (flags and DECORATIONS != 0) {
                    em.resolveColors(style, colors)
                    drawDecorations(canvas, flags, colors[0], cellX(c), cellX(c + 1), top)
                }
                c++
                continue
            }
            em.resolveColors(style, colors)
            val fg = colors[0]
            if (flags and TextStyle.INVISIBLE != 0) { c += if (wide) 2 else 1; continue }
            if (BoxDrawing.handles(cp) && combining == null) {
                fillPaint.color = fg
                BoxDrawing.draw(canvas, cp, cellX(c), top, cellX(c + 1) - cellX(c), cellHeight, fillPaint)
                c++
                continue
            }
            applyTextStyle(fg, flags)
            val source = glyphSource(cp)
            if (source == SRC_ICON && combining == null) {
                // Nerd Font icons are ~2 cells wide: use the next cell too when it is blank.
                val cells = when {
                    wide -> 2
                    c + 1 < n && isBlankCell(line, c + 1) -> 2
                    else -> 1
                }
                drawIcon(canvas, String(Character.toChars(cp)), cellX(c), cells, y)
                val own = if (wide) 2 else 1
                if (flags and DECORATIONS != 0) drawDecorations(canvas, flags, fg, cellX(c), cellX(c + own), top)
                c += own
                continue
            }
            if (!wide && combining == null && isSimple(cp)) {
                // A run of narrow, grid-aligned characters with the same style: one draw call.
                val start = c
                var len = 0
                while (c < n && len < charBuf.size - 2) {
                    val p = line.getCodePoint(c)
                    if (p <= 0 || line.getStyle(c) != style || line.getCombining(c) != null || !isSimple(p) ||
                        (c + 1 < n && line.getCodePoint(c + 1) == TerminalRow.WIDE_SPACER) || BoxDrawing.handles(p)
                    ) break
                    len += Character.toChars(p, charBuf, len)
                    c++
                }
                canvas.drawText(charBuf, 0, len, cellX(start), y, textPaint)
                if (flags and DECORATIONS != 0) drawDecorations(canvas, flags, fg, cellX(start), cellX(c), top)
            } else {
                val cells = if (wide) 2 else 1
                var text = String(Character.toChars(cp))
                if (combining != null) text = Normalizer.normalize(text + combining, Normalizer.Form.NFC)
                val paint = paintFor(source, combining)
                val symbol = isSymbol(cp)
                // Symbols from fonts that are not monospaced (⎇ and ⇄ are about 1 em wide) may
                // spill into a blank next cell, as in Ghostty or kitty; otherwise they shrink.
                val spill = symbol && !wide && c + 1 < n && isBlankCell(line, c + 1) && paint.measureText(text) > cellWidth + 0.5f
                if (spill) {
                    // Keep a little space before the next word.
                    drawCellText(canvas, text, cellX(c), 2 * cellWidth, y, paint, uniform = true, fit = SPILL_FIT * cellWidth)
                } else {
                    drawCellText(canvas, text, cellX(c), cells * cellWidth, y, paint, uniform = symbol)
                }
                if (flags and DECORATIONS != 0) drawDecorations(canvas, flags, fg, cellX(c), cellX(c + cells), top)
                c += cells
            }
        }
    }

    // ---- inline images ------------------------------------------------------------------

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imageRect = RectF()
    private val drawnSlices = HashSet<Long>()

    /** Decoded inline images, by image (identity), bounded by bitmap memory. */
    private val bitmaps = object : LruCache<TerminalImage, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: TerminalImage, value: Bitmap): Int = value.allocationByteCount
    }
    private val undecodable = WeakHashMap<TerminalImage, Boolean>()

    /**
     * Inline images in a row, between the backgrounds and the text. Each picture's slice for
     * this row spans the image's full width as long as any of its cells is still in the row:
     * gitoui writes blanks over all but the first two cells right after placing its graph
     * images (it is made for kitty-style image layers), while erasing every cell (yazi) or
     * overwriting the image removes it. Text written over the picture stays on top.
     */
    private fun drawImages(canvas: Canvas, em: TerminalEmulator, line: TerminalRow, n: Int, top: Float) {
        drawnSlices.clear()
        for (c in 0 until n) {
            val v = line.getCodePoint(c)
            if (!ImageCell.isImage(v)) continue
            val slot = ImageCell.slot(v)
            val startCol = c - ImageCell.x(v)
            val key = (slot.toLong() shl 32) or ((startCol + ImageCell.MAX_SPAN).toLong() shl 8) or ImageCell.y(v).toLong()
            if (key in drawnSlices) continue
            drawnSlices += key
            drawImageSlice(canvas, em.getImage(slot) ?: continue, ImageCell.y(v), startCol, top)
        }
    }

    /**
     * Row [y] of [image], whose area starts at column [startCol]: the whole picture is drawn at
     * its place in the area (letterboxed when it keeps its aspect ratio) and clipped to the row,
     * so rows join without seams.
     */
    private fun drawImageSlice(canvas: Canvas, image: TerminalImage, y: Int, startCol: Int, top: Float) {
        val bitmap = bitmapFor(image) ?: return
        val areaW = image.columns * cellWidth
        val areaH = image.rows * cellHeight
        var w = areaW
        var h = areaH
        if (image.preserveAspectRatio) {
            val scale = min(areaW / bitmap.width, areaH / bitmap.height)
            w = bitmap.width * scale
            h = bitmap.height * scale
        }
        val areaLeft = cellX(startCol)
        val areaTop = top - y * cellHeight
        imageRect.set(areaLeft, areaTop, areaLeft + w, areaTop + h)
        canvas.save()
        canvas.clipRect(areaLeft, top, areaLeft + areaW, top + cellHeight)
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint)
        canvas.restore()
    }

    private fun bitmapFor(image: TerminalImage): Bitmap? {
        bitmaps.get(image)?.let { return it }
        if (undecodable.containsKey(image)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.data, 0, image.data.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(image.data, 0, image.data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap == null) {
            undecodable[image] = true
            return null
        }
        bitmaps.put(image, bitmap)
        return bitmap
    }

    private fun applyTextStyle(fg: Int, flags: Int) {
        val bold = flags and TextStyle.BOLD != 0
        val skew = if (flags and TextStyle.ITALIC != 0) -0.2f else 0f
        textPaint.color = fg
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = skew
        fallbackPaint.color = fg
        fallbackPaint.isFakeBoldText = bold
        fallbackPaint.textSkewX = skew
        symbolPaint.color = fg
        symbolPaint.isFakeBoldText = bold
        symbolPaint.textSkewX = skew
        iconPaint.color = fg
    }

    /**
     * Draws one glyph cluster centered in [targetWidth]. One wider than [fit] is squeezed
     * horizontally, or with [uniform] (symbols, which must keep their shape) scaled down around
     * the middle of the line.
     */
    private fun drawCellText(
        canvas: Canvas,
        text: String,
        x: Float,
        targetWidth: Float,
        y: Float,
        paint: Paint = textPaint,
        uniform: Boolean = false,
        fit: Float = targetWidth,
    ) {
        val adv = paint.measureText(text)
        if (adv <= fit + 0.5f) {
            canvas.drawText(text, x + (targetWidth - adv) / 2f, y, paint)
        } else if (uniform) {
            val scale = fit / adv
            canvas.save()
            canvas.scale(scale, scale, x + targetWidth / 2f, y + textCenterOffset)
            canvas.drawText(text, x + (targetWidth - adv) / 2f, y, paint)
            canvas.restore()
        } else {
            val old = paint.textScaleX
            paint.textScaleX = old * targetWidth / adv
            canvas.drawText(text, x, y, paint)
            paint.textScaleX = old
        }
    }

    /** Symbols and symbol-like punctuation (⎇, ⇄, ಄, ...), which keep their shape when they must shrink. */
    private fun isSymbol(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.MATH_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }

    /** Draws a Nerd Font icon centered in [cells] cells, uniformly scaled down if it is wider. */
    private fun drawIcon(canvas: Canvas, text: String, x: Float, cells: Int, baselineY: Float) {
        val width = cells * cellWidth
        val adv = iconPaint.measureText(text)
        if (adv <= 0f) return
        val scale = minOf(1f, width / adv)
        val cx = x + width / 2f
        canvas.save()
        canvas.scale(scale, scale, cx, baselineY + iconCenterOffset)
        canvas.drawText(text, cx - adv / 2f, baselineY, iconPaint)
        canvas.restore()
    }

    private fun isBlankCell(line: TerminalRow, col: Int): Boolean {
        val cp = line.getCodePoint(col)
        return (cp == 0 || cp == ' '.code) && line.getCombining(col) == null
    }

    /**
     * Which font draws [cp]. Private-use code points the bundled Nerd Font covers always use it
     * (system fonts may map unrelated glyphs there); other characters use the primary font,
     * then Jetendard (Hangul for fonts without it), then the icon font.
     */
    private fun glyphSource(cp: Int): Int {
        if (cp < 0x80) return SRC_PRIMARY
        val cached = glyphSources.get(cp, -1)
        if (cached >= 0) return cached
        val text = String(Character.toChars(cp))
        val source = when {
            NerdFontCoverage.isPrivateUse(cp) && NerdFontCoverage.covers(cp) -> SRC_ICON
            hasOwnGlyph(textPaint, text) -> SRC_PRIMARY
            hasFallback && hasOwnGlyph(fallbackPaint, text) -> SRC_FALLBACK
            // Text-presentation symbols the device fonts lack or draw as color emoji. Wide
            // characters (emoji) keep the system emoji font.
            WcWidth.width(cp) == 1 && hasOwnGlyph(symbolPaint, text) -> SRC_SYMBOL
            NerdFontCoverage.covers(cp) -> SRC_ICON
            else -> SRC_PRIMARY
        }
        glyphSources.put(cp, source)
        return source
    }

    /** The paint for a cell; an emoji variation selector (VS16) leaves the choice to the system emoji font. */
    private fun paintFor(source: Int, combining: String?): Paint = when {
        combining != null && combining.indexOf('\uFE0F') >= 0 -> textPaint
        source == SRC_FALLBACK -> fallbackPaint
        source == SRC_SYMBOL -> symbolPaint
        else -> textPaint
    }

    /**
     * Whether [paint]'s own font (not Android's system fallback) has a glyph for [text]. On
     * Android 12+ the text is shaped and the font that rendered it is compared with the font the
     * paint uses for ASCII. Older versions only have [Paint.hasGlyph], which counts the system
     * fallback fonts too.
     */
    private fun hasOwnGlyph(paint: Paint, text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return paint.hasGlyph(text)
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        if (glyphs.glyphCount() == 0 || glyphs.getGlyphId(0) == 0) return false
        val own = ownFonts.getOrPut(paint) {
            // A character the paint's own font surely has (Symbols 2 has no Latin letters).
            val probe = if (paint === symbolPaint) "\u23F5" else "M"
            TextRunShaper.shapeTextRun(probe, 0, probe.length, 0, probe.length, 0f, 0f, false, paint).getFont(0)
        }
        return glyphs.getFont(0) == own
    }

    private fun drawDecorations(canvas: Canvas, flags: Int, color: Int, left: Float, right: Float, top: Float) {
        fillPaint.color = color
        val stroke = max(1f, (cellHeight / 14f).roundToInt().toFloat())
        if (flags and (TextStyle.UNDERLINE or TextStyle.CURLY_UNDERLINE) != 0) {
            val uy = top + cellHeight - stroke * 1.5f
            canvas.drawRect(left, uy, right, uy + stroke, fillPaint)
        }
        if (flags and TextStyle.DOUBLE_UNDERLINE != 0) {
            val uy = top + cellHeight - stroke * 1.5f
            canvas.drawRect(left, uy, right, uy + stroke, fillPaint)
            canvas.drawRect(left, uy - stroke * 2, right, uy - stroke, fillPaint)
        }
        if (flags and TextStyle.STRIKETHROUGH != 0) {
            val sy = top + cellHeight * 0.55f
            canvas.drawRect(left, sy, right, sy + stroke, fillPaint)
        }
    }

    private fun isSimple(cp: Int): Boolean {
        if (cp in 0x21..0x7E) return asciiMonospace
        val i = advanceCache.indexOfKey(cp)
        if (i >= 0) return advanceCache.valueAt(i)
        val ok = WcWidth.width(cp) == 1 && glyphSource(cp) == SRC_PRIMARY &&
            abs(textPaint.measureText(String(Character.toChars(cp))) - cellWidth) < 0.5f
        advanceCache.put(cp, ok)
        return ok
    }

    private fun drawCursor(canvas: Canvas, em: TerminalEmulator, cols: Int) {
        if (!em.isCursorVisible) return
        val screenRow = em.cursorRow + scrollOffset
        if (screenRow !in 0 until min(rows, em.rows)) return
        val col = em.cursorCol.coerceIn(0, cols - 1)
        drawCursorAt(canvas, em, screenRow, col, em.getRow(em.cursorRow))
    }

    /** The cursor at a screen cell; [line] (the row under it) supplies the glyph a block cursor inverts. */
    private fun drawCursorAt(canvas: Canvas, em: TerminalEmulator, screenRow: Int, col: Int, line: TerminalRow?) {
        val wide = line != null && col < line.columns && line.isWide(col)
        val left = cellX(col)
        val right = cellX(col + if (wide) 2 else 1)
        val top = cellY(screenRow)
        val bottom = top + cellHeight
        val scheme = em.colorScheme
        fillPaint.color = em.cursorColor
        val focused = hasFocus()
        val blinkOff = cursorBlink && focused && !cursorVisiblePhase
        if (blinkOff) return
        if (!focused) {
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = max(1f, density)
            canvas.drawRect(left + 0.5f, top + 0.5f, right - 0.5f, bottom - 0.5f, fillPaint)
            fillPaint.style = Paint.Style.FILL
            return
        }
        when (em.cursorShape) {
            CursorShape.BLOCK -> {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                if (line != null && col < line.columns) {
                    val cp = line.getCodePoint(col)
                    if (cp > 0 && cp != ' '.code) {
                        val flags = TextStyle.flags(line.getStyle(col))
                        applyTextStyle(scheme.cursorText, flags)
                        val source = glyphSource(cp)
                        if (BoxDrawing.handles(cp)) {
                            fillPaint.color = scheme.cursorText
                            BoxDrawing.draw(canvas, cp, left, top, right - left, cellHeight, fillPaint)
                        } else if (source == SRC_ICON) {
                            drawIcon(canvas, String(Character.toChars(cp)), left, if (wide) 2 else 1, top + baseline)
                        } else {
                            var text = String(Character.toChars(cp))
                            line.getCombining(col)?.let { text = Normalizer.normalize(text + it, Normalizer.Form.NFC) }
                            drawCellText(canvas, text, left, right - left, top + baseline, paintFor(source, line.getCombining(col)))
                        }
                    }
                }
            }
            CursorShape.UNDERLINE -> {
                val h = max(2f, cellHeight / 8f)
                canvas.drawRect(left, bottom - h, right, bottom, fillPaint)
            }
            CursorShape.BAR -> {
                val w = max(2f, cellWidth / 6f)
                canvas.drawRect(left, top, left + w, bottom, fillPaint)
            }
        }
    }

    /**
     * Text that is on its way to the remote, drawn at the cursor exactly like terminal text and
     * wrapping at the right edge, followed by the cursor: [echo] (sent, echo not yet arrived)
     * and then the IME composition [preedit] (not sent yet). Composing text deliberately looks
     * like any other text (no underline or highlight).
     */
    private fun drawInputOverlay(canvas: Canvas, em: TerminalEmulator, echo: String, preedit: String, cols: Int, defaultBg: Int) {
        var screenRow = em.cursorRow + scrollOffset
        var col = em.cursorCol.coerceIn(0, cols - 1)
        if (em.isCursorPendingWrap) {
            // The next character would land on the following line.
            col = 0
            screenRow++
        }
        em.resolveColors(TextStyle.DEFAULT, colors)
        val fg = colors[0]
        val text = echo + preedit
        var i = 0
        while (i < text.length && screenRow < rows) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val w = WcWidth.width(cp).coerceAtLeast(1)
            if (col + w > cols) {
                col = 0
                screenRow++
                if (screenRow >= rows) break
            }
            val left = cellX(col)
            val right = cellX(col + w)
            val top = cellY(screenRow)
            fillPaint.color = defaultBg
            canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
            applyTextStyle(fg, 0)
            val glyph = String(Character.toChars(cp))
            when (glyphSource(cp)) {
                SRC_ICON -> drawIcon(canvas, glyph, left, w, top + baseline)
                SRC_FALLBACK, SRC_SYMBOL -> drawCellText(canvas, glyph, left, right - left, top + baseline, paintFor(glyphSource(cp), null))
                else -> drawCellText(canvas, glyph, left, right - left, top + baseline)
            }
            col += w
        }
        if (col >= cols) {
            col = 0
            screenRow++
        }
        if (screenRow < rows && em.isCursorVisible) drawCursorAt(canvas, em, screenRow, col, line = null)
    }

    // =====================================================================================
    // Predicted echo
    // =====================================================================================

    /**
     * Text sent to the remote whose echo has not shown up yet, one entry per send. Committed
     * IME text is kept on screen here so a word does not vanish (and the caret jump back to
     * its start) for a network round trip between commit and echo.
     */
    private val pendingEcho = ArrayDeque<String>()

    /** [linearCursor] when the oldest [pendingEcho] entry was sent. */
    private var echoAnchor = 0L
    private var echoDeadline = 0L
    private val echoExpiry = Runnable { invalidate() }

    /** The cursor as a cell index counted from the first line (stable while output scrolls). */
    private fun linearCursor(em: TerminalEmulator): Long =
        (em.scrolledLines + em.cursorRow) * em.columns + em.cursorCol + if (em.isCursorPendingWrap) 1 else 0

    private fun predictEcho(text: String) {
        val be = backend ?: return
        if (pendingEcho.isEmpty()) echoAnchor = synchronized(be.lock) { linearCursor(be.emulator) }
        pendingEcho.addLast(text)
        echoDeadline = SystemClock.uptimeMillis() + ECHO_TIMEOUT_MS
        removeCallbacks(echoExpiry)
        postDelayed(echoExpiry, ECHO_TIMEOUT_MS)
    }

    private fun clearPredictedEcho() {
        if (pendingEcho.isEmpty()) return
        pendingEcho.clear()
        removeCallbacks(echoExpiry)
        invalidate()
    }

    /**
     * Drops the predicted text the remote has echoed by now, judged by how far the cursor
     * advanced. Anything else — the cursor moved backwards, to another place, or by an amount
     * that is not a whole number of entries — or no echo within [ECHO_TIMEOUT_MS] ends the
     * prediction (e.g. a password prompt, or a TUI that does not echo).
     */
    private fun reconcileEcho(em: TerminalEmulator) {
        if (pendingEcho.isEmpty()) return
        if (SystemClock.uptimeMillis() >= echoDeadline) {
            pendingEcho.clear()
            return
        }
        val now = linearCursor(em)
        var advance = now - echoAnchor
        if (advance == 0L) return
        while (advance > 0 && pendingEcho.isNotEmpty()) {
            val cells = textCells(pendingEcho.first())
            if (cells > advance) break
            advance -= cells
            pendingEcho.removeFirst()
        }
        if (advance == 0L) echoAnchor = now else pendingEcho.clear()
    }

    private fun textCells(text: String): Int {
        var cells = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            cells += WcWidth.width(cp).coerceAtLeast(0)
        }
        return cells
    }

    // =====================================================================================
    // Cursor blink
    // =====================================================================================

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - lastInputTime < BLINK_INTERVAL_MS) {
                cursorVisiblePhase = true
            } else {
                cursorVisiblePhase = !cursorVisiblePhase
            }
            invalidate()
            postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    private fun restartBlink() {
        removeCallbacks(blinkRunnable)
        cursorVisiblePhase = true
        if (cursorBlink && isAttachedToWindow && hasFocus()) postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        actionMode?.finish()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restartBlink()
        val be = backend ?: return
        val seq = synchronized(be.lock) { be.emulator.encodeFocus(gainFocus) }
        if (seq != null) be.write(seq)
    }

    // =====================================================================================
    // IME
    // =====================================================================================

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Deliberately NOT TYPE_TEXT_VARIATION_VISIBLE_PASSWORD (Korean keyboards switch to
        // English-only for password fields) or TYPE_NULL (no composition: Hangul arrives as
        // separate jamo). NO_SUGGESTIONS keeps composition but disables prediction and
        // auto-correction on most keyboards.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            // Opt-in only: Gboard treats it as incognito mode and disables voice typing.
            (if (imeIncognito) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        outAttrs.initialCapsMode = 0
        imeProcessor.reset()
        val ic = TerminalInputConnection(this, imeProcessor, object : TerminalInputConnection.Callbacks {
            override fun onImeKeyEvent(event: KeyEvent) {
                @Suppress("DEPRECATION")
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> handleKeyDown(event, fromIme = true)
                    KeyEvent.ACTION_MULTIPLE -> handleKeyMultiple(event)
                }
            }

            override fun onImeEditorAction() = sendKey(TerminalKey.ENTER)

            override fun onImePaste() = pasteFromClipboard()
        })
        inputConnection = ic
        return ic
    }

    /** Sends the IME's pending composition (if any) and resets the IME. */
    fun commitImeComposition() {
        val ic = inputConnection ?: return
        if (commitComposition(ic)) ic.resetEditable(restartIme = true)
    }

    /** True while a pending composition is sent: it was typed before any sticky modifier. */
    private var committingComposition = false

    /** Sends the IME's pending composition without applying sticky CTRL/ALT/SHIFT to it. */
    private fun commitComposition(ic: TerminalInputConnection): Boolean {
        committingComposition = true
        try {
            return ic.commitComposition()
        } finally {
            committingComposition = false
        }
    }

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleKeyboard() {
        val insets = rootWindowInsets
        val visible = insets != null &&
            WindowInsetsCompat.toWindowInsetsCompat(insets, this).isVisible(WindowInsetsCompat.Type.ime())
        if (visible) hideKeyboard() else showKeyboard()
    }

    // =====================================================================================
    // Sending input
    // =====================================================================================

    private fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        backend?.write(bytes)
    }

    private fun backspaceByte(): Byte = if (backspaceSendsCtrlH) 0x08 else 0x7F

    private fun noteUserInput() {
        lastInputTime = SystemClock.uptimeMillis()
        cursorVisiblePhase = true
        scrollToBottom()
        if (hasSelection()) clearSelection()
        onUserInput?.invoke()
    }

    /**
     * Writes typed text, applying sticky CTRL/ALT to its first character when [allowModifiers]
     * (a Hangul jamo is mapped to its 2-set QWERTY key first, so CTRL + ㅊ is Ctrl-C).
     * @return true if modifiers were applied.
     */
    private fun writeTyped(text: String, allowModifiers: Boolean): Boolean {
        val be = backend ?: return false
        if (text.isEmpty()) return false
        val mods = if (allowModifiers && !committingComposition) modifiers.bits() else 0
        if (mods == 0) {
            write(text.toByteArray(Charsets.UTF_8))
            return false
        }
        val original = text.codePointAt(0)
        var cp = original
        if (mods and KeyMod.SHIFT != 0) cp = Character.toUpperCase(cp)
        val ctrlAlt = mods and (KeyMod.CTRL or KeyMod.ALT)
        val first = if (ctrlAlt != 0) {
            val mapped = HangulKeymap.toLatin(cp)?.code ?: cp
            synchronized(be.lock) { be.emulator.encodeCodePoint(mapped, ctrlAlt) }
        } else {
            String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
        }
        modifiers.consume()
        write(first)
        val rest = text.substring(Character.charCount(original))
        if (rest.isNotEmpty()) write(rest.toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * Input that changes the remote line in ways the IME cannot follow (Enter, arrows, Tab,
     * control keys, paste, clicks): the pending composition is sent first and the IME's text
     * field is reset afterwards, so later backspaces/recompositions start from a clean slate.
     */
    private inline fun lineChangingInput(send: () -> Unit) {
        val ic = inputConnection
        val hadComposition = ic != null && commitComposition(ic)
        send()
        ic?.resetEditable(restartIme = hadComposition)
        noteUserInput()
    }

    /** Text from the extra-keys row or a hardware key: typed into the field like IME text. */
    fun sendTypedText(text: String) {
        if (text.isEmpty() || backend == null) return
        val ic = inputConnection
        when {
            modifiers.isAnyActive || ic == null -> lineChangingInput { writeTyped(text, allowModifiers = true) }
            ic.isComposing -> {
                // Finish the syllable first; the IME still thinks it is composing, so restart it.
                commitComposition(ic)
                writeTyped(text, allowModifiers = false)
                ic.resetEditable(restartIme = true)
                noteUserInput()
            }
            else -> ic.insertText(text) // mirrored to the remote by the processor
        }
    }

    /** A special key from the extra-keys row or a hardware keyboard; sticky modifiers apply. */
    fun sendKey(key: TerminalKey, extraModifiers: Int = 0, fromIme: Boolean = false) {
        val be = backend ?: return
        val mods = modifiers.bits() or extraModifiers
        if (key == TerminalKey.BACKSPACE && mods == 0) {
            val ic = inputConnection
            if (ic != null && !ic.hasComposingSpan) {
                val softComposing = ic.isComposing
                if (ic.deleteBackward()) {
                    // The mirrored deletion already sent the DEL (or nothing, for a soft-composed
                    // syllable that was never sent). If that syllable went away behind the IME's
                    // back (extra-keys ⌫ rather than the IME's own DEL), restart the IME.
                    if (softComposing && !fromIme) ic.resetEditable(restartIme = true)
                    noteUserInput()
                    return
                }
            }
            if (ic != null && ic.hasComposingSpan) {
                lineChangingInput { write(byteArrayOf(backspaceByte())) }
            } else {
                write(byteArrayOf(backspaceByte()))
                noteUserInput()
            }
            return
        }
        lineChangingInput {
            val bytes = synchronized(be.lock) { be.emulator.encodeKey(key, mods) }
            if (modifiers.isAnyActive) modifiers.consume()
            write(bytes)
        }
    }

    /** Sends [text] exactly as given (extra-key macros containing Enter, ESC, ...). */
    fun sendRaw(text: String) {
        if (text.isEmpty() || backend == null) return
        lineChangingInput { write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Ctrl+[letter] as a single key (e.g. ^C); a sticky ALT is combined with it. */
    fun sendCtrl(letter: Char) {
        val be = backend ?: return
        lineChangingInput {
            val mods = KeyMod.CTRL or (modifiers.bits() and KeyMod.ALT)
            val bytes = synchronized(be.lock) { be.emulator.encodeCodePoint(letter.code, mods) }
            if (modifiers.isAnyActive) modifiers.consume()
            write(bytes)
        }
    }

    fun paste(text: String) {
        val be = backend ?: return
        if (text.isEmpty()) return
        lineChangingInput {
            val bytes = synchronized(be.lock) { be.emulator.encodePaste(text) }
            write(bytes)
        }
    }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        paste(text)
    }

    // =====================================================================================
    // Hardware keys
    // =====================================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyDown(event, fromIme = false) || super.onKeyDown(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event)

    @Suppress("DEPRECATION") // Some IMEs still deliver text as ACTION_MULTIPLE events.
    private fun handleKeyMultiple(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) return false
        val chars = event.characters ?: return false
        sendTypedText(chars.replace("\r\n", "\r").replace('\n', '\r'))
        return true
    }

    private fun handleKeyDown(event: KeyEvent, fromIme: Boolean): Boolean {
        val be = backend ?: return false
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK || event.isSystem && mapKey(keyCode) == null) return false
        var mods = 0
        if (event.isShiftPressed) mods = mods or KeyMod.SHIFT
        if (event.isAltPressed) mods = mods or KeyMod.ALT
        if (event.isCtrlPressed) mods = mods or KeyMod.CTRL

        val key = mapKey(keyCode)
        if (key != null) {
            sendKey(key, mods, fromIme)
            return true
        }
        // Character keys (hardware keyboard, or IMEs that send key events, e.g. digits).
        val metaForChar = if (event.isShiftPressed || event.isCapsLockOn) KeyEvent.META_SHIFT_ON else 0
        val unicode = if (mods and (KeyMod.CTRL or KeyMod.ALT) != 0) event.getUnicodeChar(metaForChar)
        else event.getUnicodeChar(event.metaState)
        if (unicode == 0) return false
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return true // dead keys unsupported
        val stickyMods = modifiers.bits()
        val char = if (stickyMods and KeyMod.SHIFT != 0) Character.toUpperCase(unicode) else unicode
        val allMods = (mods or stickyMods) and (KeyMod.CTRL or KeyMod.ALT)
        if (allMods != 0 || stickyMods != 0) {
            lineChangingInput {
                val bytes = if (allMods != 0) {
                    synchronized(be.lock) { be.emulator.encodeCodePoint(char, allMods) }
                } else {
                    String(Character.toChars(char)).toByteArray(Charsets.UTF_8)
                }
                if (stickyMods != 0) modifiers.consume()
                write(bytes)
            }
        } else {
            sendTypedText(String(Character.toChars(unicode)))
        }
        return true
    }

    private fun mapKey(keyCode: Int): TerminalKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
        KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
        KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.ENTER
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
        KeyEvent.KEYCODE_F1 -> TerminalKey.F1
        KeyEvent.KEYCODE_F2 -> TerminalKey.F2
        KeyEvent.KEYCODE_F3 -> TerminalKey.F3
        KeyEvent.KEYCODE_F4 -> TerminalKey.F4
        KeyEvent.KEYCODE_F5 -> TerminalKey.F5
        KeyEvent.KEYCODE_F6 -> TerminalKey.F6
        KeyEvent.KEYCODE_F7 -> TerminalKey.F7
        KeyEvent.KEYCODE_F8 -> TerminalKey.F8
        KeyEvent.KEYCODE_F9 -> TerminalKey.F9
        KeyEvent.KEYCODE_F10 -> TerminalKey.F10
        KeyEvent.KEYCODE_F11 -> TerminalKey.F11
        KeyEvent.KEYCODE_F12 -> TerminalKey.F12
        else -> null
    }

    // =====================================================================================
    // Touch: tap, scroll, fling, pinch-zoom, selection
    // =====================================================================================

    private val scroller = OverScroller(context)
    private var flingLastY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollRemainder = 0f
            lastTouchX = e.x
            lastTouchY = e.y
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (hasSelection()) {
                clearSelection()
                return true
            }
            sendMouseClick(e)
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollByPixels(distanceY * scrollSpeed * SCROLL_BASE)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val be = backend ?: return false
            val historyScroll = synchronized(be.lock) {
                !be.emulator.isAlternateScreen && be.emulator.mouseTrackingMode == MouseTrackingMode.NONE
            }
            // In TUIs (tmux, vim, less) scrolling sends wheel events or arrow keys to the remote:
            // keep gliding only after a decidedly fast swipe.
            if (!historyScroll && abs(velocityY) < REMOTE_FLING_MIN_VELOCITY_DP * density) return false
            flingLastY = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaleDetector.isInProgress) return
            startSelection(e.x, e.y)
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newSize = (fontSizeSp * detector.scaleFactor).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (abs(newSize - fontSizeSp) >= 0.1f) applyFontSize(newSize)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onFontSizeChanged?.invoke((fontSizeSp * 2).roundToInt() / 2f)
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            scrollByPixels((y - flingLastY) * scrollSpeed * SCROLL_BASE)
            flingLastY = y
            postInvalidateOnAnimation()
        }
    }

    private fun scrollByPixels(dy: Float) {
        val be = backend ?: return
        scrollRemainder += dy
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val em = be.emulator
        val seq: ByteArray? = synchronized(be.lock) {
            when {
                em.mouseTrackingMode != MouseTrackingMode.NONE -> {
                    val action = if (lines > 0) MouseAction.WHEEL_DOWN else MouseAction.WHEEL_UP
                    val col = ((lastTouchX - padX) / cellWidth).toInt().coerceIn(0, columns - 1)
                    val row = ((lastTouchY - padY) / cellHeight).toInt().coerceIn(0, rows - 1)
                    val one = em.encodeMouse(action, MouseButton.NONE, col, row, 0)
                    one?.let { repeatBytes(it, abs(lines)) }
                }
                em.isAlternateScreen -> {
                    val one = em.encodeKey(if (lines > 0) TerminalKey.DOWN else TerminalKey.UP, 0)
                    repeatBytes(one, abs(lines))
                }
                else -> {
                    val before = scrollOffset
                    scrollOffset = (scrollOffset - lines).coerceIn(0, em.historySize)
                    if (scrollOffset == before) scroller.forceFinished(true) // hit the top or bottom
                    null
                }
            }
        }
        if (seq != null) lineChangingInput { write(seq) } else invalidate()
    }

    private fun repeatBytes(b: ByteArray, n: Int): ByteArray {
        val out = ByteArray(b.size * n)
        for (i in 0 until n) System.arraycopy(b, 0, out, i * b.size, b.size)
        return out
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            invalidate()
        }
    }

    private fun sendMouseClick(e: MotionEvent) {
        val be = backend ?: return
        val col = ((e.x - padX) / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = ((e.y - padY) / cellHeight).toInt().coerceIn(0, rows - 1)
        val bytes = synchronized(be.lock) {
            val em = be.emulator
            if (em.mouseTrackingMode == MouseTrackingMode.NONE) return
            val press = em.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, col, row, 0)
            val release = em.encodeMouse(MouseAction.RELEASE, MouseButton.LEFT, col, row, 0)
            (press ?: ByteArray(0)) + (release ?: ByteArray(0))
        }
        // A click may move the cursor inside the application: the IME's field is stale.
        lineChangingInput { write(bytes) }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            lastTouchX = event.x
            lastTouchY = event.y
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            scrollByPixels(-v * cellHeight * 3)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- selection -------------------------------------------------------------------

    /** Absolute line numbers (scrolledLines + row) keep the selection fixed while output scrolls. */
    private var selStartLine = 0L
    private var selStartCol = 0
    private var selEndLine = 0L
    private var selEndCol = 0
    private var selecting = false

    /** Which stored anchor follows the finger: [ANCHOR_START] (selStart*) or [ANCHOR_END] (selEnd*). */
    private var dragAnchor = ANCHOR_NONE
    private var actionMode: ActionMode? = null

    private fun hasSelection() = selecting

    private fun clearSelection() {
        if (!selecting) return
        selecting = false
        dragAnchor = ANCHOR_NONE
        actionMode?.finish()
        invalidate()
    }

    private fun cellAt(x: Float, y: Float): Pair<Long, Int>? {
        val be = backend ?: return null
        val em = be.emulator
        val col = ((x - padX) / cellWidth).toInt().coerceIn(0, columns - 1)
        val screenRow = ((y - padY) / cellHeight).toInt().coerceIn(0, rows - 1)
        val row = (screenRow - scrollOffset).coerceIn(-em.historySize, em.rows - 1)
        return (em.scrolledLines + row) to col
    }

    private fun startSelection(x: Float, y: Float) {
        val be = backend ?: return
        synchronized(be.lock) {
            val (line, col) = cellAt(x, y) ?: return
            val em = be.emulator
            val row = (line - em.scrolledLines).toInt()
            val r = em.getRow(row)
            var start = col.coerceAtMost(r.columns - 1)
            var end = start
            if (r.getCodePoint(start) == TerminalRow.WIDE_SPACER && start > 0) start--
            if (isWordCell(r, start)) {
                while (start > 0 && isWordCell(r, start - 1)) start--
                while (end + 1 < r.columns && isWordCell(r, end + 1)) end++
            }
            if (end + 1 < r.columns && r.getCodePoint(end + 1) == TerminalRow.WIDE_SPACER) end++
            selStartLine = line; selStartCol = start
            selEndLine = line; selEndCol = end
        }
        selecting = true
        dragAnchor = ANCHOR_END
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun isWordCell(r: TerminalRow, c: Int): Boolean {
        val cp = r.getCodePoint(c)
        if (cp == TerminalRow.WIDE_SPACER) return true
        if (cp <= ' '.code) return false
        return WORD_DELIMITERS.indexOf(cp.toChar()) < 0 || cp > 0xFFFF
    }

    /** Ordered selection: (startLine, startCol, endLine, endColInclusive). */
    private fun orderedSelection(): LongArray {
        val forward = selStartLine < selEndLine || (selStartLine == selEndLine && selStartCol <= selEndCol)
        return if (forward) longArrayOf(selStartLine, selStartCol.toLong(), selEndLine, selEndCol.toLong())
        else longArrayOf(selEndLine, selEndCol.toLong(), selStartLine, selStartCol.toLong())
    }

    private fun drawSelection(canvas: Canvas, em: TerminalEmulator) {
        if (!selecting) return
        val s = orderedSelection()
        fillPaint.color = em.colorScheme.selection
        val firstVisible = em.scrolledLines - scrollOffset
        for (screenRow in 0 until min(rows, em.rows)) {
            val line = firstVisible + screenRow
            if (line < s[0] || line > s[2]) continue
            val from = if (line == s[0]) s[1].toInt() else 0
            val to = if (line == s[2]) s[3].toInt() + 1 else columns
            canvas.drawRect(cellX(from), cellY(screenRow), cellX(to), cellY(screenRow) + cellHeight, fillPaint)
        }
        // Teardrop handles (like Android's text handles) hanging below the selection edges,
        // the pointed corner touching the edge.
        handlePaint.color = em.cursorColor
        val r = HANDLE_RADIUS_DP * density
        val startRow = (s[0] - firstVisible).toInt()
        val endRow = (s[2] - firstVisible).toInt()
        if (startRow in 0 until rows) {
            val x = cellX(s[1].toInt())
            val y = cellY(startRow) + cellHeight
            canvas.drawRect(x - 1f, cellY(startRow), x + 1f, y, handlePaint)
            canvas.drawCircle(x - r, y + r, r, handlePaint)
            canvas.drawRect(x - r, y, x, y + r, handlePaint)
        }
        if (endRow in 0 until rows) {
            val x = cellX(s[3].toInt() + 1)
            val y = cellY(endRow) + cellHeight
            canvas.drawRect(x - 1f, cellY(endRow), x + 1f, y, handlePaint)
            canvas.drawCircle(x + r, y + r, r, handlePaint)
            canvas.drawRect(x, y, x + r, y + r, handlePaint)
        }
    }

    private fun handleHit(x: Float, y: Float): Int {
        val be = backend ?: return DRAG_NONE
        val em = be.emulator
        val s = orderedSelection()
        val firstVisible = em.scrolledLines - scrollOffset
        val r = HANDLE_RADIUS_DP * density
        val slop = r * 2.5f
        val sx = cellX(s[1].toInt()) - r
        val sy = cellY((s[0] - firstVisible).toInt()) + cellHeight + r
        val ex = cellX(s[3].toInt() + 1) + r
        val ey = cellY((s[2] - firstVisible).toInt()) + cellHeight + r
        val ds = (x - sx) * (x - sx) + (y - sy) * (y - sy)
        val de = (x - ex) * (x - ex) + (y - ey) * (y - ey)
        return when {
            de <= slop * slop && de <= ds -> DRAG_END
            ds <= slop * slop -> DRAG_START
            else -> DRAG_NONE
        }
    }

    private fun selectedText(): String {
        val be = backend ?: return ""
        return synchronized(be.lock) {
            val em = be.emulator
            val s = orderedSelection()
            val startRow = (s[0] - em.scrolledLines).toInt().coerceAtLeast(-em.historySize)
            val endRow = (s[2] - em.scrolledLines).toInt().coerceAtMost(em.rows - 1)
            if (endRow < startRow) return@synchronized ""
            val endLine = em.getRow(endRow)
            var endCol = s[3].toInt().coerceAtMost(endLine.columns - 1)
            endCol += if (endCol >= 0 && endLine.isWide(endCol)) 2 else 1
            em.getText(startRow, s[1].toInt(), endRow, endCol)
        }
    }

    private fun selectAll() {
        val be = backend ?: return
        synchronized(be.lock) {
            val em = be.emulator
            selStartLine = em.scrolledLines - em.historySize
            selStartCol = 0
            selEndLine = em.scrolledLines + em.rows - 1
            selEndCol = em.columns - 1
        }
        selecting = true
        invalidate()
        showSelectionActions()
    }

    private fun copySelection() {
        val text = selectedText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        clearSelection()
    }

    private fun showSelectionActions() {
        actionMode?.let { it.invalidateContentRect(); return }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
                menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> copySelection()
                    MENU_PASTE -> {
                        clearSelection()
                        pasteFromClipboard()
                    }
                    MENU_SELECT_ALL -> selectAll()
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val be = backend
                if (be == null || !selecting) {
                    outRect.set(0, 0, width, height / 3)
                    return
                }
                val em = be.emulator
                val s = orderedSelection()
                val firstVisible = em.scrolledLines - scrollOffset
                val top = cellY((s[0] - firstVisible).toInt().coerceIn(0, rows - 1))
                val bottom = cellY((s[2] - firstVisible).toInt().coerceIn(0, rows - 1)) + cellHeight
                val left = if (s[0] == s[2]) cellX(s[1].toInt()) else padX.toFloat()
                val right = if (s[0] == s[2]) cellX(s[3].toInt() + 1) else width - padX.toFloat()
                tmpRect.set(left, top, right, bottom)
                outRect.set(tmpRect.left.toInt(), tmpRect.top.toInt(), tmpRect.right.toInt(), tmpRect.bottom.toInt())
            }
        }, ActionMode.TYPE_FLOATING)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (backend == null) return super.onTouchEvent(event)
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // Mouse: let the gesture detector handle clicks/scroll like touch.
            gestureDetector.onTouchEvent(event)
            return true
        }
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            dragAnchor = ANCHOR_NONE
            return true
        }
        if (selecting && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val hit = handleHit(event.x, event.y)
            if (hit != DRAG_NONE) {
                // Map the visual handle onto whichever stored anchor is currently drawn there.
                val s = orderedSelection()
                val startIsFirst = s[0] == selStartLine && s[1].toInt() == selStartCol
                dragAnchor = if ((hit == DRAG_START) == startIsFirst) ANCHOR_START else ANCHOR_END
                actionMode?.hide(Long.MAX_VALUE)
                return true
            }
        }
        if (selecting && dragAnchor != ANCHOR_NONE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val be = backend!!
                    val cell = synchronized(be.lock) { cellAt(event.x, event.y - HANDLE_RADIUS_DP * density) }
                    if (cell != null) {
                        if (dragAnchor == ANCHOR_START) {
                            selStartLine = cell.first; selStartCol = cell.second
                        } else {
                            selEndLine = cell.first; selEndCol = cell.second
                        }
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragAnchor = ANCHOR_NONE
                    showSelectionActions()
                    actionMode?.hide(0)
                    actionMode?.invalidateContentRect()
                }
            }
            // Keep the long-press drag flowing to the detector so it resets its state.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureDetector.onTouchEvent(event)
            }
            return true
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        /**
         * Height in pixels that shows exactly [rows] rows of [typeface] at [sizeSp] in a view of this
         * class, so a view can be sized for any font before it shows one.
         */
        fun heightForRows(context: Context, typeface: Typeface, sizeSp: Float, rows: Int): Int {
            val metrics = context.resources.displayMetrics
            val paint = Paint().apply {
                this.typeface = typeface
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
            }
            return ceil(rows * lineHeight(paint.fontMetrics) + 2 * padY(metrics.density)).toInt()
        }

        /** A row is as tall as the font's ascent plus descent. */
        private fun lineHeight(fm: Paint.FontMetrics): Float = ceil(fm.descent - fm.ascent)

        /** Blank space above the first and below the last row. */
        private fun padY(density: Float): Int = (2 * density).roundToInt()

        private const val BLINK_INTERVAL_MS = 600L

        /** Memory for decoded inline images, and the largest side they are decoded at. */
        private const val IMAGE_CACHE_BYTES = 32 shl 20
        private const val MAX_IMAGE_SIDE = 2048

        /** How long predicted echo stays without the remote echoing it. */
        private const val ECHO_TIMEOUT_MS = 2500L

        /** Minimum swipe speed (dp/s) to keep scrolling a TUI after the finger lifts. */
        private const val REMOTE_FLING_MIN_VELOCITY_DP = 1000f
        private const val DECORATIONS = TextStyle.UNDERLINE or TextStyle.DOUBLE_UNDERLINE or
            TextStyle.CURLY_UNDERLINE or TextStyle.STRIKETHROUGH
        private const val WORD_DELIMITERS = "\"'`()[]{}<>|;,"
        private const val HANDLE_RADIUS_DP = 9f
        private const val DRAG_NONE = 0
        private const val DRAG_START = 1
        private const val DRAG_END = 2
        private const val SRC_PRIMARY = 0
        private const val SRC_FALLBACK = 1
        private const val SRC_ICON = 2
        private const val SRC_SYMBOL = 3

        /** Widest a symbol spilling into the blank cell after it may get, in cells. */
        private const val SPILL_FIT = 1.5f
        private const val ANCHOR_NONE = 0
        private const val ANCHOR_START = 1
        private const val ANCHOR_END = 2
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val MENU_SELECT_ALL = 3
        const val MIN_FONT_SP = AppSettings.MIN_FONT_SP
        const val MAX_FONT_SP = AppSettings.MAX_FONT_SP

        /** Content moved per pixel of finger travel at scroll speed 100 %. */
        private const val SCROLL_BASE = 0.75f

        /** Font feature settings (CSS syntax) that turn programming ligatures off. */
        private const val NO_LIGATURES = "'liga' 0, 'calt' 0"
    }
}
