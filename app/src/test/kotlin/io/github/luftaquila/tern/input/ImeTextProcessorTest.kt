// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays InputConnection call sequences produced by real keyboards (taken from on-device
 * traces of Gboard where noted) and checks what the remote line ends up containing.
 *
 * [FakeConnection] mirrors BaseInputConnection's editable semantics (composing region
 * replacement, batch edits, zero-length composing spans being dropped, deletions not crossing
 * the composing region). [RemoteLine] is a minimal line editor standing in for the shell.
 */
class ImeTextProcessorTest {

    private class RemoteLine {
        val text = StringBuilder()
        var cursor = 0 // char index

        fun insert(s: String) {
            text.insert(cursor, s)
            cursor += s.length
        }

        fun backspace() {
            if (cursor == 0) return
            val cp = Character.codePointBefore(text, cursor)
            val n = Character.charCount(cp)
            text.delete(cursor - n, cursor)
            cursor -= n
        }

        fun delete() {
            if (cursor >= text.length) return
            val cp = Character.codePointAt(text, cursor)
            text.delete(cursor, cursor + Character.charCount(cp))
        }

        fun move(delta: Int) {
            repeat(kotlin.math.abs(delta)) {
                if (delta < 0 && cursor > 0) cursor -= Character.charCount(Character.codePointBefore(text, cursor))
                if (delta > 0 && cursor < text.length) cursor += Character.charCount(Character.codePointAt(text, cursor))
            }
        }
    }

    private class RecordingSink : ImeTextProcessor.Sink {
        val events = mutableListOf<String>()
        val remote = RemoteLine()
        var preedit = ""
        var modifiersPending = false

        override fun sendText(text: String, allowModifiers: Boolean): Boolean {
            if (allowModifiers && modifiersPending) {
                modifiersPending = false
                events += "MOD:$text"
                return true
            }
            events += "T:$text"
            remote.insert(text)
            return false
        }

        override fun sendBackspaces(count: Int) {
            events += "BS:$count"
            repeat(count) { remote.backspace() }
        }

        override fun sendForwardDeletes(count: Int) {
            events += "DEL:$count"
            repeat(count) { remote.delete() }
        }

        override fun sendCursorMove(delta: Int) {
            events += "MV:$delta"
            remote.move(delta)
        }

        override fun onPreeditChanged(preedit: String) {
            this.preedit = preedit
        }

        override fun hasPendingModifiers() = modifiersPending
    }

    /** Behaves like BaseInputConnection over a SpannableStringBuilder; never loses text. */
    private class FakeConnection(private val p: ImeTextProcessor) {
        val text = StringBuilder()
        var cs = -1
        var ce = -1
        var cursor = 0
        private var batch = 0
        var resets = 0

        fun batch(block: FakeConnection.() -> Unit) {
            batch++
            block()
            batch--
            if (batch == 0) sync()
        }

        fun setComposing(t: String) {
            replace(t, composing = true)
            syncIfIdle()
        }

        fun commit(t: String) {
            clearSoft()
            replace(t, composing = false)
            syncIfIdle()
        }

        // Soft composition, tracked like TerminalInputConnection does for Gboard 18: a committed
        // single jamo, then the syllable rewritten in place with replaceText (no composing span).
        private var softStart = -1
        private var softEnd = -1

        private fun clearSoft() {
            softStart = -1
            softEnd = -1
        }

        /** commitText of one Hangul character. */
        fun commitJamo(t: String) {
            clearSoft()
            replace(t, composing = false)
            softStart = cursor - t.length
            softEnd = cursor
            syncIfIdle()
        }

        /** replaceText(a, b, t): the last Hangul character before the cursor stays soft. */
        fun replaceText(a: Int, b: Int, t: String) {
            clearSoft()
            cs = -1
            ce = -1
            text.replace(a, b, t)
            cursor = a + t.length
            if (t.isNotEmpty() && HangulKeymap.isHangul(t.last())) {
                softStart = cursor - 1
                softEnd = cursor
            }
            syncIfIdle()
        }

        fun finish() {
            cs = -1
            ce = -1
            syncIfIdle()
        }

        fun setComposingRegion(a: Int, b: Int) {
            if (a == b) {
                cs = -1; ce = -1
            } else {
                cs = minOf(a, b); ce = maxOf(a, b)
            }
            syncIfIdle()
        }

        fun setSelection(at: Int) {
            cursor = at
            syncIfIdle()
        }

