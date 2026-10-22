package io.github.julssvanderhaack.luna

import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.FileOps
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.fs.LocalVfs
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.SortBy
import io.github.julssvanderhaack.luna.fs.SortOrder
import io.github.julssvanderhaack.luna.fs.naturalCompare
import io.github.julssvanderhaack.luna.fs.sortEntries
import io.github.julssvanderhaack.luna.ui.formatSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun naturalOrder() {
        val names = listOf("foto 10.jpg", "Foto 2.jpg", "álbum", "Zeta", "foto 1.jpg", "abeja")
        assertEquals(listOf("abeja", "álbum", "foto 1.jpg", "Foto 2.jpg", "foto 10.jpg", "Zeta"), names.sortedWith(::naturalCompare))
        assertEquals(0, naturalCompare("Canción", "cancion"))
    }

    @Test
    fun foldersFirstAndDescending() {
        val e = listOf(
            FileEntry("b.txt", "/b.txt", false, 10, 3),
            FileEntry("Z", "/Z", true, 0, 1),
            FileEntry("a.txt", "/a.txt", false, 99, 2),
        )
        assertEquals(listOf("Z", "a.txt", "b.txt"), sortEntries(e, SortOrder()).map { it.name })
        assertEquals(listOf("Z", "a.txt", "b.txt"), sortEntries(e, SortOrder(SortBy.SIZE, descending = true)).map { it.name })
        assertEquals(listOf("b.txt", "a.txt", "Z"), sortEntries(e, SortOrder(SortBy.DATE, descending = true, foldersFirst = false)).map { it.name })
    }

    @Test
    fun paths() {
        assertEquals("/a/b", Paths.join("/a", "b"))
        assertEquals("/b", Paths.join("/", "b"))
        assertEquals("/a", Paths.parent("/a/b"))
        assertEquals("/", Paths.parent("/a"))
        val taken = setOf("foto.jpg", "foto (2).jpg", "Fotos")
        assertEquals("foto (3).jpg", Paths.freeName("foto.jpg", false) { it in taken })
        assertEquals("Fotos (2)", Paths.freeName("Fotos", true) { it in taken })
        assertEquals(".bashrc (2)", Paths.freeName(".bashrc", false) { it == ".bashrc" })
        assertTrue(Paths.isInside("/a/b/c", "/a/b"))
        assertFalse(Paths.isInside("/a/bc", "/a/b"))
        assertEquals(Kind.IMAGE, Kind.of("IMG.JPG"))
        assertEquals(Kind.APK, Kind.of("app.apk"))
    }

    @Test
    fun sizes() {
        assertEquals("999 B", formatSize(999))
        assertEquals("1,5 kB", formatSize(1500))
        assertEquals("250 MB", formatSize(250_000_000))
    }

    private fun tree(): File {
        val src = tmp.newFolder("src")
        File(src, "Fotos/viaje").mkdirs()
        File(src, "Fotos/viaje/1.jpg").writeText("uno")
        File(src, "Fotos/2.jpg").writeText("dos")
        File(src, "nota.txt").writeText("hola")
        return src
    }

    @Test
    fun copyMoveAndConflicts() = runBlocking {
        val src = tree()
        val dst = tmp.newFolder("dst")
        val ops = FileOps {}
        val entries = LocalVfs.list(src.path)
        ops.transfer(LocalVfs, entries, LocalVfs, dst.path, move = false)
        assertEquals("uno", File(dst, "Fotos/viaje/1.jpg").readText())
        // pasting again doesn't overwrite: "nota (2).txt", "Fotos (2)"
        ops.transfer(LocalVfs, entries, LocalVfs, dst.path, move = false)
        assertEquals("hola", File(dst, "nota (2).txt").readText())
        assertTrue(File(dst, "Fotos (2)/2.jpg").exists())
        // moving into itself is refused
        val fotos = LocalVfs.stat(File(src, "Fotos").path)!!
        try {
            ops.transfer(LocalVfs, listOf(fotos), LocalVfs, File(src, "Fotos/viaje").path, move = true)
            error("should have refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("dentro de sí misma"))
        }
        ops.transfer(LocalVfs, listOf(fotos), LocalVfs, dst.path, move = true)
        assertFalse(File(src, "Fotos").exists())
        assertTrue(File(dst, "Fotos (3)/viaje/1.jpg").exists())
        val t = ops.tally(LocalVfs, LocalVfs.list(dst.path))
        // Fotos (2) + nota + nota (2) + Fotos (2) (2) + Fotos (3) (2)
        assertEquals(8, t.files)
    }

    @Test
    fun zipRoundTrip() = runBlocking {
        val src = tree()
        val ops = FileOps {}
        val zip = File(src, "todo.zip").path
        ops.zip(LocalVfs, LocalVfs.list(src.path), zip)
        val out = ops.unzip(LocalVfs, LocalVfs.stat(zip)!!)
        assertEquals(File(src, "todo").path, out)
        assertEquals("uno", File(out, "Fotos/viaje/1.jpg").readText())
        assertEquals("hola", File(out, "nota.txt").readText())
    }

    @Test
    fun zipSlipIsIgnored() = runBlocking {
        val dir = tmp.newFolder("z")
        val zip = File(dir, "malo.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../../fuera.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dentro.txt")); z.write("y".toByteArray()); z.closeEntry()
        }
        val out = FileOps {}.unzip(LocalVfs, LocalVfs.stat(zip.path)!!)
        assertFalse(File(tmp.root, "fuera.txt").exists())
        assertEquals("y", File(out, "dentro.txt").readText())
    }
}
