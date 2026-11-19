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
        var failures: Int = 0,
    )

    private var current: Invitation? = null

    fun open(secret: String): UUID {
        val id = UUID.randomUUID()
        current = Invitation(id, secret.toByteArray(Charsets.UTF_8), now() + ttlMs)
        return id
    }

    fun close(id: UUID) {
        if (current?.id == id) current = null
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
