// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A running port forward. [close] stops listening and drops its open connections. */
class PortForward internal constructor(
    /** The port actually listened on (local for -L/-D, on the server for -R), useful when 0 was asked for. */
    val boundPort: Int,
    private val stop: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching(stop)
    }
}

internal object Forwarding {

    /** `ssh -L bindHost:bindPort:destHost:destPort`: a local port that reaches destHost:destPort from the server. */
    fun local(client: SSHClient, bindHost: String, bindPort: Int, destHost: String, destPort: Int): PortForward {
        val server = listen(bindHost, bindPort)
        val forwarder = client.newLocalPortForwarder(Parameters(bindHost, server.localPort, destHost, destPort), server)
        thread(name = "forward-L${server.localPort}", isDaemon = true) {
            try {
                forwarder.listen()
            } catch (_: IOException) {
                // Closed.
            }
        }
        return PortForward(server.localPort) {
            runCatching { forwarder.close() }
            runCatching { server.close() }
        }
    }

    /** `ssh -R remoteHost:remotePort:destHost:destPort`: a port on the server that reaches destHost:destPort from here. */
    fun remote(client: SSHClient, remoteHost: String, remotePort: Int, destHost: String, destPort: Int): PortForward {
        val forwarder = client.remotePortForwarder
        val forward = try {
            forwarder.bind(
                RemotePortForwarder.Forward(remoteHost, remotePort),
                SocketForwardingConnectListener(InetSocketAddress(destHost, destPort)),
            )
        } catch (e: Exception) {
            throw SshException("The server refused to forward port $remotePort: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return PortForward(forward.port) { runCatching { forwarder.cancel(forward) } }
    }

    /** `ssh -D bindHost:bindPort`: a SOCKS 4/4a/5 proxy whose connections leave from the server. */
    fun dynamic(client: SSHClient, bindHost: String, bindPort: Int): PortForward {
        val server = listen(bindHost, bindPort)
        val socks = SocksProxy(client, server)
        thread(name = "forward-D${server.localPort}", isDaemon = true) { socks.run() }
        return PortForward(server.localPort, socks::close)
    }

    private fun listen(host: String, port: Int): ServerSocket {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(host, port))
        } catch (e: IOException) {
            runCatching { server.close() }
            throw SshException("Could not listen on $host:$port: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return server
    }
}

/** SOCKS 4, 4a and 5 (CONNECT only, no authentication) over direct-tcpip channels. */
private class SocksProxy(private val client: SSHClient, private val server: ServerSocket) {
    private val open = ConcurrentHashMap.newKeySet<Closeable>()

    @Volatile
    private var closed = false

    fun run() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            open += socket
            thread(name = "socks-${socket.port}", isDaemon = true) { serve(socket) }
        }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket) {
        var channel: DirectConnection? = null
        try {
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = socket.getOutputStream()
            val request = when (input.readUnsignedByte()) {
                5 -> socks5(input, output)
                4 -> socks4(input)
                else -> null
            } ?: return
            channel = try {
                client.newDirectConnection(request.host, request.port)
            } catch (_: Exception) {
                request.reply(output, ok = false)
                return
            }
            open += channel
            request.reply(output, ok = true)
            val remote = channel
            // Client to server; the client finishing its side sends EOF, the answer keeps flowing.
            thread(name = "socks-up-${socket.port}", isDaemon = true) {
                copy(input, remote.outputStream)
                runCatching { remote.outputStream.close() }
            }
            copy(remote.inputStream, output)
        } catch (_: IOException) {
            // Client went away or sent garbage.
        } finally {
            runCatching { socket.close() }
            channel?.let { runCatching { it.close() } }
            open -= socket
            channel?.let { open -= it }
        }
    }

    private class Request(val host: String, val port: Int, val reply: (OutputStream, Boolean) -> Unit) {
        fun reply(output: OutputStream, ok: Boolean) {
            reply.invoke(output, ok)
            output.flush()
        }
    }

    private fun socks5(input: DataInputStream, output: OutputStream): Request? {
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (0.toByte() !in methods) {
            output.write(byteArrayOf(5, 0xFF.toByte())) // no acceptable method
            return null
        }
        output.write(byteArrayOf(5, 0))
        output.flush()
        if (input.readUnsignedByte() != 5) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.US_ASCII)
            4 -> (InetAddress.getByAddress(ByteArray(16).also(input::readFully)) as Inet6Address).hostAddress
            else -> {
                output.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)) // address type not supported
                return null
            }
        }
        val port = input.readUnsignedShort()
        if (command != 1) {
            output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)) // command not supported
            return null
        }
        // Failure is reported as "general failure": the server does not say why it could not connect.
        return Request(host, port) { out, ok -> out.write(byteArrayOf(5, if (ok) 0 else 1, 0, 1, 0, 0, 0, 0, 0, 0)) }
    }

    private fun socks4(input: DataInputStream): Request? {
        val command = input.readUnsignedByte()
        val port = input.readUnsignedShort()
        val ip = ByteArray(4).also(input::readFully)
        readNullTerminated(input) // user id
        // SOCKS 4a: 0.0.0.x (x != 0) means a host name follows.
        val host = if (ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()) {
            readNullTerminated(input)
        } else {
            InetAddress.getByAddress(ip).hostAddress
        }
        if (command != 1) return null
        return Request(host, port) { out, ok -> out.write(byteArrayOf(0, if (ok) 0x5A else 0x5B, 0, 0, 0, 0, 0, 0)) }
    }

    private fun readNullTerminated(input: DataInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0) break
            if (bytes.size() >= 255) throw IOException("SOCKS field too long")
            bytes.write(b)
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // Either side closed.
        }
    }
}
