package com.tomclaw.appsend.target

import com.tomclaw.appsend.download.AWAIT
import com.tomclaw.appsend.download.COMPLETED
import com.tomclaw.appsend.download.ERROR
import com.tomclaw.appsend.download.IDLE
import com.tomclaw.appsend.install.InstallStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteStateTest {

    private fun state(
        download: Int = IDLE,
        install: InstallStatus = InstallStatus.Idle,
        installed: Boolean = false,
        sawInstall: Boolean = false,
        failure: String? = null,
    ) = TvRemoteState.from(download, install, installed, sawInstall, failure)

    @Test
    fun `order received but nothing started is queued`() {
        assertEquals(TvRemoteState(TvRemoteState.QUEUED), state())
    }

    @Test
    fun `download progress is reported with percent`() {
        assertEquals(TvRemoteState(TvRemoteState.DOWNLOADING, 42), state(download = 42))
        assertEquals(TvRemoteState(TvRemoteState.DOWNLOADING), state(download = AWAIT))
    }

    @Test
    fun `completed download then confirmation then installed`() {
        assertEquals(TvRemoteState.INSTALLING, state(download = COMPLETED).state)
        assertEquals(TvRemoteState.CONFIRM, state(download = COMPLETED, install = InstallStatus.Confirming, sawInstall = true).state)
        val done = state(download = COMPLETED, installed = true, sawInstall = true)
        assertEquals(TvRemoteState.INSTALLED, done.state)
        assertTrue(done.isFinal)
    }

    @Test
    fun `install back to idle without package is a refusal`() {
        val refused = state(download = COMPLETED, sawInstall = true)
        assertEquals(TvRemoteState(TvRemoteState.FAILED, reason = TvRemoteState.REASON_INSTALL), refused)
        assertTrue(refused.isFinal)
    }

    @Test
    fun `download error and explicit failure`() {
        assertEquals(TvRemoteState.REASON_DOWNLOAD, state(download = ERROR).reason)
        assertEquals(TvRemoteState.REASON_INFO, state(failure = TvRemoteState.REASON_INFO).reason)
        assertFalse(state(download = 10).isFinal)
    }

    @Test
    fun `json round trip and defensive parsing`() {
        val original = TvRemoteState(TvRemoteState.DOWNLOADING, 77)
        assertEquals(original, TvRemoteState.parse(original.toJson()))
        assertNull(TvRemoteState.parse(JSONObject().put("state", "pwned")))
        assertNull(TvRemoteState.parse(null))
        val clamped = TvRemoteState.parse(JSONObject().put("state", "downloading").put("percent", 900))
        assertEquals(-1, clamped?.percent)
        val reason = TvRemoteState.parse(JSONObject().put("state", "failed").put("reason", "<script>x</script>"))
        assertEquals("scriptxscript", reason?.reason)
    }

    @Test
    fun `abi compatibility uses the tv abis`() {
        val tv = listOf("armeabi-v7a", "armeabi")
        assertTrue(TvRemoteState.abiCompatible(null, tv))
        assertTrue(TvRemoteState.abiCompatible(listOf("universal"), tv))
        assertTrue(TvRemoteState.abiCompatible(listOf("arm64-v8a", "armeabi-v7a"), tv))
        assertFalse(TvRemoteState.abiCompatible(listOf("arm64-v8a"), tv))
        assertTrue(TvRemoteState.abiCompatible(listOf("arm64-v8a"), emptyList()))
    }
}
