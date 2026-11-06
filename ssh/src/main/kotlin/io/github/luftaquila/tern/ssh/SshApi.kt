// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.DisconnectListener
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// =========================================================================================
// Public API of the :ssh module (consumed by :app). Implementations live in this module.
// Everything blocking must be called off the main thread.
// =========================================================================================

/**
 * Replaces Android's stripped-down platform "BC" provider with the full BouncyCastle
 * provider so sshj gets X25519/Ed25519/ChaCha20 etc. Idempotent. Call once at process start
 * before any other API of this module.
 */
object SshSecurity {
    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            ensureFullBouncyCastle()
            // Pin sshj to the provider named "BC" (now the full BouncyCastle). With a provider
            // name set, sshj's SecurityUtils skips its own registration logic, which would
            // otherwise accept Android's stripped platform provider that also calls itself "BC".
            SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
            installed = true
        }
    }

    /** Makes Security.getProvider("BC") the full BouncyCastle provider. Returns true if it changed. */
    internal fun ensureFullBouncyCastle(): Boolean {
        val name = BouncyCastleProvider.PROVIDER_NAME
        val current = Security.getProvider(name)
        if (current != null && current.javaClass == BouncyCastleProvider::class.java) return false
        if (current != null) Security.removeProvider(name)
        // Appended (lowest priority): only code that asks for "BC" by name uses it, the
        // platform's default providers (Conscrypt / AndroidKeyStore) stay first.
        Security.addProvider(BouncyCastleProvider())
        return true
    }
}

data class SshTarget(val host: String, val port: Int = 22, val username: String)

sealed interface SshCredential {
    /** Password auth (also answers a lone keyboard-interactive password prompt). */
    data class Password(val password: String) : SshCredential

    /** Public key auth. [privateKey] is key file text: OpenSSH, PEM (PKCS#1/#8/SEC1) or PuTTY. */
    data class PrivateKey(
        val name: String,
        val privateKey: String,
        val passphrase: String? = null,
    ) : SshCredential
}

data class HostKeyInfo(
    val host: String,
    val port: Int,
    /** e.g. "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa". */
    val keyType: String,
    /** SSH wire-format public key blob, base64 (the 2nd field of a known_hosts line). */
    val publicKeyBase64: String,
    /** OpenSSH style "SHA256:<base64 without padding>". */
    val fingerprintSha256: String,
)

data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val addedAtMillis: Long,
)

interface KnownHostsStore {
    fun find(host: String, port: Int): List<KnownHost>

    /** Adds the entry, replacing an existing one with the same host, port and keyType. */
    fun save(entry: KnownHost)
}

enum class HostKeyStatus { UNKNOWN, CHANGED }

data class KeyboardInteractivePrompt(val text: String, val echo: Boolean)

/**
 * UI callbacks used while connecting. Invoked on a background thread; implementations
 * suspend until the user answers.
 */
interface SshInteraction {
    /** Unknown or changed host key. Return true to trust it (the library saves it to the store). */
    suspend fun confirmHostKey(info: HostKeyInfo, status: HostKeyStatus, previous: List<KnownHost>): Boolean

    /** Password prompt; [attempt] starts at 1. Return null to cancel the connection. */
    suspend fun requestPassword(target: SshTarget, attempt: Int): String?

    /** Private key passphrase prompt; [attempt] starts at 1. Return null to skip this key. */
    suspend fun requestPassphrase(keyName: String, attempt: Int): String?

    /** One keyboard-interactive round. Return one answer per prompt, or null to cancel. */
    suspend fun keyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractivePrompt>,
    ): List<String>?
}

data class ConnectOptions(
    val connectTimeoutMillis: Int = 15_000,
    /** 0 disables keep-alive. */
    val keepAliveIntervalSeconds: Int = 30,
    val maxPasswordAttempts: Int = 3,
    /** Offer the post-quantum hybrid key exchanges (ML-KEM-768 / sntrup761 + X25519) first. */
    val postQuantumKex: Boolean = true,
)

open class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The user (or policy) rejected the server host key. */
class SshHostKeyRejectedException(message: String) : SshException(message)

/** All authentication methods failed. */
class SshAuthenticationException(message: String) : SshException(message)

