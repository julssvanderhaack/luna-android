package io.github.julssvanderhaack.luna

import io.github.julssvanderhaack.luna.fs.FileOps
import io.github.julssvanderhaack.luna.fs.LocalVfs
import io.github.julssvanderhaack.luna.remote.AuthException
import io.github.julssvanderhaack.luna.remote.HostKeyException
import io.github.julssvanderhaack.luna.remote.Server
import io.github.julssvanderhaack.luna.remote.ServerStore
import io.github.julssvanderhaack.luna.remote.Sftp
import io.github.julssvanderhaack.luna.server.authorizedKeys
import io.github.julssvanderhaack.luna.server.buildSshServer
import io.github.julssvanderhaack.luna.settings.ServerConfig
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.SshServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.security.KeyPairGenerator

/** Luna's SFTP client against Luna's own SFTP server, as the phone and the PC would meet. */
class SftpTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val servers = ArrayList<SshServer>()

    @After
    fun stop() = servers.forEach { it.stop(true) }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun start(cfg: ServerConfig): SshServer =
        buildSshServer(cfg, File(tmp.newFolder(), "host_key")).also { it.start(); servers += it }

    @Test
    fun passwordHostKeyAndCopies() = runBlocking {
        val root = tmp.newFolder("phone")
        File(root, "DCIM").mkdir()
        File(root, "DCIM/foto.jpg").writeText("píxeles")
        val port = freePort()
        start(ServerConfig(port = port, user = "luna", password = "secreto", root = root.path))

        val store = ServerStore(tmp.newFolder("app"))
        val sftp = Sftp(store)
        val s = Server(host = "127.0.0.1", port = port, user = "luna", password = "secreto")
        store.save(s)

        // first time: unknown host, the UI shows the fingerprint
        val e = runCatching { sftp.connect(s) }.exceptionOrNull()
        assertTrue("$e", e is HostKeyException)
        e as HostKeyException
        assertFalse(e.changed)
        assertTrue(e.fingerprint.startsWith("SHA256:"))
        store.trust(e.hostPort, e.fingerprint)

        val vfs = sftp.connect(s)
        assertEquals("/", vfs.home)
        assertEquals(listOf("DCIM"), vfs.list("/").map { it.name })
        assertTrue(vfs.list("/").single().isDir)

        // phone → PC and PC → phone through FileOps, like Pegar
        val pc = tmp.newFolder("pc")
        FileOps {}.transfer(vfs, vfs.list("/"), LocalVfs, pc.path, move = false)
        assertEquals("píxeles", File(pc, "DCIM/foto.jpg").readText())
        File(pc, "nota.txt").writeText("del PC")
        FileOps {}.transfer(LocalVfs, listOf(LocalVfs.stat(File(pc, "nota.txt").path)!!), vfs, "/DCIM", move = false)
        assertEquals("del PC", File(root, "DCIM/nota.txt").readText())
        // rename and recursive delete on the server
        assertTrue(vfs.move("/DCIM/nota.txt", "/DCIM/nota2.txt"))
        vfs.delete(vfs.stat("/DCIM")!!)
        assertFalse(File(root, "DCIM").exists())

        sftp.disconnect(s.id)
        val bad = s.copy(id = "otro", password = "mal")
        store.save(bad)
        assertTrue(runCatching { sftp.connect(bad) }.exceptionOrNull() is AuthException)
    }

    @Test
    fun changedHostKeyIsReported() {
        val root = tmp.newFolder("r")
        val port = freePort()
        start(ServerConfig(port = port, password = "x", root = root.path))
        val store = ServerStore(tmp.newFolder("app"))
        store.trust("127.0.0.1:$port", "SHA256:otra-huella")
        val e = runCatching { Sftp(store).connect(Server(host = "127.0.0.1", port = port, user = "luna", password = "x")) }.exceptionOrNull()
        assertTrue("$e", e is HostKeyException && e.changed)
    }

    @Test
    fun publicKeyLoginAndReadOnly() = runBlocking {
        val root = tmp.newFolder("ro")
        File(root, "a.txt").writeText("a")
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val pub = PublicKeyEntry.toString(kp.public)
        assertEquals(1, authorizedKeys("# comentario\n$pub julss@pc\nbasura\n").size)
        val port = freePort()
        // no password at all: only the key gets in
        start(ServerConfig(port = port, password = "", authorizedKeys = pub, root = root.path, readOnly = true))

        val app = tmp.newFolder("app")
        val store = ServerStore(app)
        val s = Server(host = "127.0.0.1", port = port, user = "luna", hasKey = true)
        store.keyDir.mkdirs()
        // the client loads the key the way it loads an imported id_ecdsa (OpenSSH/PEM)
        store.keyFile(s.id).outputStream().use { out ->
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, "test", null, out)
        }
        val sftp = Sftp(store)
        val hk = runCatching { sftp.connect(s) }.exceptionOrNull() as HostKeyException
        store.trust(hk.hostPort, hk.fingerprint)
        val vfs = sftp.connect(s)
        assertEquals(listOf("a.txt"), vfs.list("/").map { it.name })
        assertEquals("a", vfs.openRead("/a.txt").bufferedReader().readText())
        assertTrue(runCatching { vfs.openWrite("/b.txt").use { it.write(1) } }.isFailure)
        assertTrue(runCatching { vfs.deleteOne("/a.txt", false) }.isFailure)
        assertTrue(File(root, "a.txt").exists())
        assertFalse(File(root, "b.txt").exists())
    }
}
