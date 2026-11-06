// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.channel.PtyMode
import org.apache.sshd.server.Environment
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.shell.ShellFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShellTest {

    private fun withShell(block: (SshConnection, List<RecordingShell>) -> Unit) {
        val shells = java.util.concurrent.CopyOnWriteArrayList<RecordingShell>()
        TestServer {
            passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
            userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            shellFactory = ShellFactory { RecordingShell().also { shells.add(it) } }
        }.use { server ->
            val connection = runBlocking {
                withTimeout(30_000) {
                    SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), InMemoryKnownHosts(), ScriptedInteraction())
                }
            }
            connection.use { block(it, shells) }
        }
    }

    /** Reads until [expected] bytes arrived (or fails after a timeout). */
    private fun readExactly(input: InputStream, expected: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        val deadline = System.currentTimeMillis() + 10_000
        while (out.size() < expected) {
            check(System.currentTimeMillis() < deadline) { "timed out after ${out.size()} bytes" }
            val n = input.read(buffer, 0, minOf(buffer.size, expected - out.size()))
            check(n >= 0) { "EOF after ${out.size()} bytes" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun ptyEnvironmentEchoResizeAndExit() = withShell { connection, shells ->
        val shell = connection.openShell(
            termType = "xterm-256color",
            columns = 100,
            rows = 30,
            widthPixels = 800,
            heightPixels = 600,
            env = mapOf("LANG" to "ko_KR.UTF-8", "TERN_TEST" to "1"),
        )
        val recorder = shells.single()
        assertTrue(recorder.started.await(10, TimeUnit.SECONDS))
        assertTrue(shell.isOpen)
        assertEquals("xterm-256color", recorder.env[Environment.ENV_TERM])
        assertEquals("100", recorder.env[Environment.ENV_COLUMNS])
        assertEquals("30", recorder.env[Environment.ENV_LINES])
        assertEquals("ko_KR.UTF-8", recorder.env["LANG"])
        assertEquals("1", recorder.env["TERN_TEST"])
        assertEquals(1, recorder.ptyModes[PtyMode.IUTF8])
        assertEquals(0x7F, recorder.ptyModes[PtyMode.VERASE])

        // Every write is flushed immediately (no explicit flush here), multi-byte UTF-8 intact.
        val text = "안녕하세요 hello 한글\r".toByteArray(Charsets.UTF_8)
        shell.output.write(text)
        assertTrue(text.contentEquals(readExactly(shell.input, text.size)))
        // Byte-by-byte writes too (a terminal sends single keystrokes).
        for (b in "ls".toByteArray()) shell.output.write(b.toInt())
        assertEquals("ls", String(readExactly(shell.input, 2), Charsets.UTF_8))

        shell.resize(120, 40, 960, 800)
        val change = recorder.windowChanges.poll(10, TimeUnit.SECONDS)
        assertNotNull(change)
        assertEquals("120" to "40", change)

        shell.output.write("\rexit\r".toByteArray())
        readExactly(shell.input, 6)
        // EOF once the remote command exits, then the exit status is known.
        val deadline = System.currentTimeMillis() + 10_000
        while (shell.input.read() >= 0) {
            check(System.currentTimeMillis() < deadline) { "no EOF" }
        }
        val statusDeadline = System.currentTimeMillis() + 10_000
        while (shell.exitStatus == null && System.currentTimeMillis() < statusDeadline) Thread.sleep(20)
        assertEquals(7, shell.exitStatus)
        shell.close()
        assertFalse(shell.isOpen)
        // The connection survives a closed shell.
        assertTrue(connection.isConnected)
    }

    @Test
    fun twoShellsOnOneConnection() = withShell { connection, shells ->
        val a = connection.openShell("xterm-256color", 80, 24, 0, 0, emptyMap())
        val b = connection.openShell("vt100", 132, 50, 0, 0, emptyMap())
        assertEquals(2, shells.size)
        b.output.write("bee\r".toByteArray())
        a.output.write("a\r".toByteArray())
        assertEquals("a\r", String(readExactly(a.input, 2)))
        assertEquals("bee\r", String(readExactly(b.input, 4)))
        a.close()
        // Closing one shell leaves the other usable.
        b.output.write("x".toByteArray())
        assertEquals("x", String(readExactly(b.input, 1)))
        b.close()
    }
}
