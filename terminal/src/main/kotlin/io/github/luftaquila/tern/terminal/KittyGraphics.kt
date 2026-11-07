// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A cell that shows part of a kitty image through a Unicode placeholder: [KittyPlaceholder.CODE_POINT]
 * in a color that names the image, with diacritics that name the cell's row and column in the
 * image's virtual placement. See https://sw.kovidgoyal.net/kitty/graphics-protocol/#unicode-placeholders
 */
class PlaceholderCell(
    val column: Int,
    val imageId: Long,
    val placementId: Long,
    val imageRow: Int,
    val imageColumn: Int,
)

object KittyPlaceholder {
    const val CODE_POINT = 0x10EEEE

    /** kitty's rowcolumn-diacritics.txt: a diacritic stands for its index in this table. */
    private val DIACRITICS = intArrayOf(
        0x0305, 0x030D, 0x030E, 0x0310, 0x0312, 0x033D, 0x033E, 0x033F, 0x0346, 0x034A,
        0x034B, 0x034C, 0x0350, 0x0351, 0x0352, 0x0357, 0x035B, 0x0363, 0x0364, 0x0365,
        0x0366, 0x0367, 0x0368, 0x0369, 0x036A, 0x036B, 0x036C, 0x036D, 0x036E, 0x036F,
        0x0483, 0x0484, 0x0485, 0x0486, 0x0487, 0x0592, 0x0593, 0x0594, 0x0595, 0x0597,
        0x0598, 0x0599, 0x059C, 0x059D, 0x059E, 0x059F, 0x05A0, 0x05A1, 0x05A8, 0x05A9,
        0x05AB, 0x05AC, 0x05AF, 0x05C4, 0x0610, 0x0611, 0x0612, 0x0613, 0x0614, 0x0615,
        0x0616, 0x0617, 0x0657, 0x0658, 0x0659, 0x065A, 0x065B, 0x065D, 0x065E, 0x06D6,
        0x06D7, 0x06D8, 0x06D9, 0x06DA, 0x06DB, 0x06DC, 0x06DF, 0x06E0, 0x06E1, 0x06E2,
        0x06E4, 0x06E7, 0x06E8, 0x06EB, 0x06EC, 0x0730, 0x0732, 0x0733, 0x0735, 0x0736,
        0x073A, 0x073D, 0x073F, 0x0740, 0x0741, 0x0743, 0x0745, 0x0747, 0x0749, 0x074A,
        0x07EB, 0x07EC, 0x07ED, 0x07EE, 0x07EF, 0x07F0, 0x07F1, 0x07F3, 0x0816, 0x0817,
        0x0818, 0x0819, 0x081B, 0x081C, 0x081D, 0x081E, 0x081F, 0x0820, 0x0821, 0x0822,
        0x0823, 0x0825, 0x0826, 0x0827, 0x0829, 0x082A, 0x082B, 0x082C, 0x082D, 0x0951,
        0x0953, 0x0954, 0x0F82, 0x0F83, 0x0F86, 0x0F87, 0x135D, 0x135E, 0x135F, 0x17DD,
        0x193A, 0x1A17, 0x1A75, 0x1A76, 0x1A77, 0x1A78, 0x1A79, 0x1A7A, 0x1A7B, 0x1A7C,
        0x1B6B, 0x1B6D, 0x1B6E, 0x1B6F, 0x1B70, 0x1B71, 0x1B72, 0x1B73, 0x1CD0, 0x1CD1,
        0x1CD2, 0x1CDA, 0x1CDB, 0x1CE0, 0x1DC0, 0x1DC1, 0x1DC3, 0x1DC4, 0x1DC5, 0x1DC6,
        0x1DC7, 0x1DC8, 0x1DC9, 0x1DCB, 0x1DCC, 0x1DD1, 0x1DD2, 0x1DD3, 0x1DD4, 0x1DD5,
        0x1DD6, 0x1DD7, 0x1DD8, 0x1DD9, 0x1DDA, 0x1DDB, 0x1DDC, 0x1DDD, 0x1DDE, 0x1DDF,
        0x1DE0, 0x1DE1, 0x1DE2, 0x1DE3, 0x1DE4, 0x1DE5, 0x1DE6, 0x1DFE, 0x20D0, 0x20D1,
        0x20D4, 0x20D5, 0x20D6, 0x20D7, 0x20DB, 0x20DC, 0x20E1, 0x20E7, 0x20E9, 0x20F0,
        0x2CEF, 0x2CF0, 0x2CF1, 0x2DE0, 0x2DE1, 0x2DE2, 0x2DE3, 0x2DE4, 0x2DE5, 0x2DE6,
        0x2DE7, 0x2DE8, 0x2DE9, 0x2DEA, 0x2DEB, 0x2DEC, 0x2DED, 0x2DEE, 0x2DEF, 0x2DF0,
        0x2DF1, 0x2DF2, 0x2DF3, 0x2DF4, 0x2DF5, 0x2DF6, 0x2DF7, 0x2DF8, 0x2DF9, 0x2DFA,
        0x2DFB, 0x2DFC, 0x2DFD, 0x2DFE, 0x2DFF, 0xA66F, 0xA67C, 0xA67D, 0xA6F0, 0xA6F1,
        0xA8E0, 0xA8E1, 0xA8E2, 0xA8E3, 0xA8E4, 0xA8E5, 0xA8E6, 0xA8E7, 0xA8E8, 0xA8E9,
        0xA8EA, 0xA8EB, 0xA8EC, 0xA8ED, 0xA8EE, 0xA8EF, 0xA8F0, 0xA8F1, 0xAAB0, 0xAAB2,
        0xAAB3, 0xAAB7, 0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xFE20, 0xFE21, 0xFE22, 0xFE23,
        0xFE24, 0xFE25, 0xFE26, 0x10A0F, 0x10A38, 0x1D185, 0x1D186, 0x1D187, 0x1D188, 0x1D189,
        0x1D1AA, 0x1D1AB, 0x1D1AC, 0x1D1AD, 0x1D242, 0x1D243, 0x1D244,
    )

