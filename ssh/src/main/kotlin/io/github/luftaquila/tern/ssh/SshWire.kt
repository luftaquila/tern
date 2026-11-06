// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/** Minimal SSH wire-format writer (RFC 4251 §5). */
internal class SshWriter {
    private val out = ByteArrayOutputStream()

    fun u32(value: Int): SshWriter {
        out.write(value ushr 24 and 0xFF)
        out.write(value ushr 16 and 0xFF)
        out.write(value ushr 8 and 0xFF)
        out.write(value and 0xFF)
        return this
    }

    fun byte(value: Int): SshWriter {
        out.write(value and 0xFF)
        return this
    }

    fun raw(bytes: ByteArray): SshWriter {
        out.write(bytes, 0, bytes.size)
        return this
    }

    /** SSH "string": uint32 length + bytes. */
    fun bytes(bytes: ByteArray): SshWriter = u32(bytes.size).raw(bytes)

    fun string(value: String): SshWriter = bytes(value.toByteArray(Charsets.UTF_8))

    /** SSH "mpint" for a non-negative value: minimal two's complement, empty for zero. */
    fun mpint(value: BigInteger): SshWriter {
        require(value.signum() >= 0) { "negative mpint" }
        return bytes(if (value.signum() == 0) ByteArray(0) else value.toByteArray())
    }

    fun size(): Int = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal SSH wire-format reader. Throws [IllegalArgumentException] on truncated input. */
internal class SshReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = data.size - pos

    fun u32(): Int {
        require(remaining >= 4) { "truncated uint32" }
        val v = (data[pos].toInt() and 0xFF shl 24) or
            (data[pos + 1].toInt() and 0xFF shl 16) or
            (data[pos + 2].toInt() and 0xFF shl 8) or
            (data[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun byte(): Int {
        require(remaining >= 1) { "truncated byte" }
        return data[pos++].toInt() and 0xFF
    }

    fun raw(length: Int): ByteArray {
        require(length in 0..remaining) { "truncated data" }
        val r = data.copyOfRange(pos, pos + length)
        pos += length
        return r
    }

    fun bytes(): ByteArray {
        val n = u32()
        require(n >= 0) { "negative length" }
        return raw(n)
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)
}

internal object Pem {
    private const val LINE = 70

    fun armor(label: String, body: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(body)
        val sb = StringBuilder()
        sb.append("-----BEGIN ").append(label).append("-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(b64.length, i + LINE)).append('\n')
            i += LINE
        }
        sb.append("-----END ").append(label).append("-----\n")
        return sb.toString()
    }

    /**
     * Decodes the base64 body of the first `-----BEGIN [label]-----` block, skipping RFC 1421
     * style "Name: value" header lines. Returns null when the block is absent or malformed.
     */
    fun decode(text: String, label: String): ByteArray? {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val lines = text.lineSequence().map { it.trim() }.toList()
        val start = lines.indexOf(begin)
        if (start < 0) return null
        val stop = lines.subList(start + 1, lines.size).indexOf(end)
        if (stop < 0) return null
        val body = lines.subList(start + 1, start + 1 + stop)
            .filter { it.isNotEmpty() && !it.contains(':') }
            .joinToString("")
        return try {
            Base64.getMimeDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

internal object Fingerprints {
    /** OpenSSH style "SHA256:<base64 without padding>" of a public key wire blob. */
    fun sha256(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
