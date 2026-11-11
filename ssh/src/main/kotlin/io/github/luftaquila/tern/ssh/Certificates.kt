// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * A certificate authority whose host certificates are trusted (OpenSSH `@cert-authority`) for the
 * hosts matching [hostPatterns]: comma-separated, `*` and `?` wildcards, `!` excluding, each
 * matched against the host name and its `[host]:port` form, ignoring case.
 */
data class HostCertificateAuthority(
    /** The CA's public key as an authorized_keys line: "<type> <base64> [comment]". */
    val publicKey: String,
    val hostPatterns: String,
)

/** An OpenSSH certificate as [SshCertificates.inspect] reads it. */
data class CertificateInfo(
    /** e.g. "ssh-ed25519-cert-v01@openssh.com". */
    val certificateType: String,
    val isHostCertificate: Boolean,
    /** The key ID the CA gave it. */
    val id: String,
    /** User names (host names for host certificates) it is valid for; empty = any. */
    val principals: List<String>,
    /** Epoch milliseconds; null = no limit. */
    val validAfterMillis: Long?,
    val validBeforeMillis: Long?,
    /** The certified key's fingerprint, comparable with [KeyInfo.fingerprintSha256]. */
    val keyFingerprintSha256: String,
    val caFingerprintSha256: String,
) {
    fun isValidAt(millis: Long): Boolean =
        (validAfterMillis == null || millis >= validAfterMillis) && (validBeforeMillis == null || millis < validBeforeMillis)
}

object SshCertificates {
    /** Reads an OpenSSH certificate ("<type>-cert-v01@openssh.com <base64> [comment]"); [SshException] when it is not one. */
    fun inspect(text: String): CertificateInfo {
        SshSecurity.install()
        val certificate = Certified.parse(text)
        return CertificateInfo(
            certificateType = KeyType.fromKey(certificate).toString(),
            isHostCertificate = certificate.type == Certified.HOST_CERTIFICATE,
            id = certificate.id.orEmpty(),
            principals = certificate.validPrincipals.orEmpty(),
            validAfterMillis = certificate.validAfter?.time?.takeIf { it > 0 },
            validBeforeMillis = certificate.validBefore?.time?.takeIf { it < Certified.FOREVER_MILLIS },
            keyFingerprintSha256 = Fingerprints.sha256(KeyFiles.publicKeyBlob(certificate.key)),
            caFingerprintSha256 = Fingerprints.sha256(certificate.signatureKey),
        )
    }

    /** Fingerprint of a plain public key line ("<type> <base64> [comment]"); [SshException] for anything else. */
    fun publicKeyFingerprint(line: String): String {
        SshSecurity.install()
        val blob = Certified.blob(line)
        val key = try {
            Buffer.PlainBuffer(blob).readPublicKey()
        } catch (e: Exception) {
            throw SshException("Not a public key", e)
        }
        if (key is Certificate<*> || KeyType.fromKey(key) == KeyType.UNKNOWN) throw SshException("Not a public key")
        return Fingerprints.sha256(blob)
    }
}

internal object Certified {
    /** SSH2_CERT_TYPE_HOST; user certificates are type 1. */
    const val HOST_CERTIFICATE = 2L

    /** sshj's date for "valid forever" (the largest uint64) and anything beyond it. */
    const val FOREVER_MILLIS = 9_223_372_036_854_775L * 1000

    /** Certificate key types, preferred as host keys for hosts a trusted CA covers. */
    val KEY_TYPES: List<KeyType> = listOf(
        KeyType.ED25519_CERT, KeyType.ECDSA256_CERT, KeyType.ECDSA384_CERT, KeyType.ECDSA521_CERT, KeyType.RSA_CERT,
    )

    /** The wire blob of a key line's second field. */
    fun blob(line: String): ByteArray {
        val field = line.trim().split(Regex("\\s+")).getOrNull(1) ?: throw SshException("Not a key line")
        return try {
            Base64.getDecoder().decode(field)
        } catch (e: IllegalArgumentException) {
            throw SshException("Not a key line", e)
        }
    }

    fun parse(text: String): Certificate<*> {
        val type = text.trim().substringBefore(' ')
        val key = try {
            Buffer.PlainBuffer(blob(text)).readPublicKey()
        } catch (e: SshException) {
            throw e
        } catch (e: Exception) {
            throw SshException("Not an OpenSSH certificate", e)
        }
        if (key !is Certificate<*> || KeyType.fromKey(key).toString() != type) throw SshException("Not an OpenSSH certificate")
        return key
    }

    /** [text]'s certificate when it certifies [pair]'s key, else null. */
    fun forKey(text: String, pair: KeyPair): Certificate<*>? {
        val certificate = try {
            parse(text)
        } catch (_: SshException) {
            return null
        }
        return certificate.takeIf { KeyFiles.publicKeyBlob(it.key).contentEquals(KeyFiles.publicKeyBlob(pair.public)) }
    }
}

/** A key offered with its OpenSSH certificate; the plain key signs. */
internal class CertifiedKey(private val pair: KeyPair, private val certificate: Certificate<*>) : KeyProvider {
    override fun getPrivate(): PrivateKey = pair.private

    override fun getPublic(): PublicKey = certificate

    override fun getType(): KeyType = KeyType.fromKey(certificate)
}

internal object HostPatterns {
    /** Whether [patterns] (see [HostCertificateAuthority.hostPatterns]) cover [host] on [port]. */
    fun matches(patterns: String, host: String, port: Int): Boolean {
        val names = listOf(host, "[$host]:$port")
        var matched = false
        for (raw in patterns.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val excluded = raw.startsWith('!')
            val pattern = raw.removePrefix("!").trim()
            if (names.none { glob(pattern, it) }) continue
            if (excluded) return false
            matched = true
        }
        return matched
    }

    private fun glob(pattern: String, name: String): Boolean {
        val regex = buildString {
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(name)
    }
}
