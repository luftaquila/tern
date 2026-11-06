// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.digest.Digest
import net.schmizz.sshj.transport.digest.SHA256
import net.schmizz.sshj.transport.digest.SHA512
import net.schmizz.sshj.transport.kex.KeyExchange
import net.schmizz.sshj.transport.kex.KeyExchangeBase
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.pqc.crypto.ntruprime.SNTRUPrimeKEMExtractor
import org.bouncycastle.pqc.crypto.ntruprime.SNTRUPrimeKeyGenerationParameters
import org.bouncycastle.pqc.crypto.ntruprime.SNTRUPrimeKeyPairGenerator
import org.bouncycastle.pqc.crypto.ntruprime.SNTRUPrimeParameters
import org.bouncycastle.pqc.crypto.ntruprime.SNTRUPrimePrivateKeyParameters
import org.bouncycastle.pqc.crypto.ntruprime.SNTRUPrimePublicKeyParameters
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Post-quantum hybrid key exchanges as OpenSSH implements them: a key encapsulation mechanism
 * (ML-KEM-768 or sntrup761) combined with X25519, so the session stays secure as long as either
 * of the two is unbroken.
 *
 * - `mlkem768x25519-sha256` (OpenSSH 9.9+, default since 10.0)
 * - `sntrup761x25519-sha512` and its older name `sntrup761x25519-sha512@openssh.com` (8.5+)
 */
object PostQuantumKex {

    /** Key exchange factories, most preferred first. */
    fun factories(): List<Factory.Named<KeyExchange>> = listOf(
        Named("mlkem768x25519-sha256") { HybridKemKex(::MlKem768, ::SHA256) },
        Named("sntrup761x25519-sha512") { HybridKemKex(::Sntrup761, ::SHA512) },
        Named("sntrup761x25519-sha512@openssh.com") { HybridKemKex(::Sntrup761, ::SHA512) },
    )

    private class Named(private val name: String, private val create: () -> KeyExchange) : Factory.Named<KeyExchange> {
        override fun getName(): String = name

        override fun create(): KeyExchange = create.invoke()
    }
}

/** The client side of a KEM: a fresh key pair whose public half goes to the server. */
internal interface Kem {
    val publicKey: ByteArray
    val ciphertextLength: Int

    fun decapsulate(ciphertext: ByteArray): ByteArray
}

internal class MlKem768 : Kem {
    private val pair = MLKEMKeyPairGenerator()
        .apply { init(MLKEMKeyGenerationParameters(SecureRandom(), MLKEMParameters.ml_kem_768)) }
        .generateKeyPair()
    override val publicKey: ByteArray = (pair.public as MLKEMPublicKeyParameters).encoded
    override val ciphertextLength: Int = MLKEMParameters.ml_kem_768.encapsulationLength

    override fun decapsulate(ciphertext: ByteArray): ByteArray =
        MLKEMExtractor(pair.private as MLKEMPrivateKeyParameters).extractSecret(ciphertext)
}

internal class Sntrup761 : Kem {
    private val pair = SNTRUPrimeKeyPairGenerator()
        .apply { init(SNTRUPrimeKeyGenerationParameters(SecureRandom(), SNTRUPrimeParameters.sntrup761)) }
        .generateKeyPair()
    override val publicKey: ByteArray = (pair.public as SNTRUPrimePublicKeyParameters).encoded
    override val ciphertextLength: Int = SNTRUPrimeParameters.sntrup761.encapsulationLength

    override fun decapsulate(ciphertext: ByteArray): ByteArray =
        SNTRUPrimeKEMExtractor(pair.private as SNTRUPrimePrivateKeyParameters).extractSecret(ciphertext)
}

/**
 * Hybrid KEM + X25519 key exchange (draft-ietf-sshm-mlkem-hybrid-kex, OpenSSH kexmlkem768x25519.c
 * and kexsntrup761x25519.c):
 *
 * - client → SSH_MSG_KEX_ECDH_INIT (30): string C_INIT = KEM public key || X25519 public key
 * - server → SSH_MSG_KEX_ECDH_REPLY (31): string K_S, string S_REPLY = KEM ciphertext ||
 *   X25519 public key, string signature over H
 * - K = HASH(KEM shared secret || X25519 shared secret), an SSH *string* (not mpint)
 * - H = HASH(V_C || V_S || I_C || I_S || K_S || C_INIT || S_REPLY || K)
 *
 * Host key and signature checks mirror sshj's AbstractDHG.
 */
