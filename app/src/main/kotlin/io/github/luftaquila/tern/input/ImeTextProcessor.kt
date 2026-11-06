// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.input

/**
 * Mirrors an IME-edited text field onto the remote shell's line editor.
 *
 * The InputConnection keeps a real, consistent editable — exactly what a normal text field
 * would contain — because IMEs (Gboard in particular) track what they committed and misbehave
 * when text silently disappears (e.g. swallowing the first backspace after a commit). This
 * class keeps [remote], a mirror of what the remote line received since the last reset, and
 * after every IME edit computes the minimal operations (cursor moves, backspaces, typed text)
 * that turn the remote line into the editable's text.
 *
 * Composition:
 *  - Hangul and other non-ASCII composing text is not sent; it is shown locally as [preedit]
 *    at the remote cursor until the IME commits it, so syllables never reach the remote as
 *    loose jamo.
 *  - A printable-ASCII prefix of the composing text IS sent eagerly: predictive English
 *    keyboards keep whole words in composition, and TUIs (vim, less, htop) must see each key
 *    immediately. Later changes by the IME (auto-correction) are fixed up by the same diff.
 *  - When an IME re-opens committed text for editing (e.g. backspacing into a Hangul word),
 *    that text leaves the remote and becomes preedit, then is re-sent when committed.
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
class ImeTextProcessor(private val sink: Sink) {

    interface Sink {
        /**
         * Text typed by the user (line breaks already converted to CR). The sink applies sticky
         * CTRL/ALT from the extra-keys row to the first character when [allowModifiers] is set.
         * @return true if modifiers were applied (the remote then received something else than [text]).
         */
        fun sendText(text: String, allowModifiers: Boolean): Boolean

        /** Erase [count] characters before the remote cursor (one DEL each). */
        fun sendBackspaces(count: Int)

        /** Erase [count] characters after the remote cursor. */
        fun sendForwardDeletes(count: Int)

        /** Move the remote cursor by [delta] characters (negative = left). */
        fun sendCursorMove(delta: Int)

        /** Local preedit (composing text that has not been sent) changed. */
        fun onPreeditChanged(preedit: String)

        /** True while a sticky CTRL/ALT from the extra-keys row waits for its key. */
        fun hasPendingModifiers(): Boolean
    }

    /** Send the printable-ASCII prefix of composing text immediately (see class docs). */
    var eagerAscii: Boolean = true

    /** What the remote line received since the last [reset]. */
    var remote: String = ""
        private set

    /** Remote cursor position as an index into [remote]. */
    private var remoteCursor = 0

    var preedit: String = ""
        private set

    /**
     * Brings the remote line in line with the editable.
     *
     * @param selStart/selEnd the editable's selection (cursor), -1 if unknown.
     * @param composingStart/composingEnd the composing span, -1 if none.
     * @return true when the editable no longer describes the remote line (a line break or a
     *   modified key was sent): the caller must clear it and call [reset].
     */
    fun sync(text: CharSequence, selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int): Boolean {
        val s = text.toString()
        val cs = minOf(composingStart, composingEnd)
        val ce = maxOf(composingStart, composingEnd)
        val hasComposing = cs >= 0 && ce > cs && ce <= s.length

        if (hasComposing && sink.hasPendingModifiers()) {
            // CTRL/ALT armed from the extra-keys row: the first key the user composes is the
            // modified key (CTRL + ㅊ = Ctrl-C). Settle everything else, then send that key.
            applyTarget(s.removeRange(cs, ce), cs, allowModifiers = false)
            val cp = s.codePointAt(cs)
            sink.sendText(String(Character.toChars(cp)), allowModifiers = true)
            setPreedit("")
            return true
        }

        val eager = if (hasComposing && eagerAscii) eagerPrefixLength(s, cs, ce) else 0
        val target: String
        val cursor: Int
        if (hasComposing) {
            target = s.substring(0, cs + eager) + s.substring(ce)
            cursor = cs + eager
        } else {
            target = s
            cursor = (if (selEnd >= 0) selEnd else s.length).coerceIn(0, s.length)
        }
        val reset = applyTarget(target, cursor, allowModifiers = true)
        setPreedit(if (hasComposing) s.substring(cs + eager, ce) else "")
        return reset
    }

    /** The IME deleted text before the start of the editable: it goes straight to the remote. */
    fun deleteBeyondEditable(before: Int, after: Int) {
        if (before > 0) sink.sendBackspaces(before)
        if (after > 0) sink.sendForwardDeletes(after)
    }

    /** Forget the mirror without sending anything (the editable was cleared). */
    fun reset() {
        remote = ""
        remoteCursor = 0
        setPreedit("")
    }

    // -------------------------------------------------------------------------------------

    private fun applyTarget(target: String, cursor: Int, allowModifiers: Boolean): Boolean {
        val current = remote
        if (target == current) {
            moveCursor(cursor)
            return false
        }
        val prefix = commonPrefixLength(current, target)
        val suffix = commonSuffixLength(current, target, prefix)
        val currentEnd = current.length - suffix
        val targetEnd = target.length - suffix

        moveCursor(currentEnd)
        if (currentEnd > prefix) sink.sendBackspaces(current.codePointCount(prefix, currentEnd))
        var lineBreak = false
        var modified = false
        if (targetEnd > prefix) {
            val typed = target.substring(prefix, targetEnd)
            lineBreak = typed.indexOf('\n') >= 0 || typed.indexOf('\r') >= 0
            modified = sink.sendText(typed.replace("\r\n", "\r").replace('\n', '\r'), allowModifiers)
        }
        remote = target
        remoteCursor = targetEnd
        if (lineBreak || modified) return true
        moveCursor(cursor)
        return false
    }

    private fun moveCursor(target: Int) {
        val t = target.coerceIn(0, remote.length)
        if (t == remoteCursor) return
        val delta = if (t > remoteCursor) {
            remote.codePointCount(remoteCursor, t)
        } else {
            -remote.codePointCount(t, remoteCursor)
        }
        remoteCursor = t
        if (delta != 0) sink.sendCursorMove(delta)
    }

    private fun setPreedit(value: String) {
        if (value != preedit) {
            preedit = value
            sink.onPreeditChanged(value)
        }
    }

    companion object {
        private fun isEagerChar(c: Char): Boolean = c in ' '..'~'

        private fun eagerPrefixLength(s: String, start: Int, end: Int): Int {
            var i = start
            while (i < end && isEagerChar(s[i])) i++
            return i - start
        }

        /** Common prefix length in chars, never splitting a surrogate pair. */
        internal fun commonPrefixLength(a: String, b: String): Int {
            val max = minOf(a.length, b.length)
            var i = 0
            while (i < max && a[i] == b[i]) i++
            if (i > 0 && i < max && Character.isHighSurrogate(a[i - 1])) i--
            return i
        }

        /** Common suffix length not overlapping [prefix], never splitting a surrogate pair. */
        internal fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
            val max = minOf(a.length, b.length) - prefix
            var n = 0
            while (n < max && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
            if (n > 0 && n < a.length && Character.isLowSurrogate(a[a.length - n])) n--
            return n
        }
    }
}
