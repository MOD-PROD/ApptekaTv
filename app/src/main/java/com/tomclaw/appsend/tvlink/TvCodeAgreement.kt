package com.tomclaw.appsend.tvlink

import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import java.math.BigInteger

/** A single J-PAKE exchange. Use the local TLS certificate on TV and the observed peer certificate on phone. */
internal class TvCodeAgreement(side: Side, code: String, tlsFingerprint: String) {
    enum class Side { TV, PHONE }

    private val participant: JPAKEParticipant
    private var material: BigInteger? = null

    init {
        require(code.matches(Regex("[A-Z2-9]{8}")))
        require(tlsFingerprint.matches(Regex("[0-9a-f]{64}")))
        val password = "appteka-tv-pair-v2:$code:$tlsFingerprint".toCharArray()
        try {
            participant = JPAKEParticipant(side.name, password)
        } finally {
            password.fill('\u0000')
        }
    }

    fun start(): JPAKERound1Payload = participant.createRound1PayloadToSend()

    fun exchange(peer: JPAKERound1Payload): JPAKERound2Payload {
        participant.validateRound1PayloadReceived(peer)
        return participant.createRound2PayloadToSend()
    }

    fun confirmation(peer: JPAKERound2Payload): JPAKERound3Payload {
        participant.validateRound2PayloadReceived(peer)
        val key = participant.calculateKeyingMaterial()
        val proof = participant.createRound3PayloadToSend(key)
        material = key
        return proof
    }

    /** Successful return is required on this side before proceeding with pairing. */
    fun verify(peer: JPAKERound3Payload) {
        val key = checkNotNull(material)
        material = null
        participant.validateRound3PayloadReceived(peer, key)
    }
}
