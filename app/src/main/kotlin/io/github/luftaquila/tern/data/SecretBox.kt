// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets (passwords, private keys, passphrases) with an AES-256-GCM key that lives in
 * the Android Keystore and never leaves it. Output format: "v1:" + base64(iv || ciphertext).
 */
object SecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "tern.secret.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val TAG_BITS = 128

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String = decrypt(encoded, key())

    private fun decrypt(encoded: String, key: SecretKey): String {
        require(encoded.startsWith(PREFIX)) { "Unknown secret format" }
        val data = Base64.decode(encoded.substring(PREFIX.length), Base64.NO_WRAP)
        val ivLen = data[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 1, ivLen))
        val plain = cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
        return String(plain, Charsets.UTF_8)
    }

    fun decryptOrNull(encoded: String?): String? = encoded?.let { runCatching { decrypt(it) }.getOrNull() }

    /**
     * Moves secrets from keys an earlier version of the app made under another alias (the app's
     * Keystore holds no other keys) to the current key, then deletes those keys. [reencryptAll]
     * must rewrite every stored secret with the function it gets and only return once the result
     * is on disk; a secret that key cannot open is left as it is.
     */
    fun migrateEarlierKeys(reencryptAll: ((String) -> String) -> Unit) {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val earlier = ks.aliases().toList().filter { it != ALIAS && ks.entryInstanceOf(it, KeyStore.SecretKeyEntry::class.java) }
        for (alias in earlier) {
            val old = ks.getKey(alias, null) as? SecretKey ?: continue
            reencryptAll { encoded -> runCatching { encrypt(decrypt(encoded, old)) }.getOrDefault(encoded) }
            ks.deleteEntry(alias)
        }
    }
}
