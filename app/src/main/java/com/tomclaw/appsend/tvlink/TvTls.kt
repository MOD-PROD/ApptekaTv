package com.tomclaw.appsend.tvlink

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal object TvTls {
    private const val ALIAS = "appteka-tv-link-v2"

    @Synchronized
    fun server(): Pair<SSLContext, String> {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .build())
                generateKeyPair()
            }
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keys, null)
        val context = SSLContext.getInstance("TLS").apply { init(factory.keyManagers, null, null) }
        return context to TvFrames.digest(keys.getCertificate(ALIAS).encoded).joinToString("") { "%02x".format(it) }
    }

    fun client(pin: String): SSLContext {
        require(pin.matches(Regex("[0-9a-f]{64}")))
        val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are unsupported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.size != 1 || !TvFrames.matches(expected, TvFrames.digest(chain[0].encoded))) {
                    throw CertificateException("TV certificate does not match the pairing QR")
                }
                chain[0].checkValidity()
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    }
}