        /** deleteSurroundingText(n, 0): local text first (not crossing composition), rest to the remote. */
        fun deleteBefore(n: Int) {
            val anchor = if (cs >= 0) minOf(cs, cursor) else cursor
            val local = minOf(n, anchor)
            text.delete(anchor - local, anchor)
            if (cursor >= anchor) cursor -= local
            if (cs >= anchor) {
                cs -= local
                ce -= local
            }
            p.deleteBeyondEditable(n - local, 0)
            syncIfIdle()
        }

        private fun replace(t: String, composing: Boolean) {
            val a = if (cs >= 0) cs else cursor
            val b = if (cs >= 0) ce else cursor
            text.replace(a, b, t)
            if (composing && t.isNotEmpty()) {
                cs = a
                ce = a + t.length
            } else {
                cs = -1 // zero-length composing spans are dropped by SpannableStringBuilder
                ce = -1
            }
            cursor = a + t.length
        }

        private fun syncIfIdle() {
            if (batch == 0) sync()
        }

        private fun sync() {
            val soft = cs < 0 && softStart >= 0 && softEnd == cursor && softEnd <= text.length
            if (!soft) clearSoft()
            if (p.sync(text, cursor, cursor, if (soft) softStart else cs, if (soft) softEnd else ce)) {
                text.setLength(0)
                cs = -1
                ce = -1
                clearSoft()
                cursor = 0
                p.reset()
                resets++
            }
        }
    }

    private val sink = RecordingSink()
    private val processor = ImeTextProcessor(sink)
    private val ic = FakeConnection(processor)
    private val remote get() = sink.remote.text.toString()

    @Test
    fun gboardWordLevelComposition() {
        // Trace from Gboard 17 (Korean 2-set) typing 한글 then space.
        listOf("ㅎ", "하", "한", "한ㄱ", "한그", "한글").forEach { ic.setComposing(it) }
        assertEquals("", remote, "nothing reaches the remote while composing")
        assertEquals("한글", sink.preedit)
        ic.commit("한글")
        ic.commit(" ")
        assertEquals("한글 ", remote)
        assertEquals("", sink.preedit)
    }

    @Test
    fun gboard18ReplaceTextComposition() {
        // Trace from Gboard 18.5 (Android 17) typing 기존 then space: no composing span; the first
        // jamo is committed and the syllable is rewritten in place with replaceText.
        ic.commitJamo("ㄱ")
        assertEquals("", remote)
        assertEquals("ㄱ", sink.preedit)
        ic.batch { replaceText(0, 1, "기") }
        ic.batch { replaceText(0, 1, "깆") }
        assertEquals("", remote, "loose jamo and half-built syllables stay local")
        assertEquals("깆", sink.preedit)
        ic.batch { replaceText(0, 1, "기조") }
        assertEquals("기", remote, "a syllable goes out once the next one takes over")
        assertEquals("조", sink.preedit)
        ic.batch { replaceText(1, 2, "존") }
        ic.commit(" ")
        assertEquals("기존 ", remote)
        assertEquals("", sink.preedit)
    }

    @Test
    fun gboard18BackspaceDropsUnsentJamo() {
        // Gboard 18.5: backspace decomposes the syllable with replaceText, then removes the lone
        // jamo with a DEL key event (deleted locally). None of it was ever sent.
        ic.commitJamo("ㅂ")
        ic.batch { replaceText(0, 1, "배") }
        ic.batch { replaceText(0, 1, "ㅂ") }
        ic.deleteBefore(1)
        assertEquals("", remote)
        assertEquals("", sink.preedit)
        assertTrue(sink.events.isEmpty(), "nothing reached the remote: ${sink.events}")
        // Typing again starts a fresh syllable.
        ic.commitJamo("ㄱ")
        ic.batch { replaceText(0, 1, "가") }
        ic.commit(" ")
        assertEquals("가 ", remote)
    }

    @Test
    fun syllableLevelComposition() {
        ic.setComposing("ㅎ"); ic.setComposing("하"); ic.setComposing("한")
        ic.batch { commit("한"); setComposing("ㄱ") }
        assertEquals("한", remote)
        assertEquals("ㄱ", sink.preedit)
        ic.setComposing("그"); ic.setComposing("글")
        ic.finish()
        assertEquals("한글", remote)
    }

    @Test
    fun finalConsonantMovesToNextSyllable() {
        ic.setComposing("한")
        ic.batch { commit("하"); setComposing("나") }
        assertEquals("하", remote)
        assertEquals("나", sink.preedit)
        ic.finish()
        assertEquals("하나", remote)
    }

