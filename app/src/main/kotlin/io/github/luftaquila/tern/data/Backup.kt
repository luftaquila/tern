// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The export file. Hosts and snippets readable; keys and saved passwords only in [secrets],
 * sealed with the password chosen when exporting. Never holds [SecretBox] ciphertext.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** Hosts as stored, without [Host.encryptedPassword]. */
    val hosts: List<Host> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    /** [BackupSecrets], when the export included them. */
    val secrets: SealedData? = null,
) {
    companion object {
        const val FORMAT = "tern-backup"
        const val VERSION = 1
    }
}

/** The sealed part of an export file. */
@Serializable
data class BackupSecrets(
    /** Saved passwords by host id. */
    val passwords: Map<String, String> = emptyMap(),
    val keys: List<BackupKey> = emptyList(),
)

/** A key with its private key text (and stored passphrase) in the clear. */
@Serializable
data class BackupKey(
    val id: String,
    val name: String,
    val keyType: String,
    val publicKey: String,
    val fingerprint: String,
    val privateKey: String,
    val passphrase: String? = null,
    val certificate: String? = null,
    val createdAt: Long = 0,
)

/** Data encrypted with a password: AES-256-GCM under a key from PBKDF2-HMAC-SHA256. Binary fields base64. */
@Serializable
data class SealedData(
    val kdf: String = Backup.KDF,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val data: String,
)

/** Not an export file of this app, or one from a newer version. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The password does not open the file's sealed part. */
class WrongPasswordException : Exception("wrong password")

/** What an import saves: hosts and snippets to add or replace, keys to add. */
class ImportChanges(val hosts: List<Host>, val keys: List<SshKeyEntry>, val snippets: List<Snippet>)

object Backup {
    const val KDF = "pbkdf2-sha256"
    const val ITERATIONS = 600_000

    /** Highest iteration count an import accepts, so a crafted file cannot stall the app. */
    private const val MAX_ITERATIONS = 10_000_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val JSON = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * The export of [hosts] and [snippets]. With [password], [secrets] (keys and saved passwords)
     * are sealed into it; slow, the key derivation takes a moment.
     */
    fun export(hosts: List<Host>, snippets: List<Snippet>, secrets: BackupSecrets?, password: String?): String {
        val file = BackupFile(
            hosts = hosts.map { it.copy(encryptedPassword = null) },
            snippets = snippets,
            secrets = if (password != null && secrets != null) seal(secrets, password) else null,
        )
        return JSON.encodeToString(BackupFile.serializer(), file)
    }

