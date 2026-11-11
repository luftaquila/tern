// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {

    private val web = Host(
        id = "web", label = "web", hostname = "web.example.com", username = "me", encryptedPassword = "v1:device-only",
        authMethod = AuthMethod.KEY, keyId = "k1", group = "prod", tags = listOf("http"), jumpHostId = "bastion",
    )
    private val bastion = Host(id = "bastion", hostname = "bastion.example.com", username = "me")
    private val snippet = Snippet("s1", "gs", "git status\r")
    private val key = BackupKey(
        "k1", "laptop", "ssh-ed25519", "AAAA", "SHA256:abc", "-----BEGIN OPENSSH PRIVATE KEY-----",
        passphrase = "pass", certificate = "ssh-ed25519-cert-v01@openssh.com AAAB", createdAt = 5,
    )
    private val protect: (String) -> String = { "sealed($it)" }

    @Test
    fun exportWithoutPasswordHoldsNoSecrets() {
        val text = Backup.export(listOf(web, bastion), listOf(snippet), BackupSecrets(mapOf("web" to "pw")), password = null)
        assertFalse("device-only" in text, "Keystore ciphertext never goes into the file")
        assertFalse("pw\"" in text)
        val file = Backup.decode(text)
        assertNull(file.secrets)
        assertEquals(listOf(web.copy(encryptedPassword = null), bastion), file.hosts, "groups, tags, keys and jump hosts kept")
        assertEquals(listOf(snippet), file.snippets)
    }

    @Test
    fun secretsOpenOnlyWithTheirPassword() {
        val secrets = BackupSecrets(mapOf("web" to "hunter2"), listOf(key))
        val text = Backup.export(listOf(web), emptyList(), secrets, password = "correct horse")
        assertFalse("hunter2" in text || "PRIVATE KEY" in text, "secrets only sealed")
        val sealed = Backup.decode(text).secrets!!
        assertEquals(Backup.ITERATIONS, sealed.iterations)
        assertEquals(secrets, Backup.open(sealed, "correct horse"))
        assertFailsWith<WrongPasswordException> { Backup.open(sealed, "wrong") }
    }

    @Test
    fun otherFilesAreRejected() {
        assertFailsWith<BackupFormatException> { Backup.decode("not json") }
        assertFailsWith<BackupFormatException> { Backup.decode("""{"format":"something-else","hosts":[]}""") }
        assertFailsWith<BackupFormatException> { Backup.decode("""{"format":"tern-backup","version":${BackupFile.VERSION + 1}}""") }
        val crafted = Backup.seal(BackupSecrets(), "pw", iterations = 1).copy(iterations = Int.MAX_VALUE)
        assertFailsWith<BackupFormatException>("a huge iteration count is refused before deriving") { Backup.open(crafted, "pw") }
    }

    @Test
    fun importReplacesByIdAndAddsTheRest() {
        val here = listOf(web.copy(label = "old web", lastUsedAt = 50), Host(id = "local", hostname = "local"))
        val file = BackupFile(hosts = listOf(web.copy(encryptedPassword = null, lastUsedAt = 10), bastion), snippets = listOf(snippet))
        val changes = Backup.merge(file, null, here, keys = listOf(entry("k1", "SHA256:abc")), snippets = emptyList(), protect)
        val imported = changes.hosts.associateBy { it.id }
        assertEquals(setOf("web", "bastion"), imported.keys, "hosts only here are untouched")
        assertEquals("web", imported.getValue("web").label, "the file's version wins")
        assertEquals("v1:device-only", imported.getValue("web").encryptedPassword, "a password saved here stays when the file has none")
        assertEquals(50, imported.getValue("web").lastUsedAt)
        assertEquals("k1", imported.getValue("web").keyId, "the key is here")
        assertEquals("bastion", imported.getValue("web").jumpHostId)
        assertEquals(listOf(snippet), changes.snippets)
        assertTrue(changes.keys.isEmpty())
    }

    @Test
    fun importWithSecretsAddsKeysAndPasswords() {
        val file = BackupFile(hosts = listOf(web.copy(encryptedPassword = null), bastion))
        val secrets = BackupSecrets(mapOf("web" to "hunter2"), listOf(key))
        val changes = Backup.merge(file, secrets, hosts = emptyList(), keys = emptyList(), snippets = emptyList(), protect)
        val added = changes.keys.single()
        assertEquals("k1", added.id)
        assertEquals("sealed(-----BEGIN OPENSSH PRIVATE KEY-----)", added.encryptedPrivateKey)
        assertEquals("sealed(pass)", added.encryptedPassphrase)
        assertEquals("ssh-ed25519-cert-v01@openssh.com AAAB", added.certificate, "the certificate comes along")
        val imported = changes.hosts.first { it.id == "web" }
        assertEquals("sealed(hunter2)", imported.encryptedPassword)
        assertEquals("k1", imported.keyId)
    }

    @Test
    fun importMatchesKeysByFingerprint() {
        val file = BackupFile(hosts = listOf(web.copy(encryptedPassword = null)))
        // The same key was imported here before under another id; another key here already has id k1.
        val other = key.copy(id = "k2", fingerprint = "SHA256:other")
        val here = listOf(entry("mine", "SHA256:abc"), entry("k2", "SHA256:zzz"))
        val changes = Backup.merge(file, BackupSecrets(keys = listOf(key, other)), emptyList(), here, emptyList(), protect)
        assertEquals("mine", changes.hosts.single().keyId, "the host uses the key already here")
        val added = changes.keys.single()
        assertEquals("SHA256:other", added.fingerprint)
        assertNotEquals("k2", added.id, "a taken id is not reused")
    }

    @Test
    fun importClearsReferencesToNothing() {
        val lone = web.copy(id = "lone", encryptedPassword = null, keyId = "gone", jumpHostId = "nowhere")
        val changes = Backup.merge(BackupFile(hosts = listOf(lone)), null, emptyList(), emptyList(), emptyList(), protect)
        val imported = changes.hosts.single()
        assertNull(imported.keyId)
        assertNull(imported.jumpHostId)
        assertNull(imported.encryptedPassword)
    }

    private fun entry(id: String, fingerprint: String) =
        SshKeyEntry(id = id, name = id, keyType = "ssh-ed25519", publicKey = "AAAA", fingerprint = fingerprint, encryptedPrivateKey = "v1:x")
}
