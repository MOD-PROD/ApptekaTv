package com.tomclaw.appsend.tvlink

import java.util.UUID

/** One invitation per visible pairing dialog. Calls are serialized by the TV server. */
internal class TvPairingWindow(
    private val now: () -> Long,
    private val ttlMs: Long = 5 * 60 * 1000L,
    private val maxFailures: Int = 5,
    /** ATV-01 durcissement : durée de validité restante une fois le QR fermé sur la TV. */
    private val graceAfterCloseMs: Long = 60 * 1000L,
) {
    enum class Result { ACCEPTED, INVALID, EXPIRED, LOCKED }

    private data class Invitation(
        val id: UUID,
        val secret: ByteArray,
        var deadline: Long,
        val code: String?,
        var failures: Int = 0,
        var codeAttempts: Int = 0,
    )

    private var current: Invitation? = null

    data class CodeAttempt(val id: UUID, val code: String)

    fun open(secret: String, code: String? = null): UUID {
        val id = UUID.randomUUID()
        current = Invitation(id, secret.toByteArray(Charsets.UTF_8), now() + ttlMs, code)
        return id
    }

    /** Hard close: the invitation is invalid immediately. */
    fun close(id: UUID) {
        if (current?.id == id) current = null
    }

    /**
     * The QR dialog was dismissed: keep the invitation briefly (a phone may be mid-scan,
     * see the 20/11 real-TV fix) but no longer than [graceAfterCloseMs].
     */
    fun dismiss(id: UUID) {
        val invitation = current ?: return
        if (invitation.id == id) invitation.deadline = minOf(invitation.deadline, now() + graceAfterCloseMs)
    }

    /** Returns the code to run J-PAKE with, without consuming an attempt. */
    fun peekCode(): CodeAttempt? {
        val invitation = current ?: return null
        val code = invitation.code ?: return null
        if (now() >= invitation.deadline || invitation.codeAttempts >= maxFailures) return null
        return CodeAttempt(invitation.id, code)
    }

    /**
     * Counts one attempt, once the phone's first J-PAKE message has passed validation
     * (well-formed group elements and zero-knowledge proofs). Garbage connections therefore
     * cannot lock the code. Returns false when the invitation changed, expired or is exhausted.
     */
    fun commitCode(attempt: CodeAttempt): Boolean {
        val invitation = current ?: return false
        if (invitation.id != attempt.id || now() >= invitation.deadline || invitation.codeAttempts >= maxFailures) {
            return false
        }
        invitation.codeAttempts++
        return true
    }

    /** Previous behaviour, kept for tests: peek and count in one step. */
    fun beginCode(): CodeAttempt? = peekCode()?.takeIf { commitCode(it) }

    fun finishCode(attempt: CodeAttempt): Boolean {
        val invitation = current ?: return false
        if (invitation.id != attempt.id || now() >= invitation.deadline) return false
        current = null
        return true
    }

    fun verify(secret: String): Result {
        val invitation = current ?: return Result.EXPIRED
        if (now() >= invitation.deadline) {
            current = null
            return Result.EXPIRED
        }
        if (invitation.failures >= maxFailures) return Result.LOCKED
        if (!TvFrames.matches(invitation.secret, secret.toByteArray(Charsets.UTF_8))) {
            invitation.failures++
            return if (invitation.failures >= maxFailures) Result.LOCKED else Result.INVALID
        }
        current = null
        return Result.ACCEPTED
    }
}
