// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.termview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Procedural rendering of box-drawing (U+2500–U+257F), block elements (U+2580–U+259F) and the
 * basic Powerline separators (U+E0B0–U+E0B6). Fonts draw these with gaps between rows (the
 * cell is taller than the glyph), which breaks tmux borders, htop meters, mc frames, etc.
 */
internal object BoxDrawing {
    private const val FONT = -1
    private const val ARC = -2
    private const val DIAG = -3

    private fun b(u: Int, d: Int, l: Int, r: Int) = (u shl 6) or (d shl 4) or (l shl 2) or r

    /** Arm weights per char of U+2500..U+257F: 0 none, 1 light, 2 heavy, 3 double. */
    private val LINES: IntArray = intArrayOf(
        b(0, 0, 1, 1), b(0, 0, 2, 2), b(1, 1, 0, 0), b(2, 2, 0, 0), // 2500-2503
        FONT, FONT, FONT, FONT, FONT, FONT, FONT, FONT, // 2504-250B dashes
        b(0, 1, 0, 1), b(0, 1, 0, 2), b(0, 2, 0, 1), b(0, 2, 0, 2), // 250C-250F
        b(0, 1, 1, 0), b(0, 1, 2, 0), b(0, 2, 1, 0), b(0, 2, 2, 0), // 2510-2513
        b(1, 0, 0, 1), b(1, 0, 0, 2), b(2, 0, 0, 1), b(2, 0, 0, 2), // 2514-2517
        b(1, 0, 1, 0), b(1, 0, 2, 0), b(2, 0, 1, 0), b(2, 0, 2, 0), // 2518-251B
        b(1, 1, 0, 1), b(1, 1, 0, 2), b(2, 1, 0, 1), b(1, 2, 0, 1), // 251C-251F
        b(2, 2, 0, 1), b(2, 1, 0, 2), b(1, 2, 0, 2), b(2, 2, 0, 2), // 2520-2523
        b(1, 1, 1, 0), b(1, 1, 2, 0), b(2, 1, 1, 0), b(1, 2, 1, 0), // 2524-2527
        b(2, 2, 1, 0), b(2, 1, 2, 0), b(1, 2, 2, 0), b(2, 2, 2, 0), // 2528-252B
        b(0, 1, 1, 1), b(0, 1, 2, 1), b(0, 1, 1, 2), b(0, 1, 2, 2), // 252C-252F
        b(0, 2, 1, 1), b(0, 2, 2, 1), b(0, 2, 1, 2), b(0, 2, 2, 2), // 2530-2533
        b(1, 0, 1, 1), b(1, 0, 2, 1), b(1, 0, 1, 2), b(1, 0, 2, 2), // 2534-2537
        b(2, 0, 1, 1), b(2, 0, 2, 1), b(2, 0, 1, 2), b(2, 0, 2, 2), // 2538-253B
        b(1, 1, 1, 1), b(1, 1, 2, 1), b(1, 1, 1, 2), b(1, 1, 2, 2), // 253C-253F
        b(2, 1, 1, 1), b(1, 2, 1, 1), b(2, 2, 1, 1), b(2, 1, 2, 1), // 2540-2543
        b(2, 1, 1, 2), b(1, 2, 2, 1), b(1, 2, 1, 2), b(2, 1, 2, 2), // 2544-2547
        b(1, 2, 2, 2), b(2, 2, 2, 1), b(2, 2, 1, 2), b(2, 2, 2, 2), // 2548-254B
        FONT, FONT, FONT, FONT, // 254C-254F dashes
        b(0, 0, 3, 3), b(3, 3, 0, 0), b(0, 1, 0, 3), b(0, 3, 0, 1), // 2550-2553
        b(0, 3, 0, 3), b(0, 1, 3, 0), b(0, 3, 1, 0), b(0, 3, 3, 0), // 2554-2557
        b(1, 0, 0, 3), b(3, 0, 0, 1), b(3, 0, 0, 3), b(1, 0, 3, 0), // 2558-255B
        b(3, 0, 1, 0), b(3, 0, 3, 0), b(1, 1, 0, 3), b(3, 3, 0, 1), // 255C-255F
        b(3, 3, 0, 3), b(1, 1, 3, 0), b(3, 3, 1, 0), b(3, 3, 3, 0), // 2560-2563
        b(0, 1, 3, 3), b(0, 3, 1, 1), b(0, 3, 3, 3), b(1, 0, 3, 3), // 2564-2567
        b(3, 0, 1, 1), b(3, 0, 3, 3), b(1, 1, 3, 3), b(3, 3, 1, 1), // 2568-256B
        b(3, 3, 3, 3), ARC, ARC, ARC, // 256C-256F
        ARC, DIAG, DIAG, DIAG, // 2570-2573
        b(0, 0, 1, 0), b(1, 0, 0, 0), b(0, 0, 0, 1), b(0, 1, 0, 0), // 2574-2577
        b(0, 0, 2, 0), b(2, 0, 0, 0), b(0, 0, 0, 2), b(0, 2, 0, 0), // 2578-257B
        b(0, 0, 1, 2), b(1, 2, 0, 0), b(0, 0, 2, 1), b(2, 1, 0, 0), // 257C-257F
    )

