// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.OpenFailException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

internal object Connector {

    /**
     * sshj applies one transport timeout to the key exchange (where the host-key prompt
     * blocks the reader thread) and to the user-auth service request; keyboard-interactive
     * prompts also block the reader thread. While connecting we therefore use a long
     * [interactiveMillis]; once authenticated the transport goes back to [normalMillis].
     */
    data class Timeouts(val normalMillis: Int, val interactiveMillis: Int)

    @Volatile
    internal var timeouts = Timeouts(normalMillis = 30_000, interactiveMillis = 10 * 60_000)

    private const val KEEPALIVE_MAX_MISSED = 3

    suspend fun connect(
        target: SshTarget,
        credentials: List<SshCredential>,
        knownHosts: KnownHostsStore,
        interaction: SshInteraction,
        options: ConnectOptions,
        via: SshConnection?,
    ): SshConnection = withContext(Dispatchers.IO) {
        // Provider registration and sshj's cipher self-test are not free; keep them off the
        // caller's (possibly main) thread.
        SshSecurity.install()
        val timeouts = timeouts
        val client = SSHClient(TernSshConfig().apply { if (options.postQuantumKex) preferPostQuantum() })
        coroutineScope {
            val bridge = CallbackBridge(coroutineContext.job)
            val verifier = StoreHostKeyVerifier(target.host, target.port, knownHosts, interaction, bridge)
            client.addHostKeyVerifier(verifier)
            client.connectTimeout = options.connectTimeoutMillis
            client.transport.timeoutMs = timeouts.interactiveMillis
            val keepAlive = client.connection.keepAlive
            keepAlive.keepAliveInterval = options.keepAliveIntervalSeconds.coerceAtLeast(0)
            (keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEPALIVE_MAX_MISSED

            // sshj blocks in socket I/O and in its own wait/notify events, which thread
            // interruption cannot unblock; aborting the transport on cancellation makes the
            // blocked calls fail fast. The watcher resumes on another IO thread.
            val finished = AtomicBoolean(false)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) {
                        abort(client)
                        via?.close()
                    }
                }
            }
            try {
                try {
                    if (via == null) client.connect(target.host, target.port) else client.connectVia(via.openTunnel(target.host, target.port))
                } catch (e: Exception) {
                    throw mapConnectFailure(e, target, verifier, via)
                }
                try {
                    Authenticator(client, target, credentials, interaction, bridge, options, timeouts).authenticate()
                } catch (e: SshException) {
                    throw e
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    throw SshException("Authentication failed: ${e.message ?: e.javaClass.simpleName}", e)
                }
                client.transport.timeoutMs = timeouts.normalMillis
                val hostKey = verifier.verified ?: throw SshException("Host key was not verified")
                val connection = SshConnection.create(client, target, hostKey, client.userAuth.banner?.takeIf { it.isNotBlank() }, via)
                finished.set(true)
                connection
            } catch (t: Throwable) {
                finished.set(true)
                closeQuietly(client)
                via?.close()
                // A failure caused by our own cancellation (the aborted transport) must surface
                // as cancellation, not as a connection error that fails the caller's scope.
                coroutineContext.ensureActive()
                throw t
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun mapConnectFailure(e: Exception, target: SshTarget, verifier: StoreHostKeyVerifier, via: SshConnection?): Exception {
        val where = "${target.host}:${target.port}"
        return when {
            verifier.rejected -> SshHostKeyRejectedException("Host key for $where was not accepted")
            e is SshException -> e
            e is kotlinx.coroutines.CancellationException -> e
            // The jump host could not open the tunnel (unreachable target, or forwarding not allowed).
            via != null && e is OpenFailException ->
                SshException("${via.target.host} could not connect to $where: ${e.message ?: e.reason}", e)
            e is UnknownHostException -> SshException("Unknown host: ${target.host}", e)
            e is SocketTimeoutException -> SshException("Connection to $where timed out", e)
            e is ConnectException -> SshException("Could not connect to $where: ${e.message ?: "connection refused"}", e)
            else -> SshException("Connection to $where failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    fun closeQuietly(client: SSHClient) {
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * Kills a connection that may be mid key-exchange or mid authentication. Unlike
     * [SSHClient.disconnect], Transport.die() also fails sshj's pending KEX / service /
     * user-auth events, so threads waiting on them return immediately instead of after the
     * (long) transport timeout.
     */
    fun abort(client: SSHClient) {
        try {
            client.transport.die(SshCancelledException("Connection cancelled"))
        } catch (_: Exception) {
        }
        closeQuietly(client)
    }
}
