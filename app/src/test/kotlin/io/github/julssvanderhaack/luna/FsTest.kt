package io.github.julssvanderhaack.luna

import io.github.julssvanderhaack.luna.fs.Answer
import io.github.julssvanderhaack.luna.fs.ArchiveFormat
import io.github.julssvanderhaack.luna.fs.Archives
import io.github.julssvanderhaack.luna.fs.Choice
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.FileOps
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.fs.LocalVfs
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.SortBy
import io.github.julssvanderhaack.luna.fs.SortOrder
import io.github.julssvanderhaack.luna.fs.Trash
import io.github.julssvanderhaack.luna.fs.fold
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
    fun foldMatchesTheSlowPath() {
        for (n in listOf("FOTO 1.JPG", "Álbum Canción", "ÑANDÚ", "plain ascii", "Ça va", "")) {
            val slow = java.text.Normalizer.normalize(n.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            assertEquals(slow, fold(n))
        }
    }

    @Test
    fun sortingByPrecomputedKeysMatchesNaturalOrder() {
        val names = (1..300).map { listOf("Foto $it.jpg", "álbum $it", "Canción ${300 - it}.mp3", "zeta_$it")[it % 4] }
        val entries = names.map { FileEntry(it, "/$it", false, 0, 0) }
        assertEquals(names.sortedWith(::naturalCompare), sortEntries(entries, SortOrder()).map { it.name })
    }

    @Test
    fun entryReadsAttributesAndLinks() {
        val dir = tmp.newFolder("d")
        val f = File(dir, "a.txt").apply { writeText("hola") }
        f.setLastModified(1_700_000_000_123)
        val e = LocalVfs.entry(f)
        assertEquals(4, e.size)
        assertFalse(e.isDir)
        assertEquals(1_700_000_000_000, e.modified / 1000 * 1000)
        assertEquals(Kind.TEXT, e.kind)
        val link = File(dir, "enlace")
        java.nio.file.Files.createSymbolicLink(link.toPath(), dir.toPath())
        LocalVfs.entry(link).let { assertTrue(it.isLink && it.isDir) }
        val broken = File(dir, "roto")
        java.nio.file.Files.createSymbolicLink(broken.toPath(), File(dir, "no existe").toPath())
        LocalVfs.entry(broken).let { assertTrue(it.isLink && !it.isDir) }
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
        val ops = FileOps({})
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
    fun conflictsAsk() = runBlocking {
        val src = tree()
        val dst = tmp.newFolder("dst")
        File(dst, "nota.txt").writeText("vieja")
        File(dst, "Fotos").mkdirs()
        File(dst, "Fotos/2.jpg").writeText("otra")
        File(dst, "Fotos/3.jpg").writeText("tres")
        val asked = ArrayList<String>()
        // folders merge, files: first replace, then skip for all
        val ops = FileOps({}) { c ->
            asked += c.incoming.name
            when {
                c.incoming.isDir -> Answer(Choice.REPLACE)
                c.incoming.name == "nota.txt" -> Answer(Choice.REPLACE)
                else -> Answer(Choice.SKIP, forAll = true)
            }
        }
        val t = ops.transfer(LocalVfs, LocalVfs.list(src.path), LocalVfs, dst.path, move = false)
        assertEquals(listOf("Fotos", "2.jpg", "nota.txt").sorted(), asked.sorted())
        assertEquals("hola", File(dst, "nota.txt").readText())
        assertEquals("otra", File(dst, "Fotos/2.jpg").readText())
        assertEquals("uno", File(dst, "Fotos/viaje/1.jpg").readText())
        assertTrue(File(dst, "Fotos/3.jpg").exists())
        assertTrue(t.replaced)
        // a merge reports what it created inside, never the folder that was already there
        assertFalse(t.created.contains(File(dst, "Fotos").path))
        assertTrue(t.created.contains(File(dst, "Fotos/viaje").path))
    }

    @Test
    fun trashAndRestore() = runBlocking {
        val vol = tmp.newFolder("vol")
        File(vol, "Docs").mkdirs()
        val f = File(vol, "Docs/carta ñ.txt").apply { writeText("x") }
        val d = File(vol, "Docs/sub").apply { mkdirs(); File(this, "a").writeText("a") }
        val items = FileOps({}).trash(listOf(LocalVfs.entry(f), LocalVfs.entry(d))) { vol.path }
        assertFalse(f.exists() || d.exists())
        val listed = Trash.list(vol.path)
        assertEquals(setOf(f.path, d.path), listed.map { it.originalPath }.toSet())
        assertTrue(File(vol, ".Trash-luna/.nomedia").exists())
        // same name again doesn't clash in the trash
        File(vol, "Docs/carta ñ.txt").writeText("y")
        Trash.put(File(vol, "Docs/carta ñ.txt"), vol.path)
        assertEquals(3, Trash.list(vol.path).size)
        File(vol, "Docs/carta ñ.txt").delete()
        items.forEach { Trash.restore(it) }
        assertEquals("x", f.readText())
        assertEquals("a", File(d, "a").readText())
        Trash.list(vol.path).forEach { Trash.purge(it) }
        assertTrue(Trash.list(vol.path).isEmpty())
    }

    @Test
    fun archiveFormatsRoundTrip() = runBlocking {
        for (format in ArchiveFormat.entries) {
            val src = tree()
            val ops = FileOps({})
            val target = File(src, "todo${format.ext}").path
            ops.compress(LocalVfs, LocalVfs.list(src.path), target, format)
            val items = Archives.list(File(target))
            assertTrue(format.name, items.any { it.path == "Fotos/viaje/1.jpg" && !it.isDir })
            val out = ops.extract(LocalVfs, LocalVfs.stat(target)!!, File(target))
            assertEquals(format.name, File(src, "todo").path, out)
            assertEquals("uno", File(out, "Fotos/viaje/1.jpg").readText())
            assertEquals("hola", File(out, "nota.txt").readText())
            val one = File(tmp.root, "uno-${format.name}")
            Archives.extractOne(File(target), "Fotos/2.jpg", one)
            assertEquals("dos", one.readText())
            src.deleteRecursively()
        }
        assertEquals("fotos", Archives.stem("fotos.tar.gz"))
        assertEquals("fotos", Archives.stem("fotos.zip"))
    }

    @Test
    fun zipSlipIsIgnored() = runBlocking {
        val dir = tmp.newFolder("z")
        val zip = File(dir, "malo.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../../fuera.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dentro.txt")); z.write("y".toByteArray()); z.closeEntry()
        }
        val out = FileOps({}).extract(LocalVfs, LocalVfs.stat(zip.path)!!, zip)
        assertFalse(File(tmp.root, "fuera.txt").exists())
        assertEquals("y", File(out, "dentro.txt").readText())
    }
}
