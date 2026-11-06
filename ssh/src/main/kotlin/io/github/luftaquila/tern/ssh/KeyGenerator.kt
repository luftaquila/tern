// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

/**
 * Generates user keys and serialises them in the OpenSSH "openssh-key-v1" format
 * (OpenSSH PROTOCOL.key), which both OpenSSH and sshj read for every supported type.
 *
 * Ed25519 uses BouncyCastle's lightweight API (no JCA provider needed, works on every
 * Android version); ECDSA and RSA use the platform's default JCA provider (Conscrypt on
 * Android), which is much faster than pure-Java RSA key generation.
 */
internal object KeyGenerator {

    private class Material(
        val keyType: String,
        /** Public key wire blob. */
        val publicBlob: ByteArray,
        /** Type-specific private fields, beginning with the key type string. */
        val privateFields: ByteArray,
    )

    fun generate(type: SshKeyType, comment: String): GeneratedKey {
        val random = SecureRandom()
        val material = when (type) {
            SshKeyType.ED25519 -> ed25519(random)
            SshKeyType.ECDSA_P256 -> ecdsaP256(random)
            SshKeyType.RSA_3072 -> rsa(3072, random)
            SshKeyType.RSA_4096 -> rsa(4096, random)
        }
        val cleanComment = cleanComment(comment)
        val privateKey = OpenSshKeyWriter.encode(material.publicBlob, material.privateFields, cleanComment, passphrase = null, random)
        val publicKey = buildString {
            append(material.keyType).append(' ')
            append(Base64.getEncoder().encodeToString(material.publicBlob))
            if (cleanComment.isNotEmpty()) append(' ').append(cleanComment)
        }
        return GeneratedKey(privateKey, publicKey, Fingerprints.sha256(material.publicBlob))
    }

    private fun ed25519(random: SecureRandom): Material {
        val privateKey = Ed25519PrivateKeyParameters(random)
        val pk = privateKey.generatePublicKey().encoded // 32 bytes
        val seed = privateKey.encoded // 32 bytes
        val blob = SshWriter().string("ssh-ed25519").bytes(pk).toByteArray()
        // OpenSSH stores the 64-byte "secret key" = seed || public key.
        val fields = SshWriter().string("ssh-ed25519").bytes(pk).bytes(seed + pk).toByteArray()
        return Material("ssh-ed25519", blob, fields)
    }

    private fun ecdsaP256(random: SecureRandom): Material {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val pub = pair.public as ECPublicKey
        val priv = pair.private as ECPrivateKey
        // Uncompressed SEC1 point: 0x04 || X || Y, each coordinate 32 bytes.
        val q = byteArrayOf(0x04) + unsigned(pub.w.affineX, 32) + unsigned(pub.w.affineY, 32)
        val keyType = "ecdsa-sha2-nistp256"
        val blob = SshWriter().string(keyType).string("nistp256").bytes(q).toByteArray()
        val fields = SshWriter().string(keyType).string("nistp256").bytes(q).mpint(priv.s).toByteArray()
        return Material(keyType, blob, fields)
    }

    private fun rsa(bits: Int, random: SecureRandom): Material {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4), random)
        val key = generator.generateKeyPair().private as RSAPrivateCrtKey
        val blob = SshWriter().string("ssh-rsa").mpint(key.publicExponent).mpint(key.modulus).toByteArray()
        // OpenSSH order: n, e, d, iqmp (= q^-1 mod p, the PKCS#1 / JCA CRT coefficient), p, q.
        val fields = SshWriter().string("ssh-rsa")
            .mpint(key.modulus)
            .mpint(key.publicExponent)
            .mpint(key.privateExponent)
            .mpint(key.crtCoefficient)
            .mpint(key.primeP)
            .mpint(key.primeQ)
            .toByteArray()
        return Material("ssh-rsa", blob, fields)
    }

    /** A key comment on one line. */
    fun cleanComment(comment: String): String = comment.trim().replace('\n', ' ').replace('\r', ' ')

    private fun unsigned(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size == length + 1 && bytes[0].toInt() == 0 -> bytes.copyOfRange(1, bytes.size)
            bytes.size < length -> ByteArray(length - bytes.size) + bytes
            else -> throw IllegalArgumentException("coordinate too large")
        }
    }
}
