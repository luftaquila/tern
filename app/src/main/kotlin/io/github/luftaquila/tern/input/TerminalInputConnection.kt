// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.input

import android.content.pm.ApplicationInfo
import android.os.Build
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi

/**
 * InputConnection for the terminal. Behaves like a plain text field towards the IME — the
 * editable keeps everything the IME committed, and every change is reported back through
 * [InputMethodManager.updateSelection] — while [ImeTextProcessor] mirrors the editable onto
 * the remote line. The editable is only cleared at points where the remote line changes in a
 * way the IME cannot know about (Enter, arrows, Tab, Ctrl keys, paste); the IME is told then.
 *
 * Soft composition: Gboard 18 composes Hangul without a composing span. It commits the first
 * jamo (`commitText("ㄱ")`) and then rewrites the syllable in place with `replaceText`
 * ("기", "깆", "기조", ...). The syllable built that way is treated like composing text (shown
 * as preedit, not sent) until an edit that cannot continue it, so loose jamo and half-built
 * syllables never reach the remote.
 */
class TerminalInputConnection(
    private val view: View,
    private val processor: ImeTextProcessor,
    private val callbacks: Callbacks,
) : BaseInputConnection(view, true) {

    interface Callbacks {
        /** Key events from the IME (DEL on an empty field, ENTER, arrows from cursor swipes, digits, ...). */
        fun onImeKeyEvent(event: KeyEvent)

        /** IME action button (some keyboards use it for Enter despite IME_FLAG_NO_ENTER_ACTION). */
        fun onImeEditorAction()

        fun onImePaste()
    }

    private val imm: InputMethodManager = view.context.getSystemService(InputMethodManager::class.java)
    private val editable: Editable = SpannableStringBuilder()
    private var batchDepth = 0
    private var reportedSelStart = -1
    private var reportedSelEnd = -1
    private var reportedCompStart = -1
    private var reportedCompEnd = -1

    /** Soft composition range (see class docs), -1 when none. Always ends at the cursor. */
    private var softStart = -1
    private var softEnd = -1

    /** Tracing for debug builds only: typed text (passwords!) must never reach logcat in release. */
    private val trace = view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun log(msg: String) {
        if (trace) {
            Log.d(
                TAG,
                "$msg | ed='$editable' sel=${Selection.getSelectionStart(editable)} " +
                    "comp=${getComposingSpanStart(editable)}..${getComposingSpanEnd(editable)} " +
                    "soft=$softStart..$softEnd batch=$batchDepth",
            )
        }
    }

    override fun getEditable(): Editable = editable

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) sync()
        return batchDepth > 0
    }

    // Each IME edit runs inside its own batch, so the mirror is synced once, after the soft
    // composition was updated (BaseInputConnection's edits batch internally as well).

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        log("commitText('$text', $newCursorPosition)")
        val t = text ?: ""
        beginBatchEdit()
        clearSoft()
        super.commitText(t, newCursorPosition)
        if (t.length == 1 && isHangul(t[0])) markSoftBeforeCursor(t.length)
        endBatchEdit()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        log("replaceText($start, $end, '$text', $newCursorPosition)")
        beginBatchEdit()
        clearSoft()
        val oldLength = editable.length
        val replaced = super.replaceText(start, end, text, newCursorPosition, textAttribute)
        // Only the last syllable can still change; an earlier one is final once the IME
        // starts the next (깆 + ㅗ = 기조: "기" is done).
        if (replaced && text.isNotEmpty() && isHangul(text[text.length - 1])) {
            val s = minOf(start, end).coerceAtMost(oldLength) // as BaseInputConnection clamps
            if (Selection.getSelectionEnd(editable) == s + text.length) markSoftBeforeCursor(1)
        }
        endBatchEdit()
        return replaced
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        log("setComposingText('$text', $newCursorPosition)")
        beginBatchEdit()
        clearSoft()
        super.setComposingText(text ?: "", newCursorPosition)
        endBatchEdit()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        log("setComposingRegion($start, $end)")
        beginBatchEdit()
        clearSoft()
        super.setComposingRegion(start, end)
        endBatchEdit()
        return true
    }

    override fun finishComposingText(): Boolean {
        log("finishComposingText")
        beginBatchEdit()
        clearSoft()
        super.finishComposingText()
        endBatchEdit()
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        log("setSelection($start, $end)")
        beginBatchEdit()
        clearSoft()
        super.setSelection(start, end)
        endBatchEdit()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        log("deleteSurroundingText($beforeLength, $afterLength)")
        beginBatchEdit()
        val (start, end) = protectedRange()
        val localBefore = minOf(beforeLength.coerceAtLeast(0), start)
        val localAfter = minOf(afterLength.coerceAtLeast(0), editable.length - end)
        if (localBefore > 0 || localAfter > 0) super.deleteSurroundingText(localBefore, localAfter)
        processor.deleteBeyondEditable(beforeLength - localBefore, afterLength - localAfter)
        endBatchEdit() // a deleted soft syllable was never sent; sync drops it (validSoftRange)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        log("deleteSurroundingTextInCodePoints($beforeLength, $afterLength)")
        beginBatchEdit()
        val (start, end) = protectedRange()
        val availBefore = Character.codePointCount(editable, 0, start)
        val availAfter = Character.codePointCount(editable, end, editable.length)
        val localBefore = minOf(beforeLength.coerceAtLeast(0), availBefore)
        val localAfter = minOf(afterLength.coerceAtLeast(0), availAfter)
        if (localBefore > 0 || localAfter > 0) super.deleteSurroundingTextInCodePoints(localBefore, localAfter)
        processor.deleteBeyondEditable(beforeLength - localBefore, afterLength - localAfter)
        endBatchEdit()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        log("sendKeyEvent(${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action})")
        callbacks.onImeKeyEvent(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        log("performEditorAction($actionCode)")
        callbacks.onImeEditorAction()
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            callbacks.onImePaste()
            return true
        }
        return false
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
        val r = super.getTextBeforeCursor(length, flags)
        log("getTextBeforeCursor($length) -> '$r'")
        return r
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        val r = super.getExtractedText(request, flags)
        log("getExtractedText -> '${r?.text}'")
        return r
    }

    override fun closeConnection() {
        log("closeConnection")
        clearSoft()
        super.closeConnection() // finishes composition: whatever was composed gets sent
        batchDepth = 0
        sync()
    }

    // ---- app-initiated edits (called by the view, outside of IME callbacks) ---------------

    /** Inserts typed text like a text field would (hardware keys, IMEs sending key events). */
    fun insertText(text: String) {
        beginBatchEdit()
        clearSoft()
        super.finishComposingText()
        super.commitText(text, 1)
        endBatchEdit()
    }

    /**
     * Backspace from a key event. Deletes locally (keeping the IME consistent) when there is
     * text before the cursor; returns false when the editable has nothing to delete, in which
     * case the caller sends the backspace straight to the remote. A soft-composed syllable is
     * deleted like any text (it was never sent, so nothing goes out for it).
     */
    fun deleteBackward(): Boolean {
        val a = Selection.getSelectionStart(editable)
        val b = Selection.getSelectionEnd(editable)
        if (a < 0 || b < 0) return false
        beginBatchEdit()
        super.finishComposingText()
        val start = minOf(a, b)
        val end = maxOf(a, b)
        val deleted = when {
            start != end -> { editable.delete(start, end); true }
            start > 0 -> {
                val cp = Character.codePointBefore(editable, start)
                editable.delete(start - Character.charCount(cp), start)
                true
            }
            else -> false
        }
        endBatchEdit()
        return deleted
    }

    /** Sends any pending composition. @return true if there was one (the IME must then be restarted). */
    fun commitComposition(): Boolean {
        val composing = getComposingSpanStart(editable) >= 0 || validSoftRange() != null || processor.preedit.isNotEmpty()
        if (!composing) return false
        beginBatchEdit()
        clearSoft()
        super.finishComposingText()
        endBatchEdit()
        return true
    }

    /**
     * Forgets the editable after the remote line changed behind the IME's back. With
     * [restartIme] the IME is restarted (needed when it may still hold a composition);
     * otherwise it is just told that the field is now empty.
     */
    fun resetEditable(restartIme: Boolean) {
        clearSoft()
        removeComposingSpans(editable)
        editable.clear()
        Selection.setSelection(editable, 0)
        processor.reset()
        if (restartIme) {
            reportedSelStart = -1
            reportedSelEnd = -1
            reportedCompStart = -1
            reportedCompEnd = -1
            imm.restartInput(view)
        } else {
            reportSelection()
        }
    }

    val isEmpty: Boolean get() = editable.isEmpty()

    /** The IME holds an unfinished syllable/word: a composing span or a soft composition. */
    val isComposing: Boolean get() = hasComposingSpan || validSoftRange() != null

    /** A real composing span (the IME edits it with setComposingText). */
    val hasComposingSpan: Boolean get() = getComposingSpanStart(editable) >= 0

    // -------------------------------------------------------------------------------------

    private fun clearSoft() {
        softStart = -1
        softEnd = -1
    }

    /** Marks the [length] chars before the (collapsed) cursor as soft composition. */
    private fun markSoftBeforeCursor(length: Int) {
        val a = Selection.getSelectionStart(editable)
        val b = Selection.getSelectionEnd(editable)
        if (a != b || b < length) return
        softStart = b - length
        softEnd = b
    }

    /** The soft composition if it is still where it was put (right before a collapsed cursor). */
    private fun validSoftRange(): IntRange? {
        if (softStart < 0) return null
        val a = Selection.getSelectionStart(editable)
        val b = Selection.getSelectionEnd(editable)
        if (a != b || b != softEnd || softEnd > editable.length || softStart >= softEnd || hasComposingSpan) {
            clearSoft()
            return null
        }
        return softStart until softEnd
    }

    private fun sync() {
        if (Selection.getSelectionStart(editable) < 0) Selection.setSelection(editable, editable.length)
        val soft = validSoftRange()
        val compStart = soft?.first ?: getComposingSpanStart(editable)
        val compEnd = soft?.let { it.last + 1 } ?: getComposingSpanEnd(editable)
        val needsReset = processor.sync(
            editable,
            Selection.getSelectionStart(editable),
            Selection.getSelectionEnd(editable),
            compStart,
            compEnd,
        )
        if (needsReset) {
            // A line break or a CTRL/ALT key went out: the remote line is no longer this text.
            if (compStart >= 0) {
                // The IME still holds a composition: restart it (posted, we are inside its call).
                view.post { resetEditable(restartIme = true) }
            } else {
                resetEditable(restartIme = false)
            }
        } else {
            if (editable.length > MAX_EDITABLE && compStart < 0) {
                resetEditable(restartIme = false)
            } else {
                reportSelection()
            }
        }
    }

    /** Reports the selection and the real composing span (never the soft one) to the IME. */
    private fun reportSelection() {
        val ss = Selection.getSelectionStart(editable)
        val se = Selection.getSelectionEnd(editable)
        val cs = getComposingSpanStart(editable)
        val ce = getComposingSpanEnd(editable)
        if (ss == reportedSelStart && se == reportedSelEnd && cs == reportedCompStart && ce == reportedCompEnd) return
        reportedSelStart = ss
        reportedSelEnd = se
        reportedCompStart = cs
        reportedCompEnd = ce
        imm.updateSelection(view, ss, se, cs, ce)
    }

    /** Selection/composing range that local deletions must not cross. */
    private fun protectedRange(): Pair<Int, Int> {
        var a = Selection.getSelectionStart(editable)
        var b = Selection.getSelectionEnd(editable)
        if (a < 0) a = editable.length
        if (b < 0) b = editable.length
        if (a > b) a = b.also { b = a }
        val cs = getComposingSpanStart(editable)
        val ce = getComposingSpanEnd(editable)
        if (cs >= 0 && ce >= 0) {
            a = minOf(a, minOf(cs, ce))
            b = maxOf(b, maxOf(cs, ce))
        }
        return a to b
    }

    companion object {
        private const val TAG = "TernIme"

        /** Long lines without Enter: start over to keep diffs cheap (the remote is unaffected). */
        private const val MAX_EDITABLE = 4000

        private fun isHangul(c: Char) = HangulKeymap.isHangul(c)
    }
}