    private val path = Path()
    private val rect = RectF()

    fun handles(cp: Int): Boolean = when (cp) {
        in 0x2500..0x257F -> LINES[cp - 0x2500] != FONT
        in 0x2580..0x259F -> true
        in 0xE0B0..0xE0B3, 0xE0B4, 0xE0B6 -> true
        else -> false
    }

    /** Draws [cp] into the cell at ([x],[y]) of size [w]×[h] with [paint]'s color. */
    fun draw(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint) {
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth
        val oldAlpha = paint.alpha
        paint.style = Paint.Style.FILL
        when (cp) {
            in 0x2500..0x257F -> drawLines(canvas, cp, x, y, w, h, paint)
            in 0x2580..0x259F -> drawBlock(canvas, cp, x, y, w, h, paint)
            else -> drawPowerline(canvas, cp, x, y, w, h, paint)
        }
        paint.style = oldStyle
        paint.strokeWidth = oldStroke
        paint.alpha = oldAlpha
    }

    private fun lightWidth(w: Float): Float = max(1f, (w / 8f).roundToInt().toFloat())

    private fun drawLines(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint) {
        val spec = LINES[cp - 0x2500]
        val t1 = lightWidth(w)
        // Snap the axes to whole pixels so adjacent cells line up exactly.
        val cx = floor(x + w / 2f)
        val cy = floor(y + h / 2f)
        val right = x + w
        val bottom = y + h
        when (spec) {
            ARC -> {
                drawArc(canvas, cp, x, y, right, bottom, cx, cy, t1, paint)
                return
            }
            DIAG -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = t1
                if (cp == 0x2571 || cp == 0x2573) canvas.drawLine(x, bottom, right, y, paint)
                if (cp == 0x2572 || cp == 0x2573) canvas.drawLine(x, y, right, bottom, paint)
                return
            }
        }
        val u = (spec shr 6) and 3
        val d = (spec shr 4) and 3
        val l = (spec shr 2) and 3
        val r = spec and 3
        val t2 = max(t1 + 1f, t1 * 2f)
        val g = max(t1, floor(w / 5f)) // half the gap of double lines

        fun thick(weight: Int) = if (weight == 2) t2 else t1

        val anyDoubleH = l == 3 || r == 3
        val anyDoubleV = u == 3 || d == 3
        // Half thickness of the perpendicular axis, to fill junctions.
        val vHalf = max(if (u in 1..2) thick(u) else 0f, if (d in 1..2) thick(d) else 0f) / 2f
        val hHalf = max(if (l in 1..2) thick(l) else 0f, if (r in 1..2) thick(r) else 0f) / 2f

        // --- single/heavy arms --------------------------------------------------------
        if (u in 1..2) {
            val t = thick(u)
            val end = when {
                !anyDoubleH -> cy + hHalf
                d != 0 -> cy
                (l == 0) != (r == 0) -> cy + g + t1 / 2f // corner: reach the far lane
                else -> cy - g + t1 / 2f // tee: stop at the near lane
            }
            canvas.drawRect(cx - t / 2f, y, cx + t / 2f, end.coerceAtMost(bottom), paint)
        }
        if (d in 1..2) {
            val t = thick(d)
            val start = when {
                !anyDoubleH -> cy - hHalf
                u != 0 -> cy
                (l == 0) != (r == 0) -> cy - g - t1 / 2f
                else -> cy + g - t1 / 2f
            }
            canvas.drawRect(cx - t / 2f, start.coerceAtLeast(y), cx + t / 2f, bottom, paint)
        }
        if (l in 1..2) {
            val t = thick(l)
            val end = when {
                !anyDoubleV -> cx + vHalf
                r != 0 -> cx
                (u == 0) != (d == 0) -> cx + g + t1 / 2f
                else -> cx - g + t1 / 2f
            }
            canvas.drawRect(x, cy - t / 2f, end.coerceAtMost(right), cy + t / 2f, paint)
        }
        if (r in 1..2) {
            val t = thick(r)
            val start = when {
                !anyDoubleV -> cx - vHalf
                l != 0 -> cx
                (u == 0) != (d == 0) -> cx - g - t1 / 2f
                else -> cx + g - t1 / 2f
            }
            canvas.drawRect(start.coerceAtLeast(x), cy - t / 2f, right, cy + t / 2f, paint)
        }