internal class HybridKemKex(
    private val newKem: () -> Kem,
    private val newDigest: () -> Digest,
) : KeyExchangeBase(newDigest()) {

    private lateinit var kem: Kem
    private val x25519Private = ByteArray(X25519.SCALAR_SIZE)
    private lateinit var clientInit: ByteArray
    private var sharedSecret: ByteArray? = null

    override fun init(trans: Transport, V_S: String, V_C: String, I_S: ByteArray, I_C: ByteArray) {
        super.init(trans, V_S, V_C, I_S, I_C)
        digest.init()
        kem = newKem()
        X25519.generatePrivateKey(SecureRandom(), x25519Private)
        val x25519Public = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(x25519Private, 0, x25519Public, 0)
        clientInit = kem.publicKey + x25519Public
        trans.write(SSHPacket(Message.KEXDH_INIT).putBytes(clientInit))
    }

    override fun next(msg: Message, packet: SSHPacket): Boolean {
        if (msg != Message.KEXDH_31) {
            throw TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "Unexpected packet: $msg")
        }
        val hostKeyBlob = packet.readBytes()
        val serverReply = packet.readBytes()
        val signatureBlob = packet.readBytes()
        val key = try {
            Buffer.PlainBuffer(hostKeyBlob).readPublicKey()
        } catch (e: Buffer.BufferException) {
            throw TransportException(e)
        }
        hostKey = key

        if (serverReply.size != kem.ciphertextLength + X25519.POINT_SIZE) {
            throw TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "Bad hybrid key exchange reply (${serverReply.size} bytes)")
        }
        val kemSecret = kem.decapsulate(serverReply.copyOfRange(0, kem.ciphertextLength))
        val ecdhSecret = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(x25519Private, 0, serverReply, kem.ciphertextLength, ecdhSecret, 0)) {
            throw TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "X25519 key agreement failed")
        }
        val k = newDigest().run {
            init()
            update(kemSecret)
            update(ecdhSecret)
            digest()
        }
        sharedSecret = k

        val buffer = initializedBuffer()
            .putBytes(hostKeyBlob)
            .putBytes(clientInit)
            .putBytes(serverReply)
            .putBytes(k)
        digest.update(buffer.array(), buffer.rpos(), buffer.available())
        H = digest.digest()

        val signature = trans.hostKeyAlgorithm.newSignature()
        signature.initVerify(if (key is Certificate<*>) key.key else key)
        signature.update(H, 0, H.size)
        if (!signature.verify(signatureBlob)) {
            throw TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "KeyExchange signature verification failed")
        }
        if (key is Certificate<*> && trans.config.isVerifyHostKeyCertificates) {
            val error = try {
                KeyType.CertUtils.verifyHostCertificate(hostKeyBlob, key, trans.remoteHost)
            } catch (e: Buffer.BufferException) {
                throw TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "KeyExchange certificate check failed", e)
            }
            if (error != null) {
                throw TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "KeyExchange certificate check failed: $error")
            }
        }
        return true
    }

    override fun getK(): BigInteger = RawSecret(checkNotNull(sharedSecret) { "Key exchange not finished" })
}

/**
 * The shared secret for sshj's key derivation, which writes K with `putMPInt(K)`, i.e. a
 * uint32 length followed by `K.toByteArray()`. Returning the raw bytes there yields the SSH
 * string encoding that hybrid key exchanges use for K.
 */
internal class RawSecret(private val raw: ByteArray) : BigInteger(1, raw) {
    override fun toByteArray(): ByteArray = raw.copyOf()

    // kotlin.Number declares these abstract; java.lang.Number derives them from intValue().
    override fun toByte(): Byte = toInt().toByte()

    override fun toShort(): Short = toInt().toShort()
}