/** The user cancelled a prompt, or a transfer was cancelled. */
class SshCancelledException(message: String = "Cancelled") : SshException(message)

/** A private key is encrypted and the passphrase is missing or wrong. */
class KeyPassphraseException : SshException {
    constructor(message: String) : super(message)
    internal constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** An authenticated SSH connection. Multiple shells / SFTP sessions can share it. */
class SshConnection private constructor(
    private val client: SSHClient,
    target: SshTarget,
    hostKey: HostKeyInfo,
    /** Pre-authentication banner (SSH_MSG_USERAUTH_BANNER) the server sent, if any. */
    val banner: String?,
    /** The jump host this connection runs through; closed with it. */
    private val jump: SshConnection?,
) : Closeable {
    private val _target = target
    private val _hostKey = hostKey
    private val forwards = ConcurrentHashMap.newKeySet<PortForward>()
    private val closedByUser = AtomicBoolean(false)
    private val disconnectFired = AtomicBoolean(false)
    private val listenerLock = Any()
    private var disconnectListener: ((Throwable?) -> Unit)? = null
    private var disconnectCause: Throwable? = null
    private var disconnected = false

    val target: SshTarget get() = _target
    val isConnected: Boolean get() = !disconnectFired.get() && client.isConnected && client.isAuthenticated

    /** Host key presented by the server during this connection. */
    val hostKey: HostKeyInfo get() = _hostKey

    private fun install() {
        client.transport.disconnectListener = DisconnectListener { reason, message ->
            val cause = if (closedByUser.get() || reason == DisconnectReason.BY_APPLICATION) {
                null
            } else {
                SshException(
                    buildString {
                        append("Disconnected")
                        if (reason != null && reason != DisconnectReason.UNKNOWN) append(" (").append(reason).append(')')
                        if (!message.isNullOrBlank()) append(": ").append(message)
                    },
                )
            }
            onDisconnected(cause)
        }
        // The transport may have died before the listener was attached.
        if (!client.isConnected) onDisconnected(SshException("Disconnected"))
    }

    private fun onDisconnected(cause: Throwable?) {
        if (!disconnectFired.compareAndSet(false, true)) return
        forwards.forEach { it.close() }
        jump?.close()
        val listener = synchronized(listenerLock) {
            disconnected = true
            disconnectCause = cause
            disconnectListener
        }
        listener?.invoke(cause)
    }

    /**
     * Interactive shell with a PTY. Env vars the server refuses are silently ignored. With [agent],
     * programs in the shell can use its keys (`ssh -A`); servers that do not allow it ignore it.
     */
    fun openShell(
        termType: String,
        columns: Int,
        rows: Int,
        widthPixels: Int,
        heightPixels: Int,
        env: Map<String, String>,
        agent: SshAgent? = null,
    ): ShellChannel {
        val channel = PtySessionChannel(client.connection, client.remoteCharset)
        try {
            channel.open()
            channel.requestPty(termType, columns, rows, widthPixels, heightPixels, PtySessionChannel.DEFAULT_MODES)
            if (agent != null) {
                attachAgent(agent)
                channel.requestAgentForwarding()
            }
            for ((name, value) in env) {
                try {
                    channel.setEnvVar(name, value)
                } catch (e: ConnectionException) {
                    // Refused by the server (sshd AcceptEnv); not fatal.
                }
            }
            channel.startShell()
        } catch (e: Exception) {
            try {
                channel.close()
            } catch (_: Exception) {
            }
            throw wrapIo("Could not open shell", e)
        }
        return ShellChannel.create(channel)
    }

    /** One agent per connection answers the server's agent channels; the latest shell's agent wins. */
    private fun attachAgent(agent: SshAgent) = synchronized(this) {
        val connection = client.connection
        val opener = connection.get(SshAgent.CHANNEL_TYPE) as? AgentChannelOpener
        if (opener != null) opener.agent = agent else connection.attach(AgentChannelOpener(connection, agent))
    }

    /**
     * `ssh -L`: connections to [port] on this device's loopback interface reach [destHost]:[destPort]
     * as seen from the server. Stops when the connection closes.
     */
    fun forwardLocal(port: Int, destHost: String, destPort: Int): PortForward =
        track(Forwarding.local(client, LOOPBACK, port, destHost, destPort))

    /**
     * `ssh -R`: connections to [port] on the server's loopback interface reach [destHost]:[destPort]
     * as seen from this device. Port 0 lets the server choose ([PortForward.boundPort]).
     */
    fun forwardRemote(port: Int, destHost: String, destPort: Int): PortForward =
        track(Forwarding.remote(client, "localhost", port, destHost, destPort))

    /** `ssh -D`: a SOCKS 4/5 proxy on [port] of this device's loopback interface, connecting from the server. */
    fun forwardDynamic(port: Int): PortForward = track(Forwarding.dynamic(client, LOOPBACK, port))

    private fun track(forward: PortForward): PortForward {
        forwards += forward
        if (disconnectFired.get()) forward.close() // the connection ended meanwhile
        return forward
    }

    /** A direct-tcpip channel to [host]:[port] from the server, for connecting through this host. */
    internal fun openTunnel(host: String, port: Int): DirectConnection = client.newDirectConnection(host, port)

    /** SFTP subsystem on this connection. */
    fun openFileSystem(): RemoteFileSystem =
        try {
            RemoteFileSystem.create(client.newSFTPClient())
        } catch (e: Exception) {
            throw wrapIo("Could not start SFTP", e)
        }

    /** Called once when the transport dies or is closed (cause null on a clean close). */
    fun setOnDisconnect(listener: ((Throwable?) -> Unit)?) {
        val fireNow: Boolean
        val cause: Throwable?
        synchronized(listenerLock) {
            disconnectListener = listener
            fireNow = disconnected && listener != null
            cause = disconnectCause
        }
        if (fireNow) listener?.invoke(cause)
    }

    override fun close() {
        closedByUser.set(true)
        Connector.closeQuietly(client)
        onDisconnected(null)
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /**
         * Connects, verifies the host key against [knownHosts] (asking [interaction] for
         * unknown/changed keys) and authenticates: first each of [credentials] in order, then
         * interactive password / keyboard-interactive prompts if the server allows them.
         *
         * With [via] the connection runs through that host (`ssh -J`) and owns it: [via] is closed
         * when this connection closes, or right away when connecting fails.
         */
        suspend fun connect(
            target: SshTarget,
            credentials: List<SshCredential>,
            knownHosts: KnownHostsStore,
            interaction: SshInteraction,
            options: ConnectOptions = ConnectOptions(),
            via: SshConnection? = null,
        ): SshConnection = Connector.connect(target, credentials, knownHosts, interaction, options, via)

        internal fun create(client: SSHClient, target: SshTarget, hostKey: HostKeyInfo, banner: String?, jump: SshConnection?): SshConnection =
            SshConnection(client, target, hostKey, banner, jump).also { it.install() }
    }
}

class ShellChannel private constructor(private val channel: PtySessionChannel) : Closeable {
    private val flushingOutput = FlushingOutputStream(channel.outputStream)

