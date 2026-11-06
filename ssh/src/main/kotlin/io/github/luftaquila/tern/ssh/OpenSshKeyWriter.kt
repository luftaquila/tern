// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.schmizz.sshj.common.KeyType
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes private keys in the OpenSSH "openssh-key-v1" format (OpenSSH PROTOCOL.key), unencrypted
 * or encrypted the way `ssh-keygen` does it: bcrypt KDF with 16 rounds and aes256-ctr.
 */
internal object OpenSshKeyWriter {

    private const val AUTH_MAGIC = "openssh-key-v1\u0000"
    private const val CIPHER = "aes256-ctr"
    private const val KDF = "bcrypt"
    private const val KDF_ROUNDS = 16
    private const val SALT_LENGTH = 16
    private const val KEY_LENGTH = 32
    private const val IV_LENGTH = 16

    /** [pair] as a key file; encrypted with [passphrase] unless it is null or empty. */
    fun write(pair: KeyPair, comment: String, passphrase: String?, random: SecureRandom = SecureRandom()): String {
        val publicBlob = KeyFiles.publicKeyBlob(pair.public)
        return encode(publicBlob, privateFields(pair, publicBlob), comment, passphrase, random)
    }

    /**
     * The file for a key given as its public key blob and its private fields (beginning with the
     * key type string, in the order OpenSSH stores them).
     */
    fun encode(publicBlob: ByteArray, privateFields: ByteArray, comment: String, passphrase: String?, random: SecureRandom): String {
        val encrypt = !passphrase.isNullOrEmpty()
        val check = random.nextInt()
        val section = SshWriter()
            .u32(check)
            .u32(check)
            .raw(privateFields)
            .string(comment)
        // Padded with 1, 2, 3, ... to the cipher block size (8 without encryption).
        val blockSize = if (encrypt) 16 else 8
        var pad = 1
        while (section.size() % blockSize != 0) section.byte(pad++)

        val body = SshWriter().raw(AUTH_MAGIC.toByteArray(Charsets.US_ASCII))
        if (encrypt) {
            val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
            val keyIv = ByteArray(KEY_LENGTH + IV_LENGTH)
            BCrypt().pbkdf(passphrase!!.toByteArray(Charsets.UTF_8), salt, KDF_ROUNDS, keyIv)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyIv, 0, KEY_LENGTH, "AES"), IvParameterSpec(keyIv, KEY_LENGTH, IV_LENGTH))
            body.string(CIPHER)
                .string(KDF)
                .bytes(SshWriter().bytes(salt).u32(KDF_ROUNDS).toByteArray())
                .u32(1) // number of keys
                .bytes(publicBlob)
                .bytes(cipher.doFinal(section.toByteArray()))
        } else {
            body.string("none")
                .string("none")
                .bytes(ByteArray(0))
                .u32(1)
                .bytes(publicBlob)
                .bytes(section.toByteArray())
        }
        return Pem.armor("OPENSSH PRIVATE KEY", body.toByteArray())
    }

    /**
     * Private fields of a loaded key. For ECDSA and Ed25519 they start with the public key blob's
     * fields; RSA stores n, e, d, iqmp (q^-1 mod p, the CRT coefficient), p, q.
     */
    private fun privateFields(pair: KeyPair, publicBlob: ByteArray): ByteArray =
        when (val type = KeyType.fromKey(pair.public)) {
            KeyType.RSA -> {
                val key = pair.private as? RSAPrivateCrtKey ?: throw SshException("This RSA key cannot be exported")
                SshWriter().string("ssh-rsa")
                    .mpint(key.modulus)
                    .mpint(key.publicExponent)
                    .mpint(key.privateExponent)
                    .mpint(key.crtCoefficient)
                    .mpint(key.primeP)
                    .mpint(key.primeQ)
                    .toByteArray()
            }
            KeyType.ECDSA256, KeyType.ECDSA384, KeyType.ECDSA521 ->
                SshWriter().raw(publicBlob).mpint((pair.private as ECPrivateKey).s).toByteArray()
            KeyType.ED25519 -> {
                val reader = SshReader(publicBlob)
                reader.string() // "ssh-ed25519"
                val publicKey = reader.bytes()
                // The private key's PKCS#8 encoding holds the 32-byte seed; OpenSSH stores seed || public key.
                val seed = ASN1OctetString.getInstance(PrivateKeyInfo.getInstance(pair.private.encoded).parsePrivateKey()).octets
                SshWriter().raw(publicBlob).bytes(seed + publicKey).toByteArray()
            }
            else -> throw SshException("Exporting $type keys is not supported")
        }
}