    /** Reads an export file. */
    fun decode(text: String): BackupFile {
        val file = try {
            JSON.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException("not an export file", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("not an export file", e)
        }
        if (file.format != BackupFile.FORMAT) throw BackupFormatException("not an export file")
        if (file.version > BackupFile.VERSION) throw BackupFormatException("made by a newer version")
        return file
    }

    fun seal(secrets: BackupSecrets, password: String, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): SealedData {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(BackupFile.FORMAT.toByteArray())
        val data = cipher.doFinal(JSON.encodeToString(BackupSecrets.serializer(), secrets).toByteArray(Charsets.UTF_8))
        return SealedData(iterations = iterations, salt = encode(salt), iv = encode(iv), data = encode(data))
    }

    /** Opens [sealed] with [password]; slow, like [seal]. */
    fun open(sealed: SealedData, password: String): BackupSecrets {
        if (sealed.kdf != KDF || sealed.iterations !in 1..MAX_ITERATIONS) throw BackupFormatException("unknown encryption")
        val salt = bytes(sealed.salt)
        val iv = bytes(sealed.iv)
        val data = bytes(sealed.data)
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, sealed.iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(BackupFile.FORMAT.toByteArray())
            cipher.doFinal(data)
        } catch (_: AEADBadTagException) {
            throw WrongPasswordException()
        }
        return try {
            JSON.decodeFromString(BackupSecrets.serializer(), String(plain, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupFormatException("damaged secrets", e)
        }
    }

    /**
     * The changes importing [file] makes to the current [hosts], [keys] and [snippets]. Hosts and
     * snippets replace the ones with the same id and are added otherwise. With [secrets], keys are
     * added unless one with the same fingerprint is here already, and saved passwords are set
     * ([protect] encrypts secrets for storage). A host the file has no password for keeps the one
     * saved here; key and jump host references to nothing here or in the file are cleared.
     */
    fun merge(
        file: BackupFile,
        secrets: BackupSecrets?,
        hosts: List<Host>,
        keys: List<SshKeyEntry>,
        snippets: List<Snippet>,
        protect: (String) -> String,
    ): ImportChanges {
        val keyIds = HashMap<String, String>() // id in the file -> id here
        val newKeys = ArrayList<SshKeyEntry>()
        for (k in secrets?.keys.orEmpty()) {
            val same = (keys + newKeys).firstOrNull { it.fingerprint == k.fingerprint }
            if (same != null) {
                keyIds[k.id] = same.id
                continue
            }
            val id = if ((keys + newKeys).any { it.id == k.id }) UUID.randomUUID().toString() else k.id
            newKeys += SshKeyEntry(
                id = id,
                name = k.name,
                keyType = k.keyType,
                publicKey = k.publicKey,
                fingerprint = k.fingerprint,
                encryptedPrivateKey = protect(k.privateKey),
                encryptedPassphrase = k.passphrase?.let(protect),
                certificate = k.certificate,
                createdAt = k.createdAt,
            )
            keyIds[k.id] = id
        }
        val keyIdsHere = keys.map { it.id }.toSet() + newKeys.map { it.id }
        val imported = file.hosts.distinctBy { it.id }
        val hostIds = hosts.map { it.id }.toSet() + imported.map { it.id }
        val changedHosts = imported.map { h ->
            val here = hosts.firstOrNull { it.id == h.id }
            h.copy(
                encryptedPassword = secrets?.passwords?.get(h.id)?.let(protect) ?: here?.encryptedPassword,
                keyId = h.keyId?.let { keyIds[it] ?: it }?.takeIf { it in keyIdsHere },
                jumpHostId = h.jumpHostId?.takeIf { it in hostIds && it != h.id },
                lastUsedAt = maxOf(h.lastUsedAt, here?.lastUsedAt ?: 0),
            )
        }
        return ImportChanges(changedHosts, newKeys, file.snippets.distinctBy { it.id })
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun bytes(s: String): ByteArray = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("damaged secrets", e)
    }
}

/** Export and import over the stores; secrets move between [SecretBox] and the file's sealed part. */
class Backups(private val hosts: HostRepository, private val keys: KeyRepository, private val snippets: SnippetRepository) {

    /** The export file's text; [password] adds keys and saved passwords. Slow with a password. */
    fun export(password: String?): String {
        val hostList = hosts.hosts.value
        val secrets = password?.let {
            BackupSecrets(
                passwords = hostList.mapNotNull { h -> SecretBox.decryptOrNull(h.encryptedPassword)?.let { h.id to it } }.toMap(),
                keys = keys.keys.value.mapNotNull { k ->
                    val privateKey = SecretBox.decryptOrNull(k.encryptedPrivateKey) ?: return@mapNotNull null
                    BackupKey(
                        k.id, k.name, k.keyType, k.publicKey, k.fingerprint, privateKey,
                        SecretBox.decryptOrNull(k.encryptedPassphrase), k.certificate, k.createdAt,
                    )
                },
            )
        }
        return Backup.export(hostList, snippets.value, secrets, password)
    }

    /** Saves what [Backup.merge] makes of [file]; the keys first, so no host points at a missing one. */
    fun import(file: BackupFile, secrets: BackupSecrets?): ImportChanges {
        val changes = Backup.merge(file, secrets, hosts.hosts.value, keys.keys.value, snippets.value, SecretBox::encrypt)
        keys.addAll(changes.keys)
        hosts.upsertAll(changes.hosts)
        snippets.upsertAll(changes.snippets)
        return changes
    }
}