    /** Remote output (PTY merges stderr). Returns -1 at EOF. */
    val input: InputStream get() = channel.inputStream
    val output: OutputStream get() = flushingOutput
    val isOpen: Boolean get() = channel.isOpen

    /** Exit status if the remote reported one, else null. */
    val exitStatus: Int? get() = channel.exitStatus

    fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
        try {
            channel.changeWindowDimensions(columns, rows, widthPixels, heightPixels)
        } catch (e: Exception) {
            throw wrapIo("Could not resize terminal", e)
        }
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
    }

    internal companion object {
        fun create(channel: PtySessionChannel) = ShellChannel(channel)
    }
}

/** sshj's channel stream buffers until flush(); a terminal must send every keystroke now. */
private class FlushingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
        delegate.flush()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        delegate.flush()
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

enum class RemoteFileType { FILE, DIRECTORY, SYMLINK, OTHER }

data class RemoteFile(
    val name: String,
    /** Absolute path. */
    val path: String,
    val type: RemoteFileType,
    val size: Long,
    val modifiedEpochSeconds: Long,
    /** POSIX permission bits (e.g. 0b111_101_101), or -1 if unknown. */
    val permissions: Int,
    /** Target of a symlink, if known. */
    val linkTarget: String?,
    /** True for directories and for symlinks pointing at directories. */
    val isDirectoryLike: Boolean,
)

