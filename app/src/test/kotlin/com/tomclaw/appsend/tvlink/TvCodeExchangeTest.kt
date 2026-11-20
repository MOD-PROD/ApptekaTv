package com.tomclaw.appsend.tvlink

import com.google.gson.JsonParser
import org.bouncycastle.crypto.CryptoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvCodeExchangeTest {
    @Test
    fun bothSidesConfirmOverFramedStreams() = exchange("K7Q9T2WX", true)

    @Test
    fun incorrectCodeCannotCompleteTheExchange() = exchange("K7Q9T2WY", false)

    private fun exchange(phoneCode: String, accepted: Boolean) {
        val pin = "a".repeat(64)
        val tvInput = PipedInputStream(65_536)
        val phoneInput = PipedInputStream(65_536)
        val phoneOutput = PipedOutputStream(tvInput)
        val tvOutput = PipedOutputStream(phoneInput)
        val worker = Executors.newFixedThreadPool(2)
        try {
            val tv = worker.submit<Result<Unit>> {
                tvInput.use {
                    tvOutput.use {
                        runCatching {
                            val first = JsonParser.parseString(TvFrames.read(tvInput)).asJsonObject
                            assertEquals("code_start", first.get("type").asString)
                            TvCodeExchange(tvInput, tvOutput).tv(
                                TvCodeAgreement(TvCodeAgreement.Side.TV, "K7Q9T2WX", pin),
                                first.getAsJsonObject("payload"),
                            )
                        }
                    }
                }
            }
            val phone = worker.submit<Result<Unit>> {
                phoneInput.use {
                    phoneOutput.use {
                        runCatching {
                            TvCodeExchange(phoneInput, phoneOutput).phone(
                                TvCodeAgreement(TvCodeAgreement.Side.PHONE, phoneCode, pin),
                            )
                        }
                    }
                }
            }
            val tvResult = tv.get(15, TimeUnit.SECONDS)
            val phoneResult = phone.get(15, TimeUnit.SECONDS)
            if (accepted) {
                tvResult.getOrThrow()
                phoneResult.getOrThrow()
            } else {
                assertTrue(tvResult.exceptionOrNull() is CryptoException)
                assertTrue(phoneResult.exceptionOrNull() is IOException)
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
