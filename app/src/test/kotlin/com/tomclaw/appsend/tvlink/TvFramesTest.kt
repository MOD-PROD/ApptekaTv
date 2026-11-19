package com.tomclaw.appsend.tvlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class TvFramesTest {
    @Test
    fun roundTripsMultipleUtf8Frames() {
        val bytes = ByteArrayOutputStream()
        TvFrames.write(bytes, """{"name":"Télévision"}""")
        TvFrames.write(bytes, """{"v":2}""")
        val input = ByteArrayInputStream(bytes.toByteArray())
        assertEquals("""{"name":"Télévision"}""", TvFrames.read(input))
        assertEquals("""{"v":2}""", TvFrames.read(input))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedFrameHeader() {
        TvFrames.read(ByteArrayInputStream(byteArrayOf(0, 4, 0, 1)))
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedFrame() {
        TvFrames.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 0)))
    }

    @Test
    fun validatesTokensAndComparesDigests() {
        val token = "a".repeat(64)
        val stored = checkNotNull(TvFrames.tokenHash(token)).toByteArray()
        assertTrue(TvFrames.matches(stored, checkNotNull(TvFrames.tokenHash(token)).toByteArray()))
        assertFalse(TvFrames.matches(stored, checkNotNull(TvFrames.tokenHash("b".repeat(64))).toByteArray()))
        assertNull(TvFrames.tokenHash(""))
        assertNull(TvFrames.tokenHash("z".repeat(64)))
    }
}
