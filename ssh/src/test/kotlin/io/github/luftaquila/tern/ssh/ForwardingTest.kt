// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ShellFactory
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ForwardingTest {

    /** A TCP service that sends back whatever it receives. */
    private class EchoServer : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName(HOST))
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true, name = "echo") {
                while (true) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true) {
                        client.use { runCatching { it.getInputStream().copyTo(it.getOutputStream()) } }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun server(configure: SshServer.() -> Unit = {}) = TestServer {
        passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
        userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        configure()
    }

    private fun connect(
        target: SshTarget,
        via: SshConnection? = null,
        knownHosts: KnownHostsStore = InMemoryKnownHosts(),
    ): SshConnection = runBlocking {
        withTimeout(30_000) {
            SshConnection.connect(target, listOf(SshCredential.Password(PASSWORD)), knownHosts, ScriptedInteraction(), via = via)
        }
    }

    private fun Socket.roundTrip(text: String): String {
        soTimeout = 10_000
        getOutputStream().write(text.toByteArray())
        return String(readFully(getInputStream(), text.length))
    }

    private fun readFully(input: InputStream, length: Int): ByteArray = ByteArray(length).also { DataInputStream(input).readFully(it) }

    @Test
    fun localForwardReachesAServiceNearTheServer(): Unit = EchoServer().use { echo ->
        server().use { server ->
            val connection = connect(server.target)
            val forward = connection.forwardLocal(0, HOST, echo.port)
            Socket(HOST, forward.boundPort).use { assertEquals("ping", it.roundTrip("ping")) }

            connection.close()
            assertFailsWith<IOException> { Socket(HOST, forward.boundPort).close() }
            Unit
        }
    }

    @Test
    fun remoteForwardReachesAServiceOnTheDevice(): Unit = EchoServer().use { echo ->
        server().use { server ->
            connect(server.target).use { connection ->
                val forward = connection.forwardRemote(0, HOST, echo.port)
                Socket("localhost", forward.boundPort).use { assertEquals("pong", it.roundTrip("pong")) }
            }
        }
    }

    @Test
    fun socksProxyConnectsFromTheServer(): Unit = EchoServer().use { echo ->
        server().use { server ->
            connect(server.target).use { connection ->
                val proxy = connection.forwardDynamic(0)

                // SOCKS 5 with a host name, the way browsers use it.
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(HOST, proxy.boundPort))).use {
                    it.connect(InetSocketAddress.createUnresolved("localhost", echo.port), 10_000)
                    assertEquals("five", it.roundTrip("five"))
                }

                // SOCKS 4a: address 0.0.0.1 and the host name after the (empty) user id.
                Socket(HOST, proxy.boundPort).use {
                    it.soTimeout = 10_000
                    val port = byteArrayOf((echo.port shr 8).toByte(), echo.port.toByte())
                    it.getOutputStream().write(byteArrayOf(4, 1) + port + byteArrayOf(0, 0, 0, 1, 0) + "localhost".toByteArray() + byteArrayOf(0))
                    assertEquals(0x5A, readFully(it.getInputStream(), 8)[1].toInt())
                    assertEquals("four", it.roundTrip("four"))
                }
            }
        }
    }

    @Test
    fun connectsThroughAJumpHost() {
        val shells = CopyOnWriteArrayList<RecordingShell>()
        server().use { jumpServer ->
            server { shellFactory = ShellFactory { RecordingShell().also(shells::add) } }.use { targetServer ->
                val jump = connect(jumpServer.target)
                val connection = connect(targetServer.target, via = jump)
                // The target's own host key is the one verified.
                assertEquals(opensshKeyId(targetServer.hostKeyPairs.single().public), "${connection.hostKey.keyType} ${connection.hostKey.publicKeyBase64}")

                val shell = connection.openShell("xterm-256color", 80, 24, 0, 0, emptyMap())
                shell.output.write("hi".toByteArray())
                assertEquals("hi", String(readFully(shell.input, 2)))

                connection.close()
                assertFalse(jump.isConnected)
            }
        }
    }

    @Test
    fun failedConnectionThroughAJumpHostClosesIt(): Unit = server().use { jumpServer ->
        val jump = connect(jumpServer.target)
        val nothingListens = ServerSocket(0, 1, InetAddress.getByName(HOST)).use { it.localPort }
        assertFailsWith<SshException> { connect(SshTarget(HOST, nothingListens, USER), via = jump) }
        assertFalse(jump.isConnected)
    }
}
