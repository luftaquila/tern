// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.signature.SignatureEdDSA
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.signature.SignatureRSA
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * A key offered through agent forwarding: its authorized_keys line, the name programs see, its
 * OpenSSH certificate (offered too, before the key), and its private key, read only when the server
 * first asks for a signature with it.
 */
class AgentKey(
    val publicKey: String,
    val name: String,
    val certificate: String? = null,
    val privateKey: () -> SshCredential.PrivateKey,
)

/**
 * Agent forwarding (`ssh -A`): programs on the server (ssh, git) ask this app to sign with
 * [keys], and the private keys never leave the device. Keys are listed by their public key; a
 * private key is loaded the first time the server asks for a signature with it, asking
 * [interaction] for the passphrase when it is encrypted and none is stored.
 */
class SshAgent(keys: List<AgentKey>, private val interaction: SshInteraction) {

    private class Identity(val blob: ByteArray, val key: AgentKey)

    private val identities: List<Identity> = keys.flatMap { key ->
        listOfNotNull(key.certificate, key.publicKey).mapNotNull { line ->
            val blob = line.trim().split(Regex("\\s+")).getOrNull(1)?.let {
                try {
                    Base64.getDecoder().decode(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            blob?.let { Identity(it, key) }
        }
    }

    private val loaded = ConcurrentHashMap<AgentKey, KeyPair>()
    private val loading = Any()

    /** Answers agent requests on one channel until the server closes it. */
    internal fun serve(input: InputStream, output: OutputStream) {
        val data = DataInputStream(input)
        while (true) {
            val length = try {
                data.readInt()
            } catch (_: EOFException) {
                return
            }
            if (length !in 1..MAX_MESSAGE) return
            val message = ByteArray(length).also(data::readFully)
            val reply = handle(message)
            output.write(SshWriter().bytes(reply).toByteArray())
            output.flush()
        }
    }

    /** One request (without its length prefix) to one reply. */
    internal fun handle(message: ByteArray): ByteArray = try {
        val request = SshReader(message)
        when (request.byte()) {
            REQUEST_IDENTITIES -> SshWriter().byte(IDENTITIES_ANSWER).u32(identities.size).apply {
                identities.forEach { bytes(it.blob).string(it.key.name) }
            }.toByteArray()
            SIGN_REQUEST -> sign(blob = request.bytes(), data = request.bytes(), flags = request.u32()) ?: FAILURE
            else -> FAILURE // also extensions such as session-bind@openssh.com, which clients treat as optional
        }
    } catch (_: IllegalArgumentException) {
        FAILURE
    }

    private fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray? {
        val identity = identities.firstOrNull { it.blob.contentEquals(blob) } ?: return null
        val pair = keyPair(identity) ?: return null
        val type = KeyType.fromKey(pair.public)
        val algorithm = when {
            type != KeyType.RSA -> type.toString()
            flags and RSA_SHA2_512 != 0 -> "rsa-sha2-512"
            flags and RSA_SHA2_256 != 0 -> "rsa-sha2-256"
            else -> "ssh-rsa"
        }
        val signature = SIGNATURES.firstOrNull { it.name == algorithm }?.create() ?: return null
        val encoded = try {
            signature.initSign(pair.private)
            signature.update(data)
            signature.encode(signature.sign())
        } catch (_: Exception) {
            return null
        }
        val signatureBlob = if (signature.isSignaturePreEncoded) {
            encoded
        } else {
            SshWriter().string(signature.signatureName).bytes(encoded).toByteArray()
        }
        return SshWriter().byte(SIGN_RESPONSE).bytes(signatureBlob).toByteArray()
    }

    /**
     * One passphrase question at a time; a key the user skipped is asked for again next time. A key
     * and its certificate share the loaded key.
     */
    private fun keyPair(identity: Identity): KeyPair? {
        loaded[identity.key]?.let { return it }
        synchronized(loading) {
            loaded[identity.key]?.let { return it }
            val pair = try {
                runBlocking { loadPrivateKey(identity.key.privateKey(), interaction) }
            } catch (_: Exception) {
                null // unreadable key, or the app could not decrypt it
            } ?: return null
            loaded[identity.key] = pair
            return pair
        }
    }

    internal companion object {
        const val CHANNEL_TYPE = "auth-agent@openssh.com"
        const val REQUEST = "auth-agent-req@openssh.com"

        private const val FAILURE_CODE = 5
        private const val REQUEST_IDENTITIES = 11
        private const val IDENTITIES_ANSWER = 12
        private const val SIGN_REQUEST = 13
        private const val SIGN_RESPONSE = 14
        private const val RSA_SHA2_256 = 2
        private const val RSA_SHA2_512 = 4

        /** OpenSSH's agent refuses longer messages too. */
        private const val MAX_MESSAGE = 256 * 1024

        private val FAILURE = byteArrayOf(FAILURE_CODE.toByte())

        private val SIGNATURES: List<Factory.Named<Signature>> = listOf(
            SignatureRSA.FactoryRSASHA512(),
            SignatureRSA.FactoryRSASHA256(),
            SignatureRSA.FactorySSHRSA(),
            SignatureECDSA.Factory256(),
            SignatureECDSA.Factory384(),
            SignatureECDSA.Factory521(),
            SignatureEdDSA.Factory(),
        )
    }
}

/** Opens a [SshAgent] session for each agent channel the server opens. */
internal class AgentChannelOpener(conn: Connection, @Volatile var agent: SshAgent) :
    AbstractForwardedChannelOpener(SshAgent.CHANNEL_TYPE, conn) {

    override fun handleOpen(buf: SSHPacket) {
        val channel = try {
            AgentChannel(conn, buf.readUInt32AsInt(), buf.readUInt32(), buf.readUInt32())
        } catch (e: Buffer.BufferException) {
            throw ConnectionException(e)
        }
        val agent = agent
        // Runs on a thread of its own (callListener), so the agent may wait for a passphrase.
        callListener(
            ConnectListener { forwarded ->
                forwarded.confirm()
                try {
                    agent.serve(forwarded.inputStream, forwarded.outputStream)
                } catch (_: IOException) {
                    // The server closed the channel.
                } finally {
                    runCatching { forwarded.close() }
                }
            },
            channel,
        )
    }
}

private class AgentChannel(conn: Connection, recipient: Int, windowSize: Long, maxPacketSize: Long) :
    AbstractForwardedChannel(conn, SshAgent.CHANNEL_TYPE, recipient, windowSize, maxPacketSize, "", 0)
