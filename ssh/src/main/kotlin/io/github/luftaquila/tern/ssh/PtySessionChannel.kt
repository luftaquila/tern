// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Session channel that can send raw encoded terminal modes in its pty-req. sshj's PTYMode
 * enum lacks IUTF8 (RFC 8160, opcode 42), which tells the remote tty driver that input is
 * UTF-8 so canonical-mode line editing erases whole multi-byte characters (e.g. Hangul).
 */
internal class PtySessionChannel(conn: Connection, remoteCharset: Charset) : SessionChannel(conn, remoteCharset) {

    fun requestPty(term: String, columns: Int, rows: Int, widthPixels: Int, heightPixels: Int, modes: ByteArray) {
        sendChannelRequest(
            "pty-req",
            true,
            Buffer.PlainBuffer()
                .putString(term)
                .putUInt32(columns.toLong())
                .putUInt32(rows.toLong())
                .putUInt32(widthPixels.toLong())
                .putUInt32(heightPixels.toLong())
                .putBytes(modes),
        ).await(conn.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Asks the server to pass agent requests back to us (no reply: servers that refuse stay silent). */
    fun requestAgentForwarding() {
        sendChannelRequest(SshAgent.REQUEST, false, Buffer.PlainBuffer())
    }

    companion object {
        private const val TTY_OP_END = 0
        private const val VERASE = 3
        private const val IUTF8 = 42

        /** Terminal modes we request: DEL (0x7f) as erase character, UTF-8 input. */
        val DEFAULT_MODES: ByteArray = SshWriter()
            .byte(VERASE).u32(0x7F)
            .byte(IUTF8).u32(1)
            .byte(TTY_OP_END)
            .toByteArray()
    }
}
