package com.tomclaw.appsend.tvlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvTokensTest {
    private val install = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private fun hash(c: Char) = c.toString().repeat(64)

    @Test
    fun samePhoneInstallationReplacesItsPreviousToken() {
        val first = TvTokens.add(emptyList(), hash('a'), install, "Pixel", 1L)
        val second = TvTokens.add(first, hash('b'), install, "Pixel", 2L)
        assertEquals(1, second.size)
        assertNull(TvTokens.find(second, hash('a')))
        assertEquals("Pixel", TvTokens.find(second, hash('b'))?.device)
    }

    @Test
    fun differentOrUnknownInstallationsAreKept() {
        val other = "1f8fad5b-d9cb-469f-a165-70867728950e"
        var list = TvTokens.add(emptyList(), hash('a'), install, "A", 1L)
        list = TvTokens.add(list, hash('b'), other, "B", 2L)
        list = TvTokens.add(list, hash('c'), "", "C", 3L)
        list = TvTokens.add(list, hash('d'), "", "C", 4L)
        assertEquals(4, list.size)
    }

    @Test
    fun forgetRevokesTheOnlyTokenOfThatPhone() {
        val list = TvTokens.add(TvTokens.add(emptyList(), hash('a'), install, "Pixel", 1L), hash('b'), install, "Pixel", 2L)
        val after = TvTokens.remove(list, list.single().id)
        assertNull(TvTokens.find(after, hash('a')))
        assertNull(TvTokens.find(after, hash('b')))
    }

    @Test
    fun encodingKeepsColonsInDeviceNames() {
        val entry = TvTokens.add(emptyList(), hash('a'), install, "Box: salon", 42L).single()
        assertEquals(entry, TvTokens.decode(entry.encode()))
    }

    @Test
    fun legacyEntriesAreReadWithoutInstallation() {
        val legacy = TvTokens.decodeLegacy("id-1:${hash('a')}:Old: phone")
        assertEquals(TvTokens.Entry("id-1", hash('a'), "", 0L, "Old: phone"), legacy)
    }

    @Test
    fun rejectsMalformedValues() {
        assertNull(TvTokens.decode("id:short:$install:1:x"))
        assertNull(TvTokens.decode("id:${hash('a')}:not-an-install:1:x"))
        assertEquals("", TvTokens.normalizeInstall("x"))
        assertEquals(install, TvTokens.normalizeInstall(install.uppercase()))
        assertEquals("?", TvTokens.cleanDevice("\n\t "))
    }
}
