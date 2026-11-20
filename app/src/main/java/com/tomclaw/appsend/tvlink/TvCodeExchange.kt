package com.tomclaw.appsend.tvlink

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.OutputStream

/** J-PAKE over one TLS connection, before either endpoint accepts an association. */
internal class TvCodeExchange(private val source: InputStream, private val destination: OutputStream) {
    fun phone(agreement: TvCodeAgreement, deviceName: String = "") {
        send("code_start", TvCodePayloads.encode(agreement.start()), deviceName)
        val serverFirst = receive("code_first")
        val serverSecond = receive("code_second")
        val second = agreement.exchange(TvCodePayloads.round1(serverFirst, TvCodeAgreement.Side.TV))
        val proof = agreement.confirmation(TvCodePayloads.round2(serverSecond, TvCodeAgreement.Side.TV))
        send("code_second", TvCodePayloads.encode(second))
        send("code_proof", TvCodePayloads.encode(proof))
        agreement.verify(TvCodePayloads.round3(receive("code_proof"), TvCodeAgreement.Side.TV))
    }

    fun tv(agreement: TvCodeAgreement, phoneFirst: JsonObject) {
        val first = agreement.start()
        val second = agreement.exchange(TvCodePayloads.round1(phoneFirst, TvCodeAgreement.Side.PHONE))
        send("code_first", TvCodePayloads.encode(first))
        send("code_second", TvCodePayloads.encode(second))
        val proof = agreement.confirmation(TvCodePayloads.round2(receive("code_second"), TvCodeAgreement.Side.PHONE))
        agreement.verify(TvCodePayloads.round3(receive("code_proof"), TvCodeAgreement.Side.PHONE))
        send("code_proof", TvCodePayloads.encode(proof))
    }

    private fun send(type: String, payload: JsonObject, deviceName: String? = null) {
        val frame = JsonObject().apply {
            addProperty("v", 2)
            addProperty("type", type)
            add("payload", payload)
            deviceName?.let { addProperty("device", it) }
        }
        TvFrames.write(destination, frame.toString())
    }

    private fun receive(type: String): JsonObject {
        val frame = JsonParser.parseString(TvFrames.read(source)).asJsonObject
        require(frame.get("v")?.asInt == 2 && frame.get("type")?.asString == type)
        return requireNotNull(frame.getAsJsonObject("payload"))
    }
}