fun interface TransferProgress {
    /** Return false to cancel; the transfer then throws [SshCancelledException]. */
    fun onProgress(transferred: Long, total: Long): Boolean
}

/** SFTP client. All methods are blocking. Paths are UTF-8. */
class RemoteFileSystem private constructor(private val sftp: SFTPClient) : Closeable {
    fun home(): String = canonicalize(".")

    fun canonicalize(path: String): String = sftpCall("Could not resolve $path") { sftp.canonicalize(path) }

    /** Entries of a directory, excluding "." and "..". */
    fun list(path: String): List<RemoteFile> = sftpCall("Could not list $path") {
        val dir = sftp.canonicalize(path)
        sftp.ls(dir).map { toRemoteFile(it.name, it.path, it.attributes) }
    }

    /** null if the path does not exist. */
    fun stat(path: String): RemoteFile? = sftpCall("Could not stat $path") {
        val attributes = try {
            sftp.lstat(path)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) return@sftpCall null
            throw e
        }
        val absolute = if (path.startsWith("/")) path else sftp.canonicalize(path)
        toRemoteFile(nameOf(absolute), absolute, attributes)
    }

    fun mkdir(path: String): Unit = sftpCall("Could not create $path") { sftp.mkdir(path) }

    fun rename(from: String, to: String): Unit = sftpCall("Could not rename $from") { sftp.rename(from, to) }

    /** Deletes a file, or a directory (its contents too when [recursive]). */
    fun delete(path: String, recursive: Boolean = false): Unit = sftpCall("Could not delete $path") {
        deleteInternal(path, recursive)
    }

    private fun deleteInternal(path: String, recursive: Boolean) {
        // lstat: never follow a symlink into its target when deleting.
        val attributes = sftp.lstat(path)
        if (attributes.type == FileMode.Type.DIRECTORY) {
            if (recursive) {
                for (child in sftp.ls(path)) deleteInternal(child.path, true)
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    fun chmod(path: String, mode: Int): Unit = sftpCall("Could not change permissions of $path") {
        sftp.chmod(path, mode and 0xFFF)
    }

    fun download(path: String, target: OutputStream, progress: TransferProgress? = null): Unit =
        sftpCall("Could not download $path") {
            sftp.open(path, EnumSet.of(OpenMode.READ)).use { file ->
                val total = try {
                    file.length()
                } catch (_: IOException) {
                    -1L
                }
                val input = file.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED)
                input.use { stream ->
                    val buffer = ByteArray(CHUNK)
                    var transferred = 0L
                    if (progress != null && !progress.onProgress(0, total)) throw SshCancelledException()
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        target.write(buffer, 0, n)
                        transferred += n
                        if (progress != null && !progress.onProgress(transferred, total)) throw SshCancelledException()
                    }
                }
                target.flush()
            }
        }

    /** [length] may be -1 when unknown. Creates or truncates [path]. */
    fun upload(source: InputStream, length: Long, path: String, progress: TransferProgress? = null): Unit =
        sftpCall("Could not upload $path") {
            sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                val output = file.RemoteFileOutputStream(0, MAX_UNCONFIRMED)
                val buffer = ByteArray(CHUNK)
                var transferred = 0L
                if (progress != null && !progress.onProgress(0, length)) throw SshCancelledException()
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n)
                    transferred += n
                    if (progress != null && !progress.onProgress(transferred, length)) throw SshCancelledException()
                }
                // Waits for the server to acknowledge every outstanding write.
                output.flush()
            }
        }

    override fun close() {
        try {
            sftp.close()
        } catch (_: Exception) {
        }
    }

    private fun toRemoteFile(name: String, path: String, attributes: FileAttributes): RemoteFile {
        val type = when (attributes.type) {
            FileMode.Type.REGULAR -> RemoteFileType.FILE
            FileMode.Type.DIRECTORY -> RemoteFileType.DIRECTORY
            FileMode.Type.SYMLINK -> RemoteFileType.SYMLINK
            else -> RemoteFileType.OTHER
        }
        var linkTarget: String? = null
        var directoryLike = type == RemoteFileType.DIRECTORY
        if (type == RemoteFileType.SYMLINK) {
            linkTarget = try {
                sftp.readlink(path)
            } catch (_: IOException) {
                null
            }
            directoryLike = try {
                sftp.stat(path).type == FileMode.Type.DIRECTORY
            } catch (_: IOException) {
                false // dangling link
            }
        }
        val permissions = if (attributes.has(FileAttributes.Flag.MODE)) attributes.mode.permissionsMask else -1
        val size = if (attributes.has(FileAttributes.Flag.SIZE)) attributes.size else 0L
        val mtime = if (attributes.has(FileAttributes.Flag.ACMODTIME)) attributes.mtime else 0L
        return RemoteFile(name, path, type, size, mtime, permissions, linkTarget, directoryLike)
    }

    private fun nameOf(path: String): String {
        if (path == "/") return "/"
        val trimmed = path.trimEnd('/')
        return trimmed.substring(trimmed.lastIndexOf('/') + 1)
    }

    internal companion object {
        private const val CHUNK = 32 * 1024
        private const val MAX_UNCONFIRMED = 16

        fun create(sftp: SFTPClient) = RemoteFileSystem(sftp)
    }
}

