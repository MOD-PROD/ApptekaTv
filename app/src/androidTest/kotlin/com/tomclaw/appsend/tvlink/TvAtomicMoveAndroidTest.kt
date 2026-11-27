package com.tomclaw.appsend.tvlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class TvAtomicMoveAndroidTest {
    @Test
    fun movesTheOpenFileWithoutReplacingAnotherFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        checkMove(context.cacheDir)
    }

    @Test
    fun movesWithinEmulatedExternalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        checkMove(checkNotNull(context.getExternalFilesDir(null)))
    }

    private fun checkMove(parent: File) {
        val root = File.createTempFile("atomic-move-", null, parent).canonicalFile
        check(root.delete() && root.mkdir())
        try {
            val destination = File(root, "destination").apply { check(mkdir()) }
            val existing = File(destination, "écran 📺.txt").apply { writeText("existing") }
            val source = File(root, existing.name).apply { writeText("before") }
            FileOutputStream(source, true).use { writer ->
                val result = TvFiles.move(listOf(root), listOf(source.path), destination.path)
                assertTrue(result.toString(), result.getBoolean("ok"))
                writer.write("-after".toByteArray())
            }
            assertEquals("existing", existing.readText())
            assertEquals("before-after", File(destination, "écran 📺 (1).txt").readText())
            assertFalse(source.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