    @Test
    fun backspaceInsideCompositionNeverReachesRemote() {
        ic.setComposing("한"); ic.setComposing("하"); ic.setComposing("ㅎ"); ic.setComposing("")
        assertEquals(emptyList(), sink.events)
        assertEquals("", sink.preedit)
    }

    @Test
    fun backspacesAfterCommitDeleteCommittedText() {
        // Regression: Gboard swallowed the first backspace after a commit when committed text
        // vanished from the editable. Now the field keeps it and deletions are mirrored.
        ic.setComposing("가"); ic.setComposing("가나")
        ic.commit("가나"); ic.commit(" ")
        ic.deleteBefore(1)
        assertEquals("가나", remote)
        ic.deleteBefore(1)
        assertEquals("가", remote)
    }

    @Test
    fun recompositionOfCommittedWordMovesItIntoPreedit() {
        ic.setComposing("가나"); ic.commit("가나"); ic.commit(" ")
        ic.deleteBefore(1)
        // Gboard re-opens the previous word for editing, then deletes a jamo inside it.
        ic.setComposingRegion(0, 2)
        assertEquals("", remote)
        assertEquals("가나", sink.preedit)
        ic.setComposing("가ㄴ")
        assertEquals("가ㄴ", sink.preedit)
        ic.setComposing("간")
        ic.finish()
        assertEquals("간", remote)
        assertEquals("", sink.preedit)
    }

    @Test
    fun predictiveEnglishIsSentKeyByKey() {
        ic.setComposing("l")
        assertEquals("l", remote, "first key reaches the remote immediately (vim/less/htop)")
        ic.setComposing("ls")
        assertEquals("ls", remote)
        assertEquals("", sink.preedit)
        ic.commit("ls")
        ic.commit(" ")
        assertEquals("ls ", remote)
    }

    @Test
    fun autoCorrectionIsFixedUpWithBackspaces() {
        ic.setComposing("t"); ic.setComposing("te"); ic.setComposing("teh")
        ic.commit("the ")
        assertEquals("the ", remote)
    }

    @Test
    fun asciiPrefixThenHangulInSameComposition() {
        ic.setComposing("a")
        ic.setComposing("aㅎ")
        assertEquals("a", remote)
        assertEquals("ㅎ", sink.preedit)
        ic.setComposing("a하")
        ic.commit("a하")
        assertEquals("a하", remote)
    }

    @Test
    fun enterFromCommitTextSendsCarriageReturnAndResets() {
        ic.commit("ls")
        ic.commit("\n")
        assertEquals("ls\r", remote)
        assertEquals(1, ic.resets)
        // A fresh line: the old text is no longer considered part of the field.
        ic.commit("a")
        ic.deleteBefore(1)
        assertEquals("ls\r", remote)
    }

    @Test
    fun stickyModifierTakesFirstComposedJamo() {
        sink.modifiersPending = true
        ic.setComposing("ㅊ")
        assertEquals(listOf("MOD:ㅊ"), sink.events, "the view maps ㅊ to c and applies CTRL")
        assertEquals("", sink.preedit)
        assertEquals(1, ic.resets)
    }

    @Test
    fun stickyModifierOnCommittedTextResetsField() {
        sink.modifiersPending = true
        ic.commit("c")
        assertEquals(listOf("MOD:c"), sink.events)
        assertEquals(1, ic.resets)
    }

    @Test
    fun cursorMovesAreMirroredAndEditsHappenAtTheCursor() {
        ic.commit("abc")
        ic.setSelection(1)
        ic.commit("X")
        assertEquals("aXbc", remote)
    }

    @Test
    fun deletionBeyondTheFieldGoesToTheRemote() {
        ic.deleteBefore(2)
        assertEquals(listOf("BS:2"), sink.events)
    }

    @Test
    fun surrogatePairsCountAsOneCharacter() {
        ic.commit("a\uD83D\uDE00") // a + 😀
        ic.deleteBefore(2)
        assertEquals("a", remote)
        assertEquals("BS:1", sink.events.last())
    }

    @Test
    fun pinyinStyleCompositionIsCorrectedOnCommit() {
        // Romanized input may reach the remote early; what matters is the committed text.
        ic.setComposing("n"); ic.setComposing("ni")
        ic.commit("你")
        assertEquals("你", remote)
    }

    @Test
    fun resetForgetsMirrorWithoutSending() {
        ic.setComposing("한")
        processor.reset()
        assertEquals("", sink.preedit)
        assertTrue(sink.events.isEmpty())
    }
}