    private val VALUES = HashMap<Int, Int>(DIACRITICS.size * 2).apply { DIACRITICS.forEachIndexed { i, cp -> put(cp, i) } }

    /** Number of distinct rows, columns or id bytes a diacritic can express. */
    val MAX_VALUE: Int get() = DIACRITICS.size - 1

    /** The number [codePoint] stands for, or -1 when it is not a row/column diacritic. */
    fun diacriticValue(codePoint: Int): Int = VALUES[codePoint] ?: -1

    /** The diacritic standing for [value]. */
    fun diacritic(value: Int): Int = DIACRITICS[value]

    /**
     * Placeholder cells among the first [columns] cells of [row], left to right. Omitted
     * diacritics continue the cell on the left when it is a placeholder in the same color:
     * no row means the left cell's row and next column, no column means the next column when
     * the row matches, and no third diacritic takes the left cell's id byte when the cell is
     * its right neighbor. Cells whose image id is 0 are left out.
     */
    fun resolve(row: TerminalRow, columns: Int = row.columns): List<PlaceholderCell> {
        var cells: ArrayList<PlaceholderCell>? = null
        var leftColor = 0
        var leftRow = -1
        var leftColumn = -1
        var leftHigh = 0
        var hasLeft = false
        for (c in 0 until minOf(columns, row.columns)) {
            if (row.getCodePoint(c) != CODE_POINT) {
                hasLeft = false
                continue
            }
            val color = TextStyle.foreground(row.getStyle(c))
            val marks = IntArray(3) { -1 }
            row.getCombining(c)?.let { s ->
                var i = 0
                var n = 0
                while (i < s.length && n < 3) {
                    val cp = s.codePointAt(i)
                    i += Character.charCount(cp)
                    val v = diacriticValue(cp)
                    if (v < 0) break
                    marks[n++] = v
                }
            }
            val continues = hasLeft && color == leftColor
            val imageRow: Int
            val imageColumn: Int
            val high: Int
            when {
                marks[0] < 0 -> if (continues) {
                    imageRow = leftRow; imageColumn = leftColumn + 1; high = leftHigh
                } else {
                    imageRow = 0; imageColumn = 0; high = 0
                }
                marks[1] < 0 -> if (continues && marks[0] == leftRow) {
                    imageRow = marks[0]; imageColumn = leftColumn + 1; high = leftHigh
                } else {
                    imageRow = marks[0]; imageColumn = 0; high = 0
                }
                marks[2] < 0 -> {
                    imageRow = marks[0]
                    imageColumn = marks[1]
                    high = if (continues && marks[0] == leftRow && marks[1] == leftColumn + 1) leftHigh else 0
                }
                else -> {
                    imageRow = marks[0]; imageColumn = marks[1]; high = marks[2]
                }
            }
            hasLeft = true
            leftColor = color
            leftRow = imageRow
            leftColumn = imageColumn
            leftHigh = high
            val low = when {
                color and TextStyle.RGB_BIT != 0 -> color and 0xFFFFFF
                color < 256 -> color
                else -> 0
            }
            val id = (high.toLong() shl 24) or low.toLong()
            if (id == 0L) continue
            if (cells == null) cells = ArrayList()
            cells += PlaceholderCell(c, id, 0, imageRow, imageColumn)
        }
        return cells ?: emptyList()
    }
}

