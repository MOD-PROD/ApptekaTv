package com.tomclaw.appsend.tvlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class TvFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var outside: File

    private fun setUp(): List<File> {
        root = tmp.newFolder("sdcard").canonicalFile
        outside = tmp.newFolder("private").canonicalFile
        File(root, "Movies").mkdirs()
        File(root, "Movies/film.mkv").writeText("film")
        File(root, ".hidden").writeText("h")
        File(root, "Android/data/com.other").mkdirs()
        File(root, "Android/media").mkdirs()
        File(outside, "keep.txt").writeText("keep")
        return listOf(root)
    }

    @Test
    fun `list hides private and hidden entries`() {
        val roots = setUp()
        val listing = TvFiles.list(roots, root.path, hidden = false)
        assertTrue(listing.getBoolean("ok"))
        val names = (0 until listing.getJSONArray("entries").length()).map { listing.getJSONArray("entries").getJSONObject(it).getString("name") }
        assertEquals(listOf("Android", "Movies"), names)
        assertTrue(listing.isNull("parent"))
        val android = TvFiles.list(roots, "${root.path}/Android", hidden = true)
        val inAndroid = (0 until android.getJSONArray("entries").length()).map { android.getJSONArray("entries").getJSONObject(it).getString("name") }
        assertEquals(listOf("media"), inAndroid)
        assertEquals(root.path, android.getString("parent"))
    }

    @Test
    fun `mkdir rename delete stay inside roots`() {
        val roots = setUp()
        assertTrue(TvFiles.mkdir(roots, root.path, "Nouveau").getBoolean("ok"))
        assertEquals("exists", TvFiles.mkdir(roots, root.path, "Nouveau").getString("error"))
        assertEquals("bad_name", TvFiles.mkdir(roots, root.path, "../x").getString("error"))
        assertTrue(TvFiles.rename(roots, "${root.path}/Nouveau", "Renommé").getBoolean("ok"))
        assertTrue(File(root, "Renommé").isDirectory)
        assertEquals("bad_path", TvFiles.rename(roots, root.path, "x").getString("error"))
        assertEquals("bad_path", TvFiles.delete(roots, listOf(root.path)).getString("error"))
        assertEquals("bad_path", TvFiles.delete(roots, listOf("${outside.path}/keep.txt")).getString("error"))
        assertTrue(TvFiles.delete(roots, listOf("${root.path}/Movies", "${root.path}/Renommé")).getBoolean("ok"))
        assertFalse(File(root, "Movies").exists())
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun `delete does not follow links`() {
        val roots = setUp()
        val dir = File(root, "Links").apply { mkdirs() }
        val inner = File(root, "Movies").toPath()
        Files.createSymbolicLink(File(dir, "toMovies").toPath(), inner)
        assertTrue(TvFiles.delete(roots, listOf(dir.path)).getBoolean("ok"))
        assertTrue(File(root, "Movies/film.mkv").exists())
    }

    @Test
    fun `incoming file gets a free name`() {
        val roots = setUp()
        val (pair, error) = TvFiles.incoming(roots, "${root.path}/Movies", "film.mkv", 4)
        assertNull(error)
        val (part, target) = pair!!
        part.writeText("new!")
        val done = TvFiles.complete(part, target)
        assertEquals("film (1).mkv", done.getString("name"))
        assertEquals("film", File(root, "Movies/film.mkv").readText())
        assertEquals("bad_name", TvFiles.incoming(roots, root.path, "a/b", 1).second!!.getString("error"))
        assertEquals("bad_path", TvFiles.incoming(roots, outside.path, "a", 1).second!!.getString("error"))
    }

    @Test
    fun `readable refuses directories and outside files`() {
        val roots = setUp()
        assertNotNull(TvFiles.readable(roots, "${root.path}/Movies/film.mkv").first)
        assertEquals("not_found", TvFiles.readable(roots, "${root.path}/Movies").second!!.getString("error"))
        assertEquals("bad_path", TvFiles.readable(roots, "${outside.path}/keep.txt").second!!.getString("error"))
    }

    @Test
    fun `move preserves nested files empty files and existing destination`() {
        val roots = setUp()
        val destination = File(root, "Destination").apply { mkdir() }
        File(destination, "Movies").apply { mkdir() }
        File(destination, "Movies/keep").writeText("keep")
        val source = File(root, "Movies")
        File(source, "Nested").apply { mkdir() }
        File(source, "Nested/empty").writeBytes(byteArrayOf())
        val other = File(root, ".hidden")

        val result = TvFiles.move(roots, listOf(source.path, other.path), destination.path)

        assertEquals(2, result.getInt("moved"))
        assertFalse(source.exists())
        assertFalse(other.exists())
        assertEquals("keep", File(destination, "Movies/keep").readText())
        assertEquals("film", File(destination, "Movies (1)/film.mkv").readText())
        assertTrue(File(destination, "Movies (1)/Nested/empty").isFile)
        assertEquals(0L, File(destination, "Movies (1)/Nested/empty").length())
        assertEquals("h", File(destination, ".hidden").readText())
    }

    @Test
    fun `same directory is unchanged and descendant is refused`() {
        val roots = setUp()
        val directory = File(root, "Movies")
        assertEquals(1, TvFiles.move(roots, listOf(directory.path), root.path).getInt("moved"))
        assertFalse(File(root, "Movies (1)").exists())
        val child = File(directory, "nested").apply { mkdir() }
        assertEquals("bad_path", TvFiles.move(roots, listOf(directory.path), child.path).getString("error"))
        assertEquals("film", File(directory, "film.mkv").readText())
    }

    @Test
    fun `move keeps failed sources and does not remove an existing collision`() {
        val roots = setUp()
        val destination = File(root, "Destination").apply { mkdir() }
        val source = File(root, "Movies")
        File(source, ".incoming.atvpart").writeText("unfinished")
        File(destination, "Movies").apply { mkdir() }
        File(destination, "Movies/keep").writeText("keep")
        val other = File(root, ".hidden")

        val result = TvFiles.move(roots, listOf(source.path, other.path), destination.path)

        assertFalse(result.getBoolean("ok"))
        assertEquals(1, result.getInt("moved"))
        assertEquals(source.path, result.getJSONArray("failedPaths").getString(0))
        assertEquals("unfinished", File(source, ".incoming.atvpart").readText())
        assertEquals("film", File(source, "film.mkv").readText())
        assertEquals("keep", File(destination, "Movies/keep").readText())
        assertFalse(File(destination, "Movies (1)").exists())
    }

    @Test
    fun `move refuses nested and directly addressed symbolic links`() {
        val roots = setUp()
        val destination = File(root, "Destination").apply { mkdir() }
        val source = File(root, "Movies")
        val link = File(source, "alias")
        Files.createSymbolicLink(link.toPath(), File(root, ".hidden").toPath())

        assertFalse(TvFiles.move(roots, listOf(source.path), destination.path).getBoolean("ok"))
        assertEquals("bad_path", TvFiles.move(roots, listOf(link.path), destination.path).getString("error"))
        assertEquals("h", File(root, ".hidden").readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(destination.list().orEmpty().isEmpty())
    }
}
