// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.termview

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.TerminalFont
import java.io.File
import java.io.IOException
import java.util.UUID

/** Loads terminal fonts: bundled ones and fonts the user imported (TTF/OTF/TTC). */
object TerminalFonts {
    private const val MAX_FONT_BYTES = 64L * 1024 * 1024

    fun fontsDir(context: Context): File = File(context.filesDir, "fonts")

    private fun defaultTypeface(context: Context): Typeface = context.resources.getFont(R.font.jetbrains_mono)

    /** res/font resource of a bundled font, null for [TerminalFont.SYSTEM_MONO] / [TerminalFont.CUSTOM]. */
    fun resource(font: TerminalFont): Int? = when (font) {
        TerminalFont.JETENDARD -> R.font.jetendard
        TerminalFont.D2CODING -> R.font.d2coding
        TerminalFont.JETBRAINS_MONO -> R.font.jetbrains_mono
        TerminalFont.FIRA_CODE -> R.font.fira_code
        TerminalFont.CASCADIA_CODE -> R.font.cascadia_code
        TerminalFont.SOURCE_CODE_PRO -> R.font.source_code_pro
        TerminalFont.IBM_PLEX_MONO -> R.font.ibm_plex_mono
        TerminalFont.HACK -> R.font.hack
        TerminalFont.MESLO -> R.font.meslo_lgs
        TerminalFont.IOSEVKA -> R.font.iosevka_term
        TerminalFont.ZERO_X_PROTO -> R.font.zero_x_proto
        TerminalFont.SYSTEM_MONO, TerminalFont.CUSTOM -> null
    }

    /** [font] (with [file] for an imported one), or null if that file is unusable. */
    fun typeface(context: Context, font: TerminalFont, file: String?): Typeface? = when (font) {
        TerminalFont.SYSTEM_MONO -> Typeface.MONOSPACE
        TerminalFont.CUSTOM -> file?.let { load(File(fontsDir(context), it)) }
        else -> context.resources.getFont(resource(font)!!)
    }

    /** The main terminal typeface for [settings]; JetBrains Mono if an imported font is unusable. */
    fun primary(context: Context, settings: AppSettings): Typeface =
        typeface(context, settings.font, settings.customFontFile) ?: defaultTypeface(context)

    /**
     * The typeface for characters the main font lacks, or null to leave them to Android's system
     * fonts ([TerminalFont.SYSTEM_MONO], or an imported fallback that is unusable).
     */
    fun fallback(context: Context, settings: AppSettings): Typeface? =
        if (settings.fallbackFont == TerminalFont.SYSTEM_MONO) null else typeface(context, settings.fallbackFont, settings.fallbackFontFile)

    fun load(file: File): Typeface? {
        if (!file.isFile) return null
        val typeface = runCatching { Typeface.Builder(file).build() }.getOrNull() ?: return null
        // A file that is not really a font yields a typeface without glyphs.
        val paint = Paint().apply { this.typeface = typeface }
        return if (paint.hasGlyph("M")) typeface else null
    }

    class ImportException(message: String) : IOException(message)

    /**
     * Copies a font picked through the Storage Access Framework into app storage and checks
     * that Android can use it. Blocking; call off the main thread.
     * @return (stored file name, display name)
     */
    fun import(context: Context, uri: Uri): Pair<String, String> {
        val resolver = context.contentResolver
        var displayName = "font"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) displayName = c.getString(0)
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FONT_BYTES) throw ImportException(context.getString(R.string.font_import_too_large))
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ImportException(context.getString(R.string.font_import_failed))

        if (!looksLikeFont(bytes)) throw ImportException(context.getString(R.string.font_import_not_font))
        val ext = when {
            bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "OTTO" -> "otf"
            bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "ttcf" -> "ttc"
            else -> "ttf"
        }
        val dir = fontsDir(context).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        if (load(file) == null) {
            file.delete()
            throw ImportException(context.getString(R.string.font_import_not_font))
        }
        return file.name to displayName.substringBeforeLast('.')
    }

    /** Deletes the imported font [file]. */
    fun delete(context: Context, file: String) {
        File(fontsDir(context), file).delete()
    }

    /** Deletes files in the fonts folder that [keep] does not list (left over from failed imports). */
    fun deleteUnused(context: Context, keep: Collection<String>) {
        fontsDir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /** sfnt magic numbers: TrueType, OpenType/CFF, Apple 'true', font collection. */
    private fun looksLikeFont(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val tag = String(bytes, 0, 4, Charsets.ISO_8859_1)
        val trueType = bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()
        return trueType || tag == "OTTO" || tag == "true" || tag == "ttcf"
    }
}
