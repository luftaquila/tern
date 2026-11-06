// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iTerm2 inline images (OSC 1337 File=…), as serie draws its commit graph. */
class ImageTest {

    private fun image(width: Int, height: Int, format: String = "png", rgb: Int = 0x336699): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) img.setRGB(x, y, rgb + x)
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    private fun osc(data: ByteArray, args: String) =
        "${OSC}1337;File=size=${data.size};$args:${Base64.getEncoder().encodeToString(data)}\u0007"

    private fun tile(t: TestTerm, row: Int, col: Int): Triple<Int, Int, Int>? {
        val v = t.emu.getRow(row).getCodePoint(col)
        return if (ImageCell.isImage(v)) Triple(ImageCell.slot(v), ImageCell.x(v), ImageCell.y(v)) else null
    }

    @Test
    fun serieStyleRowImage() {
        val png = image(40, 10)
        val t = TestTerm(cols = 20, rows = 5).feed("ab" + osc(png, "width=4;height=1;preserveAspectRatio=0;inline=1") + "cd")
        assertEquals("ab####cd", t.line(0))
        val slot = tile(t, 0, 2)!!.first
        for (x in 0 until 4) assertEquals(Triple(slot, x, 0), tile(t, 0, 2 + x))
        val img = assertNotNull(t.emu.getImage(slot))
        assertTrue(img.data.contentEquals(png))
        assertEquals(4, img.columns)
        assertEquals(1, img.rows)
        assertEquals(false, img.preserveAspectRatio)
        assertEquals(8, t.emu.cursorCol)
        assertEquals("ab    cd", t.emu.getText(0, 0, 0, 8), "copied text has blanks for the image")
    }

    @Test
    fun autoSizeFromPixelsAndCellSize() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        t.feed(osc(image(30, 30), "inline=1"))
        // 30 px wide = 3 cells, 30 px tall = 2 cells (rounded up).
        assertEquals("###", t.line(0))
        assertEquals("###", t.line(1))
        assertEquals(Triple(tile(t, 0, 0)!!.first, 2, 1), tile(t, 1, 2))
        assertEquals(1, t.emu.cursorRow)
        assertEquals(3, t.emu.cursorCol)
    }

    @Test
    fun oneSideGivenKeepsAspectRatio() {
        val t = TestTerm(cols = 40, rows = 10)
        t.emu.setCellPixelSize(10, 20)
        t.feed(osc(image(200, 100), "height=2;inline=1"))
        // 2 rows = 40 px tall -> 80 px wide = 8 cells.
        assertEquals("########", t.line(0))
    }

    @Test
    fun tallImageScrollsTheScreen() {
        val t = TestTerm(cols = 10, rows = 4).feed("1\r\n2\r\n3\r\n4")
        t.feed(osc(image(20, 20), "width=2;height=3;inline=1"))
        // Placed at row 3 col 1: two more rows needed, so the screen scrolled twice.
        assertEquals("3", t.line(0))
        assertEquals("4##", t.line(1))
        assertEquals(" ##", t.line(2))
        assertEquals(" ##", t.line(3))
        assertEquals(2, tile(t, 3, 2)!!.third)
    }

    @Test
    fun textAndErasingReplaceTiles() {
        val t = TestTerm(cols = 10, rows = 3).feed(osc(image(10, 10), "width=4;height=1;inline=1"))
        t.feed("\rxy")
        assertEquals("xy##", t.line(0))
        t.feed("${CSI}K")
        assertEquals("xy", t.line(0))
    }

    @Test
    fun imagesNoCellShowsAreForgotten() {
        val t = TestTerm(cols = 10, rows = 2, scrollback = 0)
        t.feed(osc(image(8, 8, rgb = 1), "width=1;height=1;inline=1"))
        val first = tile(t, 0, 0)!!.first
        // Keep the first image on screen; churn through many others on the second line.
        for (i in 2..400) t.feed("${CSI}2;1H" + osc(image(8, 8, rgb = i * 7), "width=1;height=1;inline=1"))
        assertNotNull(t.emu.getImage(first), "still visible")
        assertNotNull(t.emu.getImage(tile(t, 1, 0)!!.first))
        t.feed("${CSI}2J")
        for (i in 401..700) t.feed("${CSI}1;1H" + osc(image(8, 8, rgb = i * 7), "width=1;height=1;inline=1"))
        assertNull(t.emu.getImage(first), "dropped once nothing showed it and the store filled up")
    }

    @Test
    fun onlyInlineImagesAreShown() {
        val t = TestTerm().feed(osc(image(8, 8), "width=2;height=1;inline=0") + "x")
        assertEquals("x", t.line(0))
        t.feed("${OSC}1337;SetUserVar=a=Yg==\u0007y")
        assertEquals("xy", t.line(0))
    }

    @Test
    fun multipartTransfer() {
        // iTerm2 3.5+: MultipartFile=<args>, then FilePart=<base64 chunk>..., then FileEnd.
        val png = image(40, 10)
        val b64 = Base64.getEncoder().encodeToString(png)
        val t = TestTerm(cols = 20, rows = 5).feed("a${OSC}1337;MultipartFile=width=4;height=1;preserveAspectRatio=0;inline=1\u0007")
        for (chunk in b64.chunked(37)) t.feed("${OSC}1337;FilePart=$chunk\u0007")
        assertEquals("a", t.line(0), "nothing shown before FileEnd")
        t.feed("${OSC}1337;FileEnd\u0007b")
        assertEquals("a####b", t.line(0))
        assertTrue(t.emu.getImage(tile(t, 0, 1)!!.first)!!.data.contentEquals(png))
        // Parts without a started transfer are ignored.
        t.feed("${OSC}1337;FilePart=$b64\u0007${OSC}1337;FileEnd\u0007c")
        assertEquals("a####bc", t.line(0))
    }

    @Test
    fun cellSizeQueries() {
        val t = TestTerm(cols = 80, rows = 24)
        t.emu.setPixelSize(80 * 21 + 5, 24 * 44)
        t.feed("${CSI}16t")
        assertEquals("${CSI}6;44;21t", t.replies())
        t.feed("${CSI}14t")
        assertEquals("${CSI}4;${24 * 44};${80 * 21 + 5}t", t.replies())
        t.feed("${OSC}1337;ReportCellSize\u0007")
        assertEquals("${OSC}1337;ReportCellSize=44.0;21.0;1.0\u0007", t.replies())
        t.feed("${OSC}1337;ReportCellSize\u001b\\")
        assertEquals("${OSC}1337;ReportCellSize=44.0;21.0;1.0\u001b\\", t.replies())
    }

    @Test
    fun pixelSizeFromHeaders() {
        assertEquals(37 to 11, imagePixelSize(image(37, 11, "png")))
        assertEquals(23 to 9, imagePixelSize(image(23, 9, "gif")))
        assertEquals(31 to 17, imagePixelSize(image(31, 17, "jpg")))
        assertNull(imagePixelSize(byteArrayOf(1, 2, 3)))
    }
}
