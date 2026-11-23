package com.tomclaw.appsend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ReleaseUpdateTest {

    private val validHash = "a".repeat(64)
    private val validUrl = "https://github.com/MOD-PROD/ApptekaTv/releases/download/v1200/apptekatv-release.apk"

    private fun json(
        packageName: String = "app.modprod.apptekatv",
        channel: String = "stable",
        versionCode: Long = 1200,
        versionName: String = "24.1",
        url: String = validUrl,
        sha256: String = validHash,
        size: Long = 3_000_000
    ): String = """
        {
          "packageName": "$packageName",
          "channel": "$channel",
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "url": "$url",
          "sha256": "$sha256",
          "size": $size
        }
    """.trimIndent()

    @Test
    fun `parses a valid newer update`() {
        val update = ReleaseUpdate.parse(json(), installedVersion = 1195)
        assertNotNull(update)
        assertEquals(1200L, update!!.versionCode)
        assertEquals("24.1", update.versionName)
        assertEquals(validUrl, update.url)
        assertEquals(validHash, update.sha256)
        assertEquals(3_000_000L, update.size)
    }

    @Test
    fun `returns null when versionCode is not greater`() {
        assertNull(ReleaseUpdate.parse(json(versionCode = 1195), 1195))
        assertNull(ReleaseUpdate.parse(json(versionCode = 1190), 1195))
    }

    @Test
    fun `rejects wrong packageName`() {
        assertThrows { ReleaseUpdate.parse(json(packageName = "com.evil.apk"), 1195) }
    }

    @Test
    fun `rejects non-stable channel`() {
        assertThrows { ReleaseUpdate.parse(json(channel = "beta"), 1195) }
    }

    @Test
    fun `rejects non-https or non-github url`() {
        assertThrows {
            ReleaseUpdate.parse(json(url = "http://github.com/MOD-PROD/ApptekaTv/releases/download/v1200/apptekatv-release.apk"), 1195)
        }
        assertThrows {
            ReleaseUpdate.parse(json(url = "https://evil.com/MOD-PROD/ApptekaTv/releases/download/v1200/apptekatv-release.apk"), 1195)
        }
        assertThrows {
            ReleaseUpdate.parse(json(url = "https://github.com/OTHER/ApptekaTv/releases/download/v1200/apptekatv-release.apk"), 1195)
        }
        assertThrows {
            ReleaseUpdate.parse(json(url = "https://github.com/MOD-PROD/ApptekaTv/releases/download/v1200/other.apk"), 1195)
        }
    }

    @Test
    fun `rejects invalid sha256`() {
        assertThrows { ReleaseUpdate.parse(json(sha256 = "deadbeef"), 1195) }
        assertThrows { ReleaseUpdate.parse(json(sha256 = "g".repeat(64)), 1195) }
    }

    @Test
    fun `rejects zero or oversized size`() {
        assertThrows { ReleaseUpdate.parse(json(size = 0), 1195) }
        assertThrows { ReleaseUpdate.parse(json(size = 536_870_913L), 1195) }
    }

    @Test
    fun `rejects blank versionName`() {
        assertThrows { ReleaseUpdate.parse(json(versionName = "  "), 1195) }
        assertThrows { ReleaseUpdate.parse(json(versionName = ""), 1195) }
    }

    @Test
    fun `normalizes hash to lowercase`() {
        val upper = "A".repeat(64)
        val update = ReleaseUpdate.parse(json(sha256 = upper), 1195)!!
        assertEquals(upper.lowercase(), update.sha256)
    }

    @Test
    fun `simulator manifest is isolated from the Appteka TV channels`() {
        val simUrl = "https://github.com/MOD-PROD/ApptekaTv/releases/download/v2200-simultv/atksimultv-release.apk"
        fun sim(json: String) = ReleaseUpdate.parse(json, 1195, ReleaseUpdate.SIMULTV_CHANNEL,
            ReleaseUpdate.SIMULTV_PACKAGE, ReleaseUpdate.SIMULTV_APK_NAME)
        val update = sim(json(packageName = ReleaseUpdate.SIMULTV_PACKAGE, channel = "simultv", url = simUrl))
        assertEquals(simUrl, update!!.url)
        // An Appteka TV manifest (other package, channel or APK) is never accepted by ATKsimulTv.
        assertThrows { sim(json(channel = "simultv", url = simUrl)) }
        assertThrows { sim(json(packageName = ReleaseUpdate.SIMULTV_PACKAGE, channel = "test", url = simUrl)) }
        assertThrows { sim(json(packageName = ReleaseUpdate.SIMULTV_PACKAGE, channel = "simultv")) }
        // And the Appteka TV parser never accepts the simulator manifest.
        assertThrows { ReleaseUpdate.parse(json(packageName = ReleaseUpdate.SIMULTV_PACKAGE, channel = "stable", url = simUrl), 1195) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("Expected exception")
        } catch (_: IllegalArgumentException) {
            // expected from require()
        } catch (_: org.json.JSONException) {
            // expected for malformed
        }
    }
}
