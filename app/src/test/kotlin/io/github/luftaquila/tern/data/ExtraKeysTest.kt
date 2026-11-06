// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtraKeysTest {

    @Test
    fun escapesParseToControlCharacters() {
        assertEquals("git status\r", KeyTextEscapes.parse("git status\\n"))
        assertEquals("a\rb\tc\u001bd\\e", KeyTextEscapes.parse("a\\rb\\tc\\ed\\\\e"))
        assertEquals("\u0003", KeyTextEscapes.parse("\\x03"))
        assertEquals("한", KeyTextEscapes.parse("\\ud55c"))
        // Unknown or incomplete escapes are kept literally.
        assertEquals("\\q\\x1", KeyTextEscapes.parse("\\q\\x1"))
        assertEquals("ends with \\", KeyTextEscapes.parse("ends with \\"))
    }

    @Test
    fun formatIsTheInverseForEditing() {
        val samples = listOf("git status\r", "\u001b:wq\r", "a\\b", "tab\there", "\u0003", "plain 한글")
        for (s in samples) assertEquals(s, KeyTextEscapes.parse(KeyTextEscapes.format(s)), "round trip of ${KeyTextEscapes.format(s)}")
    }

    @Test
    fun rawDetection() {
        assertTrue(KeyTextEscapes.isRaw("ls\r"))
        assertTrue(KeyTextEscapes.isRaw("\u001b"))
        assertFalse(KeyTextEscapes.isRaw("~/"))
        assertFalse(KeyTextEscapes.isRaw("|"))
    }

    @Test
    fun settingsWithCustomKeysSurviveJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val settings = AppSettings(
            extraKeys = ExtraKeysConfig(
                listOf(
                    listOf(ExtraKeyDef.Special(SpecialKey.ESC), ExtraKeyDef.Text("/")),
                    listOf(ExtraKeyDef.Text("git status\r", label = "gs"), ExtraKeyDef.Special(SpecialKey.CTRL_C)),
                ),
            ),
            extraKeysExpanded = true,
        )
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        assertEquals(settings, json.decodeFromString(AppSettings.serializer(), encoded))
    }

    @Test
    fun settingsFromBeforeVersionOneGetTheNewDefaults() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // Written by the previous version: absolute scroll speed, 5000 lines, DIRECT input mode.
        val old = json.decodeFromString(
            AppSettings.serializer(),
            """{"scrollSpeed":0.75,"scrollbackLines":5000,"fontSizeSp":7.0,"inputMode":"DIRECT"}""",
        )
        assertEquals(0, old.settingsVersion)
        val migrated = old.copy(extraKeys = ExtraKeysConfig.PREVIOUS_DEFAULT).migrated()
        assertEquals(1f, migrated.scrollSpeed, "the old default speed is the new 100 %")
        assertEquals(1000, migrated.scrollbackLines)
        assertEquals(AppSettings.MIN_FONT_SP, migrated.fontSizeSp)
        assertEquals(ExtraKeysConfig.DEFAULT, migrated.extraKeys)
        assertEquals(AppSettings.SETTINGS_VERSION, migrated.settingsVersion)
    }

    @Test
    fun singleImportedFontJoinsTheFontList() {
        val old = AppSettings(font = TerminalFont.CUSTOM, customFontFile = "a1.ttf", customFontName = "Sarasa Mono", settingsVersion = 1)
        val migrated = old.migrated()
        assertEquals(listOf(ImportedFont("a1.ttf", "Sarasa Mono")), migrated.importedFonts)
        assertEquals(TerminalFont.CUSTOM, migrated.font)
        assertEquals("a1.ttf", migrated.customFontFile)
        assertEquals(TerminalFont.JETENDARD, migrated.fallbackFont, "the fallback stays what it was before")
        assertEquals(emptyList(), AppSettings(settingsVersion = 1).migrated().importedFonts)
    }

    @Test
    fun settingsWithARemovedFontStillLoad() {
        // Nanum Gothic Coding was bundled until it was removed; settings that chose it fall back to the defaults.
        val settings = JsonStore.JSON.decodeFromString(
            AppSettings.serializer(),
            """{"font":"NANUM_GOTHIC_CODING","fallbackFont":"NANUM_GOTHIC_CODING","fontSizeSp":15.0,"settingsVersion":3}""",
        )
        assertEquals(TerminalFont.JETBRAINS_MONO, settings.font)
        assertEquals(TerminalFont.JETENDARD, settings.fallbackFont)
        assertEquals(15f, settings.fontSizeSp, "the other settings are kept")
    }

    @Test
    fun ligaturesStartOffOnceAndStayAsChosen() {
        // Version 2 files stored the old default (on); version 3 turns it off once.
        assertEquals(false, AppSettings(ligatures = true, settingsVersion = 2).migrated().ligatures)
        val chosen = AppSettings(ligatures = true, settingsVersion = AppSettings.SETTINGS_VERSION)
        assertEquals(true, chosen.migrated().ligatures, "turned on after the change, it stays on")
    }

    @Test
    fun migrationKeepsChosenValues() {
        val migrated = AppSettings(scrollSpeed = 1.5f, scrollbackLines = 10000, fontSizeSp = 16f).migrated()
        assertEquals(2f, migrated.scrollSpeed, "1.5 of the old scale is 200 % of the new default")
        assertEquals(10000, migrated.scrollbackLines)
        assertEquals(16f, migrated.fontSizeSp)
        val custom = ExtraKeysConfig(ExtraKeysConfig.PREVIOUS_DEFAULT.rows.take(1))
        assertEquals(custom, AppSettings(extraKeys = custom).migrated().extraKeys, "customized key layouts are kept")
        val current = AppSettings(scrollSpeed = 1.3f, scrollbackLines = 5000, settingsVersion = AppSettings.SETTINGS_VERSION)
        assertEquals(current, current.migrated(), "current settings are left alone")
    }

}
