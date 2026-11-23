package com.tomclaw.appsend.tvlink

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPairingWindowTest {
    private var time = 100L
    private val window = TvPairingWindow(now = { time }, ttlMs = 300L, maxFailures = 2)

    @Test
    fun capsCodeAttemptsAndRejectsCompletionAfterDismissal() {
        val id = window.open("qr", "K7Q9T2WX")
        val first = checkNotNull(window.beginCode())
        checkNotNull(window.beginCode())
        assertEquals(null, window.beginCode())
        window.close(id)
        assertEquals(false, window.finishCode(first))
    }

    @Test
    fun qrAndCodeShareOneInvitation() {
        window.open("qr", "K7Q9T2WX")
        val attempt = checkNotNull(window.beginCode())
        assertEquals(true, window.finishCode(attempt))
        assertEquals(TvPairingWindow.Result.EXPIRED, window.verify("qr"))
    }

    @Test
    fun invitationIsConsumedOnSuccess() {
        window.open("secret")
        assertEquals(TvPairingWindow.Result.ACCEPTED, window.verify("secret"))
        assertEquals(TvPairingWindow.Result.EXPIRED, window.verify("secret"))
    }

    @Test
    fun rejectsAtExpiryBoundary() {
        window.open("secret")
        time = 400L
        assertEquals(TvPairingWindow.Result.EXPIRED, window.verify("secret"))
    }

    @Test
    fun locksAfterFailuresAndOpeningAgainClearsTheLock() {
        window.open("secret")
        assertEquals(TvPairingWindow.Result.INVALID, window.verify("wrong"))
        assertEquals(TvPairingWindow.Result.LOCKED, window.verify("wrong"))
        assertEquals(TvPairingWindow.Result.LOCKED, window.verify("secret"))
        window.open("new-secret")
        assertEquals(TvPairingWindow.Result.ACCEPTED, window.verify("new-secret"))
    }

    @Test
    fun dismissalOfOldDialogDoesNotCloseNewInvitation() {
        val old = window.open("old")
        val current = window.open("new")
        window.close(old)
        assertEquals(TvPairingWindow.Result.INVALID, window.verify("old"))
        window.close(current)
        assertEquals(TvPairingWindow.Result.EXPIRED, window.verify("new"))
    }

    @Test
    fun peekDoesNotConsumeAndCommitIsCapped() {
        window.open("qr", "K7Q9T2WX")
        repeat(5) { checkNotNull(window.peekCode()) }
        val attempt = checkNotNull(window.peekCode())
        assertEquals(true, window.commitCode(attempt))
        assertEquals(true, window.commitCode(attempt))
        assertEquals(false, window.commitCode(attempt))
        assertEquals(null, window.peekCode())
    }

    @Test
    fun dismissedQrStaysValidOnlyForTheGracePeriod() {
        val graced = TvPairingWindow(now = { time }, ttlMs = 300L, maxFailures = 2, graceAfterCloseMs = 50L)
        val id = graced.open("secret")
        graced.dismiss(id)
        time = 149L
        assertEquals(TvPairingWindow.Result.INVALID, graced.verify("wrong"))
        time = 150L
        assertEquals(TvPairingWindow.Result.EXPIRED, graced.verify("secret"))
    }

    @Test
    fun dismissNeverExtendsTheDeadline() {
        val graced = TvPairingWindow(now = { time }, ttlMs = 300L, maxFailures = 2, graceAfterCloseMs = 1_000L)
        val id = graced.open("secret")
        graced.dismiss(id)
        time = 400L
        assertEquals(TvPairingWindow.Result.EXPIRED, graced.verify("secret"))
    }
}
