// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Verifiers for the app lock's PIN or pattern: salted PBKDF2-HMAC-SHA256, compared in constant
 * time. The stored verifier is additionally encrypted with the Keystore key ([SecretBox]), so
 * a copied settings file cannot be brute-forced elsewhere.
 */
object LockSecrets {
    private const val PREFIX = "pbkdf2-sha256"
    private const val ITERATIONS = 60_000

    fun pin(digits: String): String = "pin:$digits"

    /** A pattern as the dots it connects, numbered 0..8 row by row. */
    fun pattern(dots: List<Int>): String = "pattern:" + dots.joinToString(",")

    fun create(secret: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(secret, salt, ITERATIONS)
        return listOf(PREFIX, ITERATIONS.toString(), encode(salt), encode(hash)).joinToString("$")
    }

    fun verify(secret: String, verifier: String): Boolean {
        val parts = verifier.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = decode(parts[2]) ?: return false
        val hash = decode(parts[3]) ?: return false
        return MessageDigest.isEqual(derive(secret, salt, iterations), hash)
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun decode(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
}
