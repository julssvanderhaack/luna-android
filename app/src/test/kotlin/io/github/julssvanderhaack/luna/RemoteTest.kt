package io.github.julssvanderhaack.luna

import io.github.julssvanderhaack.luna.fs.FileOps
import io.github.julssvanderhaack.luna.fs.LocalVfs
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.remote.AuthException
import io.github.julssvanderhaack.luna.remote.FtpVfs
import io.github.julssvanderhaack.luna.remote.Protocol
import io.github.julssvanderhaack.luna.remote.RemoteVfs
import io.github.julssvanderhaack.luna.remote.Server
import io.github.julssvanderhaack.luna.remote.SmbVfs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SMB and FTP(S) against real servers on this machine, when they're running
 * (a user-mode smbd on 4450 with share Prueba, pyftpdlib on 2121/2122):
 * LUNA_SMB=host:port:share:user:password, LUNA_FTP=host:port:user:password, LUNA_FTPS=…
 */
class RemoteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun env(name: String) = System.getenv(name)?.split(':')

    private fun exercise(v: RemoteVfs, root: String) = runBlocking {
        val dir = Paths.join(root, "luna-prueba-${System.nanoTime()}")
        v.mkdir(dir)
        try {
            val f = Paths.join(dir, "canción ñ.txt")
            v.openWrite(f).use { it.write("hola desde Luna".toByteArray()) }
            assertEquals("hola desde Luna", v.openRead(f).use { it.readBytes().decodeToString() })
            val e = v.stat(f)!!
            assertEquals(15, e.size)
            assertFalse(e.isDir)
            assertTrue(v.stat(dir)!!.isDir)
            assertNull(v.stat(Paths.join(dir, "no existe")))
            v.setModified(f, 1_700_000_000_000)
            assertEquals(1_700_000_000_000 / 1000, v.stat(f)!!.modified / 1000)
            // a tree up and back down, through the same engine as Pegar
            val src = tmp.newFolder("subir")
            File(src, "Fotos/viaje").mkdirs()
            File(src, "Fotos/viaje/1.jpg").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
            File(src, "nota.txt").writeText("nota")
            val ops = FileOps({})
            ops.transfer(LocalVfs, LocalVfs.list(src.path), v, dir, move = false)
            assertEquals(setOf("canción ñ.txt", "Fotos", "nota.txt"), v.list(dir).map { it.name }.toSet())
            assertTrue(v.move(Paths.join(dir, "nota.txt"), Paths.join(dir, "nota 2.txt")))
            val back = tmp.newFolder("bajar")
            ops.transfer(v, v.list(dir), LocalVfs, back.path, move = false)
            assertTrue(File(back, "Fotos/viaje/1.jpg").readBytes().contentEquals(File(src, "Fotos/viaje/1.jpg").readBytes()))
            assertEquals("nota", File(back, "nota 2.txt").readText())
            v.createFile(Paths.join(dir, "vacío"))
            assertEquals(0, v.stat(Paths.join(dir, "vacío"))!!.size)
        } finally {
            v.stat(dir)?.let { v.delete(it) }
        }
        assertNull(v.stat(dir))
    }

    @Test
    fun smb() {
        val e = env("LUNA_SMB")
        assumeTrue(e != null)
        val (host, port, share, user, pw) = e!!
        val s = Server(host = host, port = port.toInt(), user = user, protocol = Protocol.SMB, share = share)
        val v = SmbVfs.connect(s, pw)
        try {
            exercise(v, "/")
        } finally {
            v.close()
        }
        try {
            SmbVfs.connect(s, "mala").close()
            error("bad password accepted")
        } catch (_: AuthException) {
        }
    }

    private fun ftp(name: String, protocol: Protocol) {
        val e = env(name)
        assumeTrue(e != null)
        val (host, port, user, pw) = e!!
        val s = Server(host = host, port = port.toInt(), user = user, protocol = protocol)
        val v = FtpVfs.connect(s, pw)
        try {
            exercise(v, v.home)
        } finally {
            v.close()
        }
        try {
            FtpVfs.connect(s, "mala").close()
            error("bad password accepted")
        } catch (_: AuthException) {
        }
    }

    @Test
    fun ftp() = ftp("LUNA_FTP", Protocol.FTP)

    @Test
    fun ftps() = ftp("LUNA_FTPS", Protocol.FTPS)
}