/** An image sent with the kitty graphics protocol, kept for placements and placeholders. */
internal class KittyImage(
    val id: Long,
    val number: Long,
    /** PNG data (raw RGB and RGBA uploads are converted). */
    val data: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    /** Virtual placements (U=1) by placement id, each the image fitted to its cells. */
    val placements = LinkedHashMap<Long, TerminalImage>()
}

/** Origin of a tile image placed by a kitty command. */
internal class KittyTile(val imageId: Long, val placementId: Long)

/** One `APC G <key>=<value>,… ; <payload> ST` command. */
internal class KittyCommand private constructor(private val keys: Map<Char, String>, val payload: String) {
    fun has(key: Char): Boolean = key in keys

    fun char(key: Char, default: Char): Char = keys[key]?.singleOrNull() ?: default

    /** Integer value of [key] (unsigned 32-bit ids, signed z-index), 0 when absent or invalid. */
    fun int(key: Char): Long = keys[key]?.toLongOrNull() ?: 0

    /** True when every key of the command is one of [allowed]. */
    fun onlyKeys(allowed: String): Boolean = keys.keys.all { it in allowed }

    companion object {
        fun parse(s: String): KittyCommand {
            val semi = s.indexOf(';')
            val control = if (semi < 0) s else s.substring(0, semi)
            val keys = HashMap<Char, String>()
            for (part in control.split(',')) {
                if (part.length >= 2 && part[1] == '=') keys[part[0]] = part.substring(2)
            }
            return KittyCommand(keys, if (semi < 0) "" else s.substring(semi + 1))
        }
    }
}

/** Inflates zlib data (kitty's o=z), or null when it is corrupt or larger than [limit]. */
internal fun inflateZlib(data: ByteArray, limit: Int): ByteArray? {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    } catch (_: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}

/** Encodes 8-bit RGB ([channels] 3) or RGBA (4) pixels, row by row, as a PNG file. */
internal fun encodePng(width: Int, height: Int, channels: Int, pixels: ByteArray): ByteArray {
    val stride = width * channels
    val raw = ByteArray(height * (stride + 1))
    for (y in 0 until height) System.arraycopy(pixels, y * stride, raw, y * (stride + 1) + 1, stride)
    val deflater = Deflater(Deflater.BEST_SPEED)
    val compressed = ByteArrayOutputStream()
    try {
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(1 shl 16)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
    } finally {
        deflater.end()
    }
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
    fun chunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
    val colorType: Byte = if (channels == 4) 6 else 2
    chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(colorType).put(0).put(0).put(0).array())
    chunk("IDAT", compressed.toByteArray())
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
}
