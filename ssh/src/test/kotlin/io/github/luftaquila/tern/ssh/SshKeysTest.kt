// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshKeysTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keygen: String
        get() {
            val k = SSH_KEYGEN
            assumeTrue("ssh-keygen not available", k != null)
            return k!!.path
        }

    @Test
    fun generatedKeysRoundTripThroughSshjAndOpenSsh() {
        for (type in SshKeyType.values()) {
            val key = SshKeys.generate(type, "tern@test 한글")
            assertTrue(key.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"), type.name)
            assertTrue(key.publicKey.endsWith(" tern@test 한글"), key.publicKey)
            assertFalse(SshKeys.isEncrypted(key.privateKey))

            // sshj can read it back, and our public key / fingerprint match.
            val info = SshKeys.inspect(key.privateKey, null)
            assertEquals(key.publicKey, info.publicKey, type.name)
            assertEquals(key.fingerprintSha256, info.fingerprintSha256, type.name)
            assertEquals(key.publicKey.substringBefore(' '), info.keyType)

            // OpenSSH agrees on the public key and the SHA256 fingerprint.
            val file = privateKeyFile(tmp.root.toPath(), "id_${type.name.lowercase()}", key.privateKey)
            val opensshPublic = run(keygen, "-y", "-f", file.path).trim()
            assertEquals(keyId(key.publicKey), keyId(opensshPublic), type.name)
            val fingerprintLine = run(keygen, "-l", "-E", "sha256", "-f", file.path)
            assertTrue(fingerprintLine.contains(key.fingerprintSha256), "$type: $fingerprintLine vs ${key.fingerprintSha256}")
            assertTrue(fingerprintLine.contains("tern@test"), "$type comment: $fingerprintLine")
        }
    }

    @Test
    fun exportedKeysOpenWithTheNewPassphraseInOpenSsh() {
        for (type in listOf(SshKeyType.ED25519, SshKeyType.ECDSA_P256, SshKeyType.RSA_3072)) {
            val key = SshKeys.generate(type, "")
            val exported = SshKeys.exportOpenSsh(key.privateKey, null, "phone", "new pass")
            assertTrue(SshKeys.isEncrypted(exported), type.name)
            assertFailsWith<KeyPassphraseException> { SshKeys.inspect(exported, "wrong") }
            assertEquals(keyId(key.publicKey), keyId(SshKeys.inspect(exported, "new pass").publicKey), type.name)

            val file = privateKeyFile(tmp.root.toPath(), "id_${type.name.lowercase()}", exported)
            assertEquals(keyId(key.publicKey), keyId(run(keygen, "-y", "-P", "new pass", "-f", file.path)), type.name)

            val plain = SshKeys.exportOpenSsh(key.privateKey, null, "phone", null)
            assertFalse(SshKeys.isEncrypted(plain))
            val plainFile = privateKeyFile(tmp.root.toPath(), "plain_${type.name.lowercase()}", plain)
            assertEquals(keyId(key.publicKey), keyId(run(keygen, "-y", "-f", plainFile.path)), type.name)
            assertTrue(run(keygen, "-l", "-f", plainFile.path).contains("phone"), "$type keeps its comment")
        }
    }

    @Test
    fun importedPemKeyExportsAsOpenSsh() {
        val file = File(tmp.root, "id_pem")
        run(keygen, "-q", "-t", "ecdsa", "-b", "384", "-m", "PEM", "-N", "old pass", "-f", file.path)
        val pem = file.readText()
        assertFailsWith<KeyPassphraseException> { SshKeys.exportOpenSsh(pem, null, "laptop", "new pass") }

        val exported = SshKeys.exportOpenSsh(pem, "old pass", "laptop", "new pass")
        assertTrue(exported.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        val out = privateKeyFile(tmp.root.toPath(), "id_exported", exported)
        assertEquals(keyId(File(tmp.root, "id_pem.pub").readText()), keyId(run(keygen, "-y", "-P", "new pass", "-f", out.path)))
    }

    @Test
    fun keyTypesAndSizes() {
        assertEquals("ssh-ed25519", SshKeys.generate(SshKeyType.ED25519, "").publicKey.substringBefore(' '))
        assertEquals("ecdsa-sha2-nistp256", SshKeys.generate(SshKeyType.ECDSA_P256, "").publicKey.substringBefore(' '))
        val rsa = SshKeys.generate(SshKeyType.RSA_3072, "")
        assertEquals("ssh-rsa", rsa.publicKey.substringBefore(' '))
        // No trailing space when there is no comment.
        assertEquals(2, rsa.publicKey.split(' ').size)
        if (SSH_KEYGEN != null) {
            val file = privateKeyFile(tmp.root.toPath(), "id_rsa3072", rsa.privateKey)
            assertTrue(run(keygen, "-l", "-f", file.path).startsWith("3072 "))
        }
    }

    @Test
    fun inspectsOpenSshGeneratedKeysIncludingComment() {
        val file = File(tmp.root, "id_ed25519")
        run(keygen, "-q", "-t", "ed25519", "-N", "", "-C", "user@laptop", "-f", file.path)
        val text = file.readText()
        assertFalse(SshKeys.isEncrypted(text))
        val info = SshKeys.inspect(text, null)
        assertEquals(File(tmp.root, "id_ed25519.pub").readText().trim(), info.publicKey)
        assertEquals("ssh-ed25519", info.keyType)
    }

    @Test
    fun encryptedOpenSshKeyNeedsCorrectPassphrase() {
        val file = File(tmp.root, "id_enc")
        run(keygen, "-q", "-t", "ed25519", "-N", "correct horse", "-C", "enc", "-f", file.path)
        val text = file.readText()
        assertTrue(SshKeys.isEncrypted(text))
        assertFailsWith<KeyPassphraseException> { SshKeys.inspect(text, null) }
        assertFailsWith<KeyPassphraseException> { SshKeys.inspect(text, "wrong") }
        val info = SshKeys.inspect(text, "correct horse")
        assertEquals(keyId(File(tmp.root, "id_enc.pub").readText()), keyId(info.publicKey))
    }

    @Test
    fun pemFormats() {
        data class Case(val name: String, val args: List<String>, val passphrase: String, val header: String)
        val cases = listOf(
            Case("rsa_pem", listOf("-t", "rsa", "-b", "2048", "-m", "PEM"), "", "-----BEGIN RSA PRIVATE KEY-----"),
            Case("rsa_pem_enc", listOf("-t", "rsa", "-b", "2048", "-m", "PEM"), "passphrase-1", "-----BEGIN RSA PRIVATE KEY-----"),
            Case("ec_pem", listOf("-t", "ecdsa", "-b", "256", "-m", "PEM"), "", "-----BEGIN EC PRIVATE KEY-----"),
            Case("ec_pem_enc", listOf("-t", "ecdsa", "-b", "384", "-m", "PEM"), "passphrase-2", "-----BEGIN EC PRIVATE KEY-----"),
            Case("rsa_pkcs8", listOf("-t", "rsa", "-b", "2048", "-m", "PKCS8"), "", "-----BEGIN PRIVATE KEY-----"),
            Case("rsa_pkcs8_enc", listOf("-t", "rsa", "-b", "2048", "-m", "PKCS8"), "passphrase-3", "-----BEGIN ENCRYPTED PRIVATE KEY-----"),
        )
        for (c in cases) {
            val file = File(tmp.root, c.name)
            run(keygen, "-q", *c.args.toTypedArray(), "-N", c.passphrase, "-C", c.name, "-f", file.path)
            val text = file.readText()
            assertTrue(text.startsWith(c.header), "${c.name}: ${text.lineSequence().first()}")
            val encrypted = c.passphrase.isNotEmpty()
            assertEquals(encrypted, SshKeys.isEncrypted(text), c.name)
            if (encrypted) {
                assertFailsWith<KeyPassphraseException>(c.name) { SshKeys.inspect(text, null) }
                assertFailsWith<KeyPassphraseException>(c.name) { SshKeys.inspect(text, "nope") }
            }
            val info = SshKeys.inspect(text, c.passphrase.ifEmpty { null })
            val expected = File(tmp.root, "${c.name}.pub").readText()
            assertEquals(keyId(expected), keyId(info.publicKey), c.name)
            val fingerprint = run(keygen, "-l", "-E", "sha256", "-f", File(tmp.root, "${c.name}.pub").path)
            assertTrue(fingerprint.contains(info.fingerprintSha256), "${c.name}: $fingerprint")
        }
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<SshException> { SshKeys.inspect("not a key", null) }
        assertFailsWith<SshException> { SshKeys.inspect("", null) }
        assertFailsWith<SshException> {
            SshKeys.inspect("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n", null)
        }
        assertFalse(SshKeys.isEncrypted("garbage"))
    }

    @Test
    fun corruptOrUndecryptedKeysFailCleanly() {
        // sshj's DER parser allocates whatever length the input claims; garbage must never
        // reach it (an OutOfMemoryError would crash the app).
        val random = java.util.Random(1234)
        val labels = listOf("PRIVATE KEY", "ENCRYPTED PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY", "OPENSSH PRIVATE KEY")
        repeat(600) { i ->
            val body = ByteArray(16 + random.nextInt(400)).also { random.nextBytes(it) }
            // Make some bodies look like a DER SEQUENCE with an absurd length.
            if (i % 3 == 0) {
                body[0] = 0x30
                body[1] = 0x84.toByte()
                body[2] = 0x7F
            }
            val text = Pem.armor(labels[i % labels.size], body)
            try {
                SshKeys.inspect(text, if (i % 2 == 0) null else "pw")
            } catch (e: SshException) {
                // expected
            }
        }
        // Encrypted keys: a missing passphrase short-circuits; many wrong passphrases
        // (some decrypt to garbage with valid padding) must still fail as passphrase errors.
        val file = File(tmp.root, "enc_ec")
        run(keygen, "-q", "-t", "ecdsa", "-b", "256", "-m", "PEM", "-N", "right-pass", "-f", file.path)
        val text = file.readText()
        assertFailsWith<KeyPassphraseException> { SshKeys.inspect(text, null) }
        repeat(400) { n ->
            assertFailsWith<KeyPassphraseException> { SshKeys.inspect(text, "wrong-$n") }
        }
        SshKeys.inspect(text, "right-pass")
    }

    @Test
    fun puttyEncryptionHeaderDetection() {
        val unencrypted = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\nComment: c\n"
        val encrypted = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes256-cbc\nComment: c\n"
        assertFalse(SshKeys.isEncrypted(unencrypted))
        assertTrue(SshKeys.isEncrypted(encrypted))
    }
}
