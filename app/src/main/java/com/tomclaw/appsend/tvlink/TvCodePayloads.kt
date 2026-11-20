package com.tomclaw.appsend.tvlink

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import java.math.BigInteger

/** Wire representation for J-PAKE messages carried in the bounded v2 frames. */
internal object TvCodePayloads {
    private const val GROUP_HEX_DIGITS = 768 // NIST 3072-bit group
    private val integerPattern = Regex("-?(0|[1-9a-f][0-9a-f]*)")

    fun encode(value: JPAKERound1Payload): JsonObject = packet(value.participantId).apply {
        addProperty("gx1", value.gx1.toString(16))
        addProperty("gx2", value.gx2.toString(16))
        add("proof1", proof(value.knowledgeProofForX1))
        add("proof2", proof(value.knowledgeProofForX2))
    }

    fun encode(value: JPAKERound2Payload): JsonObject = packet(value.participantId).apply {
        addProperty("a", value.a.toString(16))
        add("proof", proof(value.knowledgeProofForX2s))
    }

    fun encode(value: JPAKERound3Payload): JsonObject = packet(value.participantId).apply {
        addProperty("tag", value.macTag.toString(16))
    }

    fun round1(json: JsonObject, peer: TvCodeAgreement.Side): JPAKERound1Payload =
        JPAKERound1Payload(peerId(json, peer), group(json, "gx1"), group(json, "gx2"),
            parseProof(json, "proof1"), parseProof(json, "proof2"))

    fun round2(json: JsonObject, peer: TvCodeAgreement.Side): JPAKERound2Payload =
        JPAKERound2Payload(peerId(json, peer), group(json, "a"), parseProof(json, "proof"))

    fun round3(json: JsonObject, peer: TvCodeAgreement.Side): JPAKERound3Payload =
        JPAKERound3Payload(peerId(json, peer), integer(readString(json, "tag"), 64, signed = true))

    private fun packet(id: String) = JsonObject().apply { addProperty("participant", id) }

    private fun peerId(json: JsonObject, expected: TvCodeAgreement.Side): String =
        readString(json, "participant").also { require(it == expected.name) }

    private fun proof(parts: Array<BigInteger>) = JsonArray().apply {
        parts.forEach { add(it.toString(16)) }
    }

    private fun parseProof(json: JsonObject, field: String): Array<BigInteger> {
        val values = json.get(field)?.takeIf { it.isJsonArray }?.asJsonArray
        require(values != null && values.size() == 2)
        return Array(2) { index ->
            val value = values.get(index)
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            integer(value.asString, GROUP_HEX_DIGITS)
        }
    }

    private fun readString(json: JsonObject, field: String): String {
        val value = json.get(field)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun group(json: JsonObject, field: String): BigInteger =
        integer(readString(json, field), GROUP_HEX_DIGITS)

    private fun integer(raw: String, digits: Int, signed: Boolean = false): BigInteger {
        val negative = raw.startsWith('-')
        require(!negative || signed)
        require((raw.length - (if (negative) 1 else 0)) in 1..digits)
        require(integerPattern.matches(raw) && raw != "-0")
        return BigInteger(raw, 16)
    }
}
