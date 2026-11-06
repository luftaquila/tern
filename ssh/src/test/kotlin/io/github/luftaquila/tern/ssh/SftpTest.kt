// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SftpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun withFs(block: (RemoteFileSystem, Path) -> Unit) {
        val root = tmp.newFolder("root").toPath()
        TestServer {
            passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
            userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }.use { server ->
            val connection = runBlocking {
                withTimeout(30_000) {
                    SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), InMemoryKnownHosts(), ScriptedInteraction())
                }
            }
            connection.use { c -> c.openFileSystem().use { fs -> block(fs, root) } }
        }
    }

    @Test
    fun basicOperationsWithKoreanNames() = withFs { fs, root ->
        assertEquals("/", fs.home())
        fs.mkdir("/문서")
        val content = "안녕하세요, Tern!\n".toByteArray(Charsets.UTF_8)
        fs.upload(ByteArrayInputStream(content), content.size.toLong(), "/문서/한글 파일.txt")
        assertTrue(Files.exists(root.resolve("문서").resolve("한글 파일.txt")))

        val listing = fs.list("/문서")
        val entry = listing.single()
        assertEquals("한글 파일.txt", entry.name)
        assertEquals("/문서/한글 파일.txt", entry.path)
        assertEquals(RemoteFileType.FILE, entry.type)
        assertEquals(content.size.toLong(), entry.size)
        assertFalse(entry.isDirectoryLike)
        assertTrue(entry.modifiedEpochSeconds > 0)

        val out = ByteArrayOutputStream()
        fs.download("/문서/한글 파일.txt", out)
        assertTrue(content.contentEquals(out.toByteArray()))

        val dir = fs.stat("/문서")
        assertNotNull(dir)
        assertEquals(RemoteFileType.DIRECTORY, dir.type)
        assertTrue(dir.isDirectoryLike)
        assertEquals("문서", dir.name)
        assertNull(fs.stat("/없는 파일"))

        // Relative paths resolve against the home directory.
        assertEquals("/문서", fs.canonicalize("문서"))
        assertEquals(listOf("문서"), fs.list(".").map { it.name })

        fs.rename("/문서/한글 파일.txt", "/문서/renamed.txt")
        assertNull(fs.stat("/문서/한글 파일.txt"))
        assertEquals(RemoteFileType.FILE, fs.stat("/문서/renamed.txt")!!.type)
    }

    @Test
    fun chmodAndPermissions() = withFs { fs, root ->
        fs.upload(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, "/script.sh")
        fs.chmod("/script.sh", "750".toInt(8))
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("script.sh"))))
        assertEquals("750".toInt(8), fs.stat("/script.sh")!!.permissions)
    }

    @Test
    fun symlinksResolveForDirectoryLike() = withFs { fs, root ->
        fs.mkdir("/target")
        Files.createSymbolicLink(root.resolve("link"), Paths.get("target"))
        Files.createSymbolicLink(root.resolve("dangling"), Paths.get("nowhere"))
        val byName = fs.list("/").associateBy { it.name }
        val link = byName.getValue("link")
        assertEquals(RemoteFileType.SYMLINK, link.type)
        assertTrue(link.isDirectoryLike)
        assertNotNull(link.linkTarget)
        assertTrue(link.linkTarget.endsWith("target"), link.linkTarget)
        val dangling = byName.getValue("dangling")
        assertEquals(RemoteFileType.SYMLINK, dangling.type)
        assertFalse(dangling.isDirectoryLike)
        assertTrue(byName.getValue("target").isDirectoryLike)

        // Deleting a symlink to a directory removes the link, not the target.
        fs.delete("/link")
        assertNotNull(fs.stat("/target"))
        assertNull(fs.stat("/link"))
    }

    @Test
    fun recursiveDelete() = withFs { fs, root ->
        fs.mkdir("/a")
        fs.mkdir("/a/b")
        fs.upload(ByteArrayInputStream(byteArrayOf(1)), 1, "/a/b/c.txt")
        fs.upload(ByteArrayInputStream(byteArrayOf(2)), 1, "/a/d.txt")
        assertFailsWith<SshException> { fs.delete("/a", recursive = false) }
        assertTrue(Files.exists(root.resolve("a/b/c.txt")))
        fs.delete("/a", recursive = true)
        assertFalse(Files.exists(root.resolve("a")))
        assertNull(fs.stat("/a"))
        // Errors carry a message, never a bare sshj exception.
        val e = assertFailsWith<SshException> { fs.list("/missing") }
        assertTrue(e.message!!.contains("/missing"), e.message)
    }

    @Test
    fun largeTransferWithProgress() = withFs { fs, _ ->
        val data = ByteArray(5 * 1024 * 1024 + 123).also { Random(42).nextBytes(it) }
        val uploadProgress = mutableListOf<Pair<Long, Long>>()
        fs.upload(ByteArrayInputStream(data), data.size.toLong(), "/big.bin") { done, total ->
            uploadProgress.add(done to total)
            true
        }
        assertEquals(0L to data.size.toLong(), uploadProgress.first())
        assertEquals(data.size.toLong() to data.size.toLong(), uploadProgress.last())
        assertTrue(uploadProgress.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertEquals(data.size.toLong(), fs.stat("/big.bin")!!.size)

        val downloadProgress = mutableListOf<Pair<Long, Long>>()
        val out = ByteArrayOutputStream()
        fs.download("/big.bin", out) { done, total ->
            downloadProgress.add(done to total)
            true
        }
        assertTrue(data.contentEquals(out.toByteArray()))
        assertEquals(data.size.toLong() to data.size.toLong(), downloadProgress.last())

        // Unknown length uploads report -1 as the total.
        val unknown = mutableListOf<Long>()
        fs.upload(ByteArrayInputStream(data, 0, 1000), -1, "/unknown.bin") { _, total -> unknown.add(total); true }
        assertTrue(unknown.all { it == -1L })
    }

    @Test
    fun cancellation() = withFs { fs, _ ->
        val data = ByteArray(4 * 1024 * 1024).also { Random(7).nextBytes(it) }
        assertFailsWith<SshCancelledException> {
            fs.upload(ByteArrayInputStream(data), data.size.toLong(), "/cancelled.bin") { done, _ -> done < 1024 * 1024 }
        }
        fs.upload(ByteArrayInputStream(data), data.size.toLong(), "/full.bin")
        val out = ByteArrayOutputStream()
        assertFailsWith<SshCancelledException> {
            fs.download("/full.bin", out) { done, _ -> done < 512 * 1024 }
        }
        assertTrue(out.size() < data.size)
        // The file system is still usable afterwards.
        assertEquals(data.size.toLong(), fs.stat("/full.bin")!!.size)
    }
}
