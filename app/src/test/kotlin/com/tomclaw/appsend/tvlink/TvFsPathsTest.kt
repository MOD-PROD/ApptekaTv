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

class TvFsPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storage(): Pair<File, File> {
        val root = tmp.newFolder("sdcard").canonicalFile
        val outside = tmp.newFolder("private").canonicalFile
        File(root, "Movies").mkdirs()
        File(root, "Android/data/com.other").mkdirs()
        File(root, "Android/obb").mkdirs()
        File(root, "Android/media").mkdirs()
        File(outside, "token.txt").writeText("secret")
        return root to outside
    }

    @Test
    fun `root and children are allowed`() {
        val (root, _) = storage()
        assertEquals(root, TvFsPaths.resolve(listOf(root), root.path))
        assertEquals(File(root, "Movies"), TvFsPaths.resolve(listOf(root), root.path + "/Movies"))
        assertNotNull(TvFsPaths.resolve(listOf(root), root.path + "/Android/media"))
        // A file that does not exist yet (target of an upload) can still be resolved.
        assertNotNull(TvFsPaths.resolve(listOf(root), root.path + "/Movies/new.mkv"))
    }

    @Test
    fun `dot dot and paths outside roots are refused`() {
        val (root, outside) = storage()
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/../private/token.txt"))
        assertNull(TvFsPaths.resolve(listOf(root), outside.path + "/token.txt"))
        assertNull(TvFsPaths.resolve(listOf(root), "/"))
        assertNull(TvFsPaths.resolve(listOf(root), "Movies"))
        assertNull(TvFsPaths.resolve(listOf(root), ""))
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/a\u0000b"))
    }

    @Test
    fun `private folders of other apps are refused`() {
        val (root, _) = storage()
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/Android/data"))
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/Android/data/com.other"))
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/android/OBB"))
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/Android/obb/x.obb"))
    }

    @Test
    fun `symlink leaving the root is refused`() {
        val (root, outside) = storage()
        val link = File(root, "escape").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        assertNull(TvFsPaths.resolve(listOf(root), root.path + "/escape/token.txt"))
    }

    @Test
    fun `names cannot carry a path`() {
        assertTrue(TvFsPaths.validName("Film (2024).mkv"))
        assertFalse(TvFsPaths.validName("a/b"))
        assertFalse(TvFsPaths.validName("..\\b"))
        assertFalse(TvFsPaths.validName(".."))
        assertFalse(TvFsPaths.validName(""))
        assertFalse(TvFsPaths.validName("a\nb"))
        assertFalse(TvFsPaths.validName("x".repeat(256)))
    }

    @Test
    fun `free name adds a counter`() {
        val dir = tmp.newFolder("dir")
        File(dir, "a.txt").writeText("1")
        File(dir, "a (1).txt").writeText("2")
        assertEquals("b.txt", TvFsPaths.freeName(dir, "b.txt"))
        assertEquals("a (2).txt", TvFsPaths.freeName(dir, "a.txt"))
        File(dir, "noext").writeText("3")
        assertEquals("noext (1)", TvFsPaths.freeName(dir, "noext"))
    }
}
