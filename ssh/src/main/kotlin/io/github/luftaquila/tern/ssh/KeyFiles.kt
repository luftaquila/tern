// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.IOException
import java.io.StringReader
import java.security.KeyPair
import java.security.PublicKey
import java.util.Base64

/** Private key parsing on top of sshj's file key providers. */
internal object KeyFiles {

    private const val OPENSSH_LABEL = "OPENSSH PRIVATE KEY"
    private const val AUTH_MAGIC = "openssh-key-v1\u0000"

    /** Detects encryption from the key text alone (no passphrase needed). */
    fun isEncrypted(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("PuTTY-User-Key-File-")) {
            val encryption = trimmed.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Encryption:") }
                ?.substringAfter(':')
                ?.trim()
            return encryption != null && encryption != "none"
        }
        if (trimmed.contains("-----BEGIN $OPENSSH_LABEL-----")) {
            return openSshCipher(trimmed)?.let { it != "none" } ?: false
        }
        if (trimmed.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) return true
        return trimmed.lineSequence().any { it.trim() == "Proc-Type: 4,ENCRYPTED" }
    }

    /**
     * Loads a key pair. Throws [KeyPassphraseException] when the key is encrypted and the
     * passphrase is missing or wrong, [SshException] for unsupported or corrupt keys.
     *
     * PEM/DER keys are parsed and decrypted by BouncyCastle, whose ASN.1 parser bounds every
     * length. sshj's own DER parser (asn-one) allocates whatever length the input claims, so
     * feeding it ciphertext or a corrupted key can throw OutOfMemoryError; it only ever sees
     * DER that BouncyCastle has already validated.
     */
    fun load(text: String, passphrase: String?): KeyPair {
        val encrypted = isEncrypted(text)
        if (encrypted && passphrase == null) throw KeyPassphraseException("Passphrase required")
        val format = try {
            KeyProviderUtil.detectKeyFileFormat(text, false)
        } catch (e: IOException) {
            throw SshException("Empty private key", e)
        }
        return when (format) {
            KeyFormat.OpenSSHv1 -> loadWithSshj(OpenSSHKeyV1KeyFile(), text, passphrase, encrypted)
            KeyFormat.PuTTY -> loadWithSshj(PuTTYKeyFile(), text, passphrase, encrypted)
            KeyFormat.PKCS8 -> loadPem(text, passphrase)
            else -> throw SshException("Unsupported private key format")
        }
    }

    private fun loadWithSshj(provider: FileKeyProvider, text: String, passphrase: String?, encrypted: Boolean): KeyPair {
        provider.init(text, null, passphrase?.let { FixedPassword(it) })
        try {
            val publicKey = provider.getPublic()
            val privateKey = provider.getPrivate()
            if (publicKey == null || privateKey == null) throw SshException("Invalid private key")
            return KeyPair(publicKey, privateKey)
        } catch (e: KeyDecryptionFailedException) {
            throw passphraseError(passphrase, e)
        } catch (e: IOException) {
            if (encrypted) throw passphraseError(passphrase, e)
            throw SshException("Invalid private key: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: SSHRuntimeException) {
            if (encrypted) throw passphraseError(passphrase, e)
            throw SshException("Invalid private key: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: SshException) {
            throw e
        } catch (e: RuntimeException) {
            // e.g. unsupported cipher / KDF in an encrypted OpenSSH key.
            throw SshException("Unsupported private key: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Traditional ("RSA/EC/DSA PRIVATE KEY", optionally Proc-Type encrypted) and PKCS#8
     * ("PRIVATE KEY", "ENCRYPTED PRIVATE KEY") PEM keys. Traditional pairs are converted by
     * BouncyCastle directly, which also handles SEC1 EC keys with explicit curve parameters
     * (written by LibreSSL builds of `ssh-keygen -m PEM`), unsupported by sshj.
     */
    private fun loadPem(text: String, passphrase: String?): KeyPair {
        val parsed = try {
            PEMParser(StringReader(text)).use { it.readObject() }
        } catch (e: Exception) {
            throw SshException("Invalid private key: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return when (parsed) {
            is PEMKeyPair -> convert(parsed)
            is PEMEncryptedKeyPair -> {
                val decrypted = try {
                    parsed.decryptKeyPair(
                        JcePEMDecryptorProviderBuilder()
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .build(requireNotNull(passphrase).toCharArray()),
                    )
                } catch (e: Exception) {
                    throw passphraseError(passphrase, e)
                }
                convert(decrypted)
            }
            is PrivateKeyInfo -> pkcs8(parsed)
            is PKCS8EncryptedPrivateKeyInfo -> {
                val info = try {
                    parsed.decryptPrivateKeyInfo(
                        JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .build(requireNotNull(passphrase).toCharArray()),
                    )
                } catch (e: Exception) {
                    throw passphraseError(passphrase, e)
                }
                pkcs8(info)
            }
            else -> throw SshException("Unsupported private key format")
        }
    }

    private fun convert(pair: PEMKeyPair): KeyPair {
        // A "PRIVATE KEY" with legacy Proc-Type encryption decrypts to a private key only.
        if (pair.publicKeyInfo == null) return pkcs8(pair.privateKeyInfo)
        return try {
            JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getKeyPair(pair)
        } catch (e: Exception) {
            throw SshException("Invalid private key: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Validates a PKCS#8 structure with BouncyCastle, then lets sshj derive the key pair. */
    private fun pkcs8(info: PrivateKeyInfo): KeyPair {
        val der = try {
            info.parsePrivateKey() // parses (and bounds-checks) the inner key structure
            info.encoded
        } catch (e: Exception) {
            throw SshException("Invalid private key: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return loadWithSshj(PKCS8KeyFile(), Pem.armor("PRIVATE KEY", der), null, encrypted = false)
    }

    fun inspect(text: String, passphrase: String?): KeyInfo {
        val pair = load(text, passphrase)
        val blob = publicKeyBlob(pair.public)
        val keyType = KeyType.fromKey(pair.public).toString()
        val comment = comment(text)
        val line = buildString {
            append(keyType).append(' ').append(Base64.getEncoder().encodeToString(blob))
            if (!comment.isNullOrBlank()) append(' ').append(comment.trim())
        }
        return KeyInfo(keyType, line, Fingerprints.sha256(blob))
    }

    fun publicKeyBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    private fun passphraseError(passphrase: String?, cause: Throwable): KeyPassphraseException {
        val message = if (passphrase == null) "Passphrase required" else "Incorrect passphrase"
        return KeyPassphraseException(message, cause)
    }

    private fun openSshCipher(text: String): String? {
        val body = Pem.decode(text, OPENSSH_LABEL) ?: return null
        return try {
            val reader = SshReader(body)
            val magic = reader.raw(AUTH_MAGIC.length)
            if (!magic.contentEquals(AUTH_MAGIC.toByteArray(Charsets.US_ASCII))) return null
            reader.string()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Comment stored in the key file (unencrypted OpenSSH v1 and PuTTY keys), if any. */
    fun comment(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("PuTTY-User-Key-File-")) {
            return trimmed.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Comment:") }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        val body = Pem.decode(trimmed, OPENSSH_LABEL) ?: return null
        return try {
            val r = SshReader(body)
            if (!r.raw(AUTH_MAGIC.length).contentEquals(AUTH_MAGIC.toByteArray(Charsets.US_ASCII))) return null
            if (r.string() != "none") return null // encrypted: comment is inside the ciphertext
            r.string() // kdfname
            r.bytes() // kdfoptions
            if (r.u32() != 1) return null
            r.bytes() // public key blob
            val p = SshReader(r.bytes())
            p.u32()
            p.u32()
            when (val type = p.string()) {
                "ssh-ed25519" -> repeat(2) { p.bytes() }
                "ssh-rsa" -> repeat(6) { p.bytes() }
                "ssh-dss" -> repeat(5) { p.bytes() }
                "sk-ssh-ed25519@openssh.com" -> {
                    p.bytes(); p.bytes(); p.byte(); p.bytes(); p.bytes()
                }
                "sk-ecdsa-sha2-nistp256@openssh.com" -> {
                    p.bytes(); p.bytes(); p.bytes(); p.byte(); p.bytes(); p.bytes()
                }
                else -> if (type.startsWith("ecdsa-sha2-")) repeat(3) { p.bytes() } else return null
            }
            p.string().takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Supplies a fixed passphrase; sshj blanks the returned array, so hand out a fresh copy. */
internal class FixedPassword(private val secret: String) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = secret.toCharArray()
    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}
