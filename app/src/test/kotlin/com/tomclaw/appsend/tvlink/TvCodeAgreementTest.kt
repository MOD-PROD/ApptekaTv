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
        val tv2 = tv.exchange(phone1)
        val phone2 = phone.exchange(tv1)
        val tv3 = tv.confirmation(phone2)
        val phone3 = phone.confirmation(tv2)
        phone.verify(tv3)
        tv.verify(phone3)
    }

    @Test fun confirmsMatchingCodeAndCertificate() = confirm(code, pin)

    @Test(expected = CryptoException::class)
    fun rejectsDifferentCode() = confirm("K7Q9T2WY", pin)

    @Test(expected = CryptoException::class)
    fun rejectsDifferentCertificate() = confirm(code, "b".repeat(64))
}
