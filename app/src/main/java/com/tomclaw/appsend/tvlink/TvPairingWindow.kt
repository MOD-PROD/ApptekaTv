package com.tomclaw.appsend.tvlink

import java.util.UUID

/** One invitation per visible pairing dialog. Calls are serialized by the TV server. */
internal class TvPairingWindow(
    private val now: () -> Long,
    private val ttlMs: Long = 5 * 60 * 1000L,
    private val maxFailures: Int = 5,
) {
    enum class Result { ACCEPTED, INVALID, EXPIRED, LOCKED }

    private data class Invitation(
        val id: UUID,
        val secret: ByteArray,
        val deadline: Long,
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

    fun close(id: UUID) {
        if (current?.id == id) current = null
    }

    fun beginCode(): CodeAttempt? {
        val invitation = current ?: return null
        val code = invitation.code ?: return null
        if (now() >= invitation.deadline || invitation.codeAttempts >= maxFailures) return null
        invitation.codeAttempts++
        return CodeAttempt(invitation.id, code)
    }

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
