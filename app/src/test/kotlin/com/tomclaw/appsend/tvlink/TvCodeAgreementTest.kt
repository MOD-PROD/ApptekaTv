package com.tomclaw.appsend.tvlink

import org.bouncycastle.crypto.CryptoException
import org.junit.Test

class TvCodeAgreementTest {
    private val pin = "a".repeat(64)
    private val code = "K7Q9T2WX"

    private fun confirm(phoneCode: String, phonePin: String) {
        val tv = TvCodeAgreement(TvCodeAgreement.Side.TV, code, pin)
        val phone = TvCodeAgreement(TvCodeAgreement.Side.PHONE, phoneCode, phonePin)
        val tv1 = tv.start()
        val phone1 = phone.start()
        val tv2 = tv.exchange(TvCodePayloads.round1(TvCodePayloads.encode(phone1), TvCodeAgreement.Side.PHONE))
        val phone2 = phone.exchange(TvCodePayloads.round1(TvCodePayloads.encode(tv1), TvCodeAgreement.Side.TV))
        val tv3 = tv.confirmation(TvCodePayloads.round2(TvCodePayloads.encode(phone2), TvCodeAgreement.Side.PHONE))
        val phone3 = phone.confirmation(TvCodePayloads.round2(TvCodePayloads.encode(tv2), TvCodeAgreement.Side.TV))
        phone.verify(TvCodePayloads.round3(TvCodePayloads.encode(tv3), TvCodeAgreement.Side.TV))
        tv.verify(TvCodePayloads.round3(TvCodePayloads.encode(phone3), TvCodeAgreement.Side.PHONE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedGroupElement() {
        val tv = TvCodeAgreement(TvCodeAgreement.Side.TV, code, pin)
        val payload = TvCodePayloads.encode(tv.start()).apply { addProperty("gx1", "a".repeat(769)) }
        TvCodePayloads.round1(payload, TvCodeAgreement.Side.TV)
    }

    @Test fun confirmsMatchingCodeAndCertificate() = confirm(code, pin)

    @Test(expected = CryptoException::class)
    fun rejectsDifferentCode() = confirm("K7Q9T2WY", pin)

    @Test(expected = CryptoException::class)
    fun rejectsDifferentCertificate() = confirm(code, "b".repeat(64))

    @Test(expected = CryptoException::class)
    fun rejectsTamperedKnowledgeProof() {
        val tv = TvCodeAgreement(TvCodeAgreement.Side.TV, code, pin)
        val phone = TvCodeAgreement(TvCodeAgreement.Side.PHONE, code, pin)
        val payload = TvCodePayloads.encode(phone.start())
        val proof = payload.getAsJsonArray("proof1")
        val r = java.math.BigInteger(proof.get(1).asString, 16).add(java.math.BigInteger.ONE)
        proof.set(1, com.google.gson.JsonPrimitive(r.toString(16)))
        tv.exchange(TvCodePayloads.round1(payload, TvCodeAgreement.Side.PHONE))
    }

    @Test(expected = CryptoException::class)
    fun rejectsIdentityGroupElement() {
        val tv = TvCodeAgreement(TvCodeAgreement.Side.TV, code, pin)
        val phone = TvCodeAgreement(TvCodeAgreement.Side.PHONE, code, pin)
        val payload = TvCodePayloads.encode(phone.start()).apply { addProperty("gx2", "1") }
        tv.exchange(TvCodePayloads.round1(payload, TvCodeAgreement.Side.PHONE))
    }
}
