// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnippetsTest {

    private fun ids() = generateSequence(1) { it + 1 }.map { "s$it" }.iterator().let { it::next }

    @Test
    fun macroKeysOfEarlierLayoutsBecomeSnippets() {
        val gs = ExtraKeyDef.Text("git status\r", "gs")
        val config = ExtraKeysConfig(
            listOf(
                listOf(ExtraKeyDef.Special(SpecialKey.ESC), ExtraKeyDef.Text("/"), gs, ExtraKeyDef.Text("ls -la")),
                listOf(ExtraKeysConfig.MACROS[0], ExtraKeyDef.Text("\u0002"), gs),
            ),
        )
        val (converted, added) = config.macrosAsSnippets(emptyList(), ids())
        assertEquals(listOf(Snippet("s1", "gs", "git status\r"), Snippet("s2", "", "ls -la")), added, "the same macro twice is one snippet")
        assertEquals(
            ExtraKeysConfig(
                listOf(
                    listOf(ExtraKeyDef.Special(SpecialKey.ESC), ExtraKeyDef.Text("/"), ExtraKeyDef.SnippetRef("s1"), ExtraKeyDef.SnippetRef("s2")),
                    listOf(ExtraKeysConfig.MACROS[0], ExtraKeyDef.Text("\u0002"), ExtraKeyDef.SnippetRef("s1")),
                ),
            ),
            converted,
            "single characters and built-in macros stay text keys",
        )
        val (again, none) = converted.macrosAsSnippets(added, ids())
        assertEquals(converted, again, "a converted layout is left alone")
        assertEquals(emptyList(), none)
    }

    @Test
    fun macroKeysReuseAMatchingSnippet() {
        val existing = Snippet("mine", "gs", "git status\r")
        val config = ExtraKeysConfig(listOf(listOf(ExtraKeyDef.Text("git status\r", "gs"), ExtraKeyDef.Text("git status\r", "st"))))
        val (converted, added) = config.macrosAsSnippets(listOf(existing), ids())
        assertEquals(listOf(Snippet("s1", "st", "git status\r")), added, "another name is another snippet")
        assertEquals(ExtraKeysConfig(listOf(listOf(ExtraKeyDef.SnippetRef("mine"), ExtraKeyDef.SnippetRef("s1")))), converted)
    }

    @Test
    fun snippetKeysSurviveJson() {
        val settings = AppSettings(
            extraKeys = ExtraKeysConfig(listOf(listOf(ExtraKeyDef.SnippetRef("a"), ExtraKeyDef.Special(SpecialKey.SNIPPETS)))),
            settingsVersion = AppSettings.SETTINGS_VERSION,
        )
        val json = JsonStore.JSON.encodeToString(AppSettings.serializer(), settings)
        assertEquals(settings, JsonStore.JSON.decodeFromString(AppSettings.serializer(), json))
    }

    @Test
    fun snippetKeysOnTheBar() {
        val config = ExtraKeysConfig(listOf(listOf(ExtraKeyDef.Special(SpecialKey.ESC)), listOf(ExtraKeyDef.Text("/"))))
        val with = config.withSnippet("a")
        assertEquals(listOf(ExtraKeyDef.Special(SpecialKey.ESC), ExtraKeyDef.SnippetRef("a")), with.rows[0], "added to the end of the first row")
        assertTrue(with.hasSnippet("a"))
        assertFalse(with.hasSnippet("b"))
        assertEquals(config, with.withSnippet("a").withoutSnippet("a"), "every key of the snippet goes")
        assertEquals(listOf(listOf(ExtraKeyDef.SnippetRef("a"))), ExtraKeysConfig(emptyList()).withSnippet("a").rows)
    }

    @Test
    fun snippetTitles() {
        assertEquals("gs", Snippet(name = "gs", text = "git status\r").title)
        assertEquals("ls -la⏎", Snippet(text = "ls -la\r").title, "without a name, the text with Enter shown")
        assertEquals("^B", Snippet(text = "\u0002").title)
    }
}
