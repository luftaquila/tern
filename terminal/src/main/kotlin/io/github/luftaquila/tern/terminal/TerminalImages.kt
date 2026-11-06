// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * An image shown with the iTerm2 inline image protocol (`OSC 1337 ; File=…:<base64> BEL`).
 * The cells it covers hold [ImageCell] values naming one tile each, so the image scrolls,
 * gets overwritten and erased exactly like text.
 */
class TerminalImage internal constructor(
    val slot: Int,
    /** The image file as sent (PNG, JPEG, GIF, ...); the renderer decodes it. */
    val data: ByteArray,
    /** Size of the area the image fills, in cells. */
    val columns: Int,
    val rows: Int,
    /** Fit inside the area keeping the aspect ratio (true), or stretch to fill it. */
    val preserveAspectRatio: Boolean,
)

/**
 * Cell values for image tiles. They are negative and below [TerminalRow.WIDE_SPACER], so code
 * that handles characters (`> 0`) or blanks (`== 0`) never mistakes them for text.
 */
object ImageCell {
    private const val OFFSET = 2

    /** Images are clipped to this many columns and rows. */
    const val MAX_SPAN = 128

    internal const val MAX_SLOT = (Int.MAX_VALUE - OFFSET - 0x3FFF) / 0x4000

    fun isImage(value: Int): Boolean = value <= -OFFSET

    internal fun encode(slot: Int, x: Int, y: Int): Int = -(OFFSET + (slot shl 14) + (y shl 7) + x)

    fun slot(value: Int): Int = (-value - OFFSET) ushr 14

    /** Tile column within the image. */
    fun x(value: Int): Int = (-value - OFFSET) and 0x7F

    /** Tile row within the image. */
    fun y(value: Int): Int = ((-value - OFFSET) ushr 7) and 0x7F
}

/** Pixel size read from the header of a PNG, GIF or JPEG file, or null if unknown. */
internal fun imagePixelSize(data: ByteArray): Pair<Int, Int>? {
    fun u8(i: Int) = data[i].toInt() and 0xFF
    fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    if (data.size >= 24 && u8(0) == 0x89 && u8(1) == 'P'.code && u8(2) == 'N'.code && u8(3) == 'G'.code) {
        return be32(16) to be32(20)
    }
    if (data.size >= 10 && u8(0) == 'G'.code && u8(1) == 'I'.code && u8(2) == 'F'.code) {
        return le16(6) to le16(8)
    }
    if (data.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8) {
        var i = 2
        while (i + 9 < data.size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            val length = be16(i + 2)
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC) carry the frame size.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return be16(i + 7) to be16(i + 5)
            }
            i += 2 + length
        }
    }
    return null
}