        // --- double arms: two lanes at ±g -------------------------------------------
        val ht = t1 / 2f
        if (r == 3) {
            val upper = when { u == 3 -> cx + g; d == 3 && u == 0 -> cx - g; else -> cx }
            val lower = when { d == 3 -> cx + g; u == 3 && d == 0 -> cx - g; else -> cx }
            canvas.drawRect(upper - ht, cy - g - ht, right, cy - g + ht, paint)
            canvas.drawRect(lower - ht, cy + g - ht, right, cy + g + ht, paint)
        }
        if (l == 3) {
            val upper = when { u == 3 -> cx - g; d == 3 && u == 0 -> cx + g; else -> cx }
            val lower = when { d == 3 -> cx - g; u == 3 && d == 0 -> cx + g; else -> cx }
            canvas.drawRect(x, cy - g - ht, upper + ht, cy - g + ht, paint)
            canvas.drawRect(x, cy + g - ht, lower + ht, cy + g + ht, paint)
        }
        if (u == 3) {
            val left = when { l == 3 -> cy - g; r == 3 && l == 0 -> cy + g; else -> cy }
            val rightLane = when { r == 3 -> cy - g; l == 3 && r == 0 -> cy + g; else -> cy }
            canvas.drawRect(cx - g - ht, y, cx - g + ht, left + ht, paint)
            canvas.drawRect(cx + g - ht, y, cx + g + ht, rightLane + ht, paint)
        }
        if (d == 3) {
            val left = when { l == 3 -> cy + g; r == 3 && l == 0 -> cy - g; else -> cy }
            val rightLane = when { r == 3 -> cy + g; l == 3 && r == 0 -> cy - g; else -> cy }
            canvas.drawRect(cx - g - ht, left - ht, cx - g + ht, bottom, paint)
            canvas.drawRect(cx + g - ht, rightLane - ht, cx + g + ht, bottom, paint)
        }
    }

    private fun drawArc(
        canvas: Canvas, cp: Int, x: Float, y: Float, right: Float, bottom: Float,
        cx: Float, cy: Float, t: Float, paint: Paint,
    ) {
        val radius = minOf(right - x, bottom - y) / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = t
        path.reset()
        when (cp) {
            0x256D -> { // ╭ down and right
                path.moveTo(right, cy); path.lineTo(cx + radius, cy)
                path.quadTo(cx, cy, cx, cy + radius); path.lineTo(cx, bottom)
            }
            0x256E -> { // ╮ down and left
                path.moveTo(x, cy); path.lineTo(cx - radius, cy)
                path.quadTo(cx, cy, cx, cy + radius); path.lineTo(cx, bottom)
            }
            0x256F -> { // ╯ up and left
                path.moveTo(x, cy); path.lineTo(cx - radius, cy)
                path.quadTo(cx, cy, cx, cy - radius); path.lineTo(cx, y)
            }
            else -> { // ╰ up and right
                path.moveTo(right, cy); path.lineTo(cx + radius, cy)
                path.quadTo(cx, cy, cx, cy - radius); path.lineTo(cx, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawBlock(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint) {
        val right = x + w
        val bottom = y + h
        fun fill(l: Float, t: Float, r: Float, b: Float) = canvas.drawRect(l, t, r, b, paint)
        when (cp) {
            0x2580 -> fill(x, y, right, y + h / 2f)
            in 0x2581..0x2588 -> fill(x, bottom - h * (cp - 0x2580) / 8f, right, bottom)
            in 0x2589..0x258F -> fill(x, y, x + w * (0x2590 - cp) / 8f, bottom)
            0x2590 -> fill(x + w / 2f, y, right, bottom)
            in 0x2591..0x2593 -> {
                paint.alpha = paint.alpha * (cp - 0x2590) / 4
                fill(x, y, right, bottom)
            }
            0x2594 -> fill(x, y, right, y + h / 8f)
            0x2595 -> fill(right - w / 8f, y, right, bottom)
            else -> {
                // Quadrants: bit 1 upper-left, 2 upper-right, 4 lower-left, 8 lower-right.
                val bits = when (cp) {
                    0x2596 -> 4; 0x2597 -> 8; 0x2598 -> 1; 0x2599 -> 13; 0x259A -> 9
                    0x259B -> 7; 0x259C -> 11; 0x259D -> 2; 0x259E -> 6; else -> 14
                }
                val mx = x + w / 2f
                val my = y + h / 2f
                if (bits and 1 != 0) fill(x, y, mx, my)
                if (bits and 2 != 0) fill(mx, y, right, my)
                if (bits and 4 != 0) fill(x, my, mx, bottom)
                if (bits and 8 != 0) fill(mx, my, right, bottom)
            }
        }
    }

    private fun drawPowerline(canvas: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint) {
        val right = x + w
        val bottom = y + h
        val cy = y + h / 2f
        path.reset()
        when (cp) {
            0xE0B0 -> { path.moveTo(x, y); path.lineTo(right, cy); path.lineTo(x, bottom); path.close() }
            0xE0B2 -> { path.moveTo(right, y); path.lineTo(x, cy); path.lineTo(right, bottom); path.close() }
            0xE0B1, 0xE0B3 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lightWidth(w)
                if (cp == 0xE0B1) {
                    path.moveTo(x, y); path.lineTo(right, cy); path.lineTo(x, bottom)
                } else {
                    path.moveTo(right, y); path.lineTo(x, cy); path.lineTo(right, bottom)
                }
            }
            0xE0B4 -> { // right half disc
                rect.set(x - w, y, right, bottom)
                path.addArc(rect, -90f, 180f); path.close()
            }
            else -> { // 0xE0B6 left half disc
                rect.set(x, y, right + w, bottom)
                path.addArc(rect, 90f, 180f); path.close()
            }
        }
        canvas.drawPath(path, paint)
    }
}
