// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kitty graphics protocol (APC G …), as gitoui, serie and yazi use it. */
class KittyGraphicsTest {

    private fun png(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) img.setRGB(x, y, 0x336699 + x)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun b64(data: ByteArray) = Base64.getEncoder().encodeToString(data)

    private fun apc(control: String, payload: String = "") = "\u001b_G$control;$payload\u001b\\"

    private fun tile(t: TestTerm, row: Int, col: Int): Triple<Int, Int, Int>? {
        val v = t.emu.getRow(row).getCodePoint(col)
        return if (ImageCell.isImage(v)) Triple(ImageCell.slot(v), ImageCell.x(v), ImageCell.y(v)) else null
    }

    private fun placeholder(vararg marks: Int): String {
        val sb = StringBuilder().appendCodePoint(KittyPlaceholder.CODE_POINT)
        for (m in marks) sb.appendCodePoint(KittyPlaceholder.diacritic(m))
        return sb.toString()
    }

    @Test
    fun transmitAndDisplayAtTheCursor() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        t.feed("ab" + apc("a=T,f=100,i=7", b64(png(30, 40))) + "cd")
        // 30 x 40 px is 3 x 2 cells; the cursor ends after the image on its last row.
        assertEquals("ab###", t.line(0))
        assertEquals("  ###cd", t.line(1))
        val slot = tile(t, 0, 2)!!.first
        assertEquals(Triple(slot, 2, 1), tile(t, 1, 4))
        assertEquals("\u001b_Gi=7;OK\u001b\\", t.replies())
    }

    @Test
    fun chunkedUploadIsShownAfterTheLastChunk() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        val data = b64(png(20, 20))
        val cut = (data.length / 2) / 4 * 4
        t.feed(apc("a=T,f=100,i=3,m=1", data.substring(0, cut)))
        assertEquals("", t.line(0))
        assertEquals("", t.replies(), "no reply before the data is complete")
        t.feed(apc("m=0", data.substring(cut)))
        assertEquals("##", t.line(0))
        assertEquals("\u001b_Gi=3;OK\u001b\\", t.replies())
    }

    @Test
    fun cursorStaysWithCursorPolicyOne() {
        val t = TestTerm(cols = 20, rows = 5).feed("x")
        t.emu.setCellPixelSize(10, 20)
        t.feed(apc("a=T,f=100,C=1,q=2", b64(png(20, 40))) + "y")
        assertEquals("xy#", t.line(0), "text after the image starts where the cursor was")
        assertEquals(" ##", t.line(1))
    }

    @Test
    fun columnsAndRowsGivenFillTheArea() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        t.feed(apc("a=T,f=100,c=6,r=1,q=2", b64(png(10, 10))))
        assertEquals("######", t.line(0))
        val image = assertNotNull(t.emu.getImage(tile(t, 0, 0)!!.first))
        assertEquals(false, image.preserveAspectRatio)
    }

    @Test
    fun rawRgbaCompressedWithZlibBecomesAPng() {
        val pixels = ByteArray(2 * 2 * 4) { if (it % 4 == 3) 0xFF.toByte() else (it * 20).toByte() }
        val deflater = Deflater().apply { setInput(pixels); finish() }
        val buf = ByteArray(256)
        val compressed = buf.copyOf(deflater.deflate(buf))
        val t = TestTerm(cols = 20, rows = 5)
        t.feed(apc("a=T,f=32,s=2,v=2,o=z,c=2,r=1,q=2", b64(compressed)))
        val image = assertNotNull(t.emu.getImage(tile(t, 0, 0)!!.first))
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(image.data)))
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        // Pixel (1, 0) is bytes 4..7 of the upload.
        assertEquals(0xFF000000.toInt() or (80 shl 16) or (100 shl 8) or 120, decoded.getRGB(1, 0))
    }

    @Test
    fun placeAnImageSentEarlier() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        t.feed(apc("a=t,f=100,i=5", b64(png(20, 20))))
        assertEquals("", t.line(0), "a=t only stores the image")
        assertEquals("\u001b_Gi=5;OK\u001b\\", t.replies())
        t.feed("ab" + apc("a=p,i=5,p=2"))
        assertEquals("ab##", t.line(0))
        assertEquals("\u001b_Gi=5,p=2;OK\u001b\\", t.replies())
        t.feed(apc("a=p,i=6"))
        assertTrue(t.replies().startsWith("\u001b_Gi=6;ENOENT:"))
    }

    @Test
    fun queryRepliesWithoutKeepingTheImage() {
        val t = TestTerm(cols = 20, rows = 5)
        t.feed(apc("i=31,s=1,v=1,a=q,t=d,f=24", "AAAA"))
        assertEquals("\u001b_Gi=31;OK\u001b\\", t.replies())
        assertEquals("", t.line(0))
        t.feed(apc("a=p,i=31"))
        assertTrue(t.replies().startsWith("\u001b_Gi=31;ENOENT:"), "a query does not store the image")
        t.feed(apc("i=32,a=q,t=f,f=100", b64("/tmp/x.png".toByteArray())))
        assertTrue(t.replies().startsWith("\u001b_Gi=32;EINVAL:"), "only direct transmission works over SSH")
    }

    @Test
    fun repliesFollowQuietAndNeedAnId() {
        val t = TestTerm(cols = 20, rows = 5)
        val data = b64(png(10, 10))
        t.feed(apc("a=t,f=100,i=1,q=1", data))
        assertEquals("", t.replies(), "q=1 silences OK")
        t.feed(apc("a=t,f=100,i=2,q=1", "bm90IGEgcG5n"))
        assertTrue(t.replies().startsWith("\u001b_Gi=2;EINVAL:"), "q=1 still reports errors")
        t.feed(apc("a=t,f=100,i=3,q=2", "bm90IGEgcG5n"))
        assertEquals("", t.replies(), "q=2 silences errors too")
        t.feed(apc("a=t,f=100", data))
        assertEquals("", t.replies(), "no id, no reply")
        t.feed(apc("a=t,f=100,I=13", data))
        assertTrue(Regex("\u001b_Gi=\\d+,I=13;OK\u001b\\\\").matches(t.replies()), "an image number gets the assigned id back")
    }

    @Test
    fun unicodePlaceholdersShowAVirtualPlacement() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        t.feed(apc("a=T,U=1,f=100,i=42,c=3,r=2,q=2", b64(png(30, 40))))
        assertEquals("", t.line(0), "a virtual placement draws nothing by itself")
        assertEquals(0, t.emu.cursorCol, "and does not move the cursor")
        // Row 0: all diacritics, then none (inferred), then row and column only.
        t.feed("\u001b[38;5;42m" + placeholder(0, 0) + placeholder() + placeholder(0, 2) + "\u001b[m\r\n")
        // Row 1 in true color 0x00002A (= 42): row only, so columns count from 0.
        t.feed("\u001b[38;2;0;0;42m" + placeholder(1) + placeholder(1) + placeholder(1) + "\u001b[m")
        val first = KittyPlaceholder.resolve(t.emu.getRow(0))
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2), first.map { it.imageRow to it.imageColumn })
        assertTrue(first.all { it.imageId == 42L })
        val second = KittyPlaceholder.resolve(t.emu.getRow(1))
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2), second.map { it.imageRow to it.imageColumn })
        assertEquals(listOf(0, 1, 2), second.map { it.column })
        val image = assertNotNull(t.emu.getPlaceholderImage(42, 0))
        assertEquals(3, image.columns)
        assertEquals(2, image.rows)
        assertTrue(image.preserveAspectRatio && image.centered)
        assertEquals("", t.emu.getText(0, 0, 0, 3), "copied text has blanks for placeholders")
    }

    @Test
    fun thirdDiacriticIsTheTopByteOfTheId() {
        val t = TestTerm(cols = 20, rows = 5)
        t.feed("\u001b[38;2;0;1;2m" + placeholder(0, 0, 1) + placeholder())
        val cells = KittyPlaceholder.resolve(t.emu.getRow(0))
        val id = (1L shl 24) or 0x0102L
        assertEquals(listOf(id, id), cells.map { it.imageId })
        assertEquals(listOf(0, 1), cells.map { it.imageColumn })
    }

    @Test
    fun placeholdersWithoutAnImageColorAreIgnored() {
        val t = TestTerm(cols = 20, rows = 5).feed(placeholder(0, 0))
        assertTrue(KittyPlaceholder.resolve(t.emu.getRow(0)).isEmpty())
    }

    @Test
    fun deleteByIdRemovesItsPlacements() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        t.feed(apc("a=T,f=100,i=8,q=2", b64(png(20, 20))) + "x")
        t.feed(apc("a=T,U=1,f=100,i=9,c=2,r=1,q=2", b64(png(20, 20))))
        assertEquals("##x", t.line(0))
        t.feed(apc("a=d,d=i,i=8"))
        assertEquals("  x", t.line(0), "tiles of image 8 are cleared, the text stays")
        t.feed(apc("a=p,i=8,q=1"))
        assertEquals("", t.replies(), "lowercase d keeps the image data")
        assertNotNull(t.emu.getPlaceholderImage(9, 0))
        t.feed(apc("a=d,d=I,i=9"))
        assertNull(t.emu.getPlaceholderImage(9, 0))
    }

    @Test
    fun deleteAllClearsVisibleImagesOnly() {
        val t = TestTerm(cols = 20, rows = 5)
        t.emu.setCellPixelSize(10, 20)
        val iterm = "\u001b]1337;File=inline=1;width=2;height=1:${b64(png(20, 20))}\u0007"
        t.feed(iterm + apc("a=T,f=100,c=2,r=1,q=2", b64(png(20, 20))))
        assertEquals("####", t.line(0))
        t.feed(apc("a=d"))
        assertEquals("##", t.line(0), "iTerm2 images are not kitty placements")
    }
}