/** Runs an SFTP operation, mapping sshj/IO failures to [SshException]. */
private inline fun <T> sftpCall(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        throw wrapIo(what, e)
    }

internal fun wrapIo(what: String, e: Exception): SshException = when (e) {
    is SshException -> e
    is SFTPException -> SshException("$what: ${sftpMessage(e)}", e)
    is IOException, is SSHRuntimeException -> SshException("$what: ${e.message ?: e.javaClass.simpleName}", e)
    else -> SshException("$what: ${e.message ?: e.javaClass.simpleName}", e)
}

private fun sftpMessage(e: SFTPException): String {
    val message = e.message
    if (!message.isNullOrBlank()) return message
    return when (e.statusCode) {
        Response.StatusCode.NO_SUCH_FILE -> "No such file"
        Response.StatusCode.PERMISSION_DENIED -> "Permission denied"
        else -> e.statusCode?.toString() ?: "SFTP error"
    }
}

enum class SshKeyType(val displayName: String) {
    ED25519("Ed25519"),
    ECDSA_P256("ECDSA P-256"),
    RSA_3072("RSA 3072"),
    RSA_4096("RSA 4096"),
}

data class GeneratedKey(
    /** Unencrypted private key text that both sshj and OpenSSH can load. */
    val privateKey: String,
    /** authorized_keys line: "<type> <base64> <comment>". */
    val publicKey: String,
    val fingerprintSha256: String,
)

data class KeyInfo(
    val keyType: String,
    /** authorized_keys line "<type> <base64>" (comment included when the format carries one). */
    val publicKey: String,
    val fingerprintSha256: String,
)

object SshKeys {
    fun generate(type: SshKeyType, comment: String): GeneratedKey = KeyGenerator.generate(type, comment)

    /**
     * Parses a private key. Throws [KeyPassphraseException] when it is encrypted and
     * [passphrase] is null or wrong, [SshException] when the format is not recognised.
     */
    fun inspect(privateKey: String, passphrase: String?): KeyInfo {
        SshSecurity.install()
        return KeyFiles.inspect(privateKey, passphrase)
    }

    fun isEncrypted(privateKey: String): Boolean = KeyFiles.isEncrypted(privateKey)

    /**
     * [privateKey] (any format [inspect] reads) as an OpenSSH private key file with [comment],
     * encrypted with [newPassphrase] the way ssh-keygen does, or unencrypted when it is null or
     * empty. [passphrase] opens [privateKey] when it is encrypted; [KeyPassphraseException] when it
     * is missing or wrong.
     */
    fun exportOpenSsh(privateKey: String, passphrase: String?, comment: String, newPassphrase: String?): String {
        SshSecurity.install()
        val pair = KeyFiles.load(privateKey, passphrase)
        return OpenSshKeyWriter.write(pair, KeyGenerator.cleanComment(comment), newPassphrase)
    }
}
