package com.tomclaw.appsend.tvlink

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal object TvTls {
    // ATV-01 durcissement : clé EC P-256. Conscrypt signe avec les clés AndroidKeyStore
    // par des opérations brutes (NONEwithECDSA) : DIGEST_NONE est indispensable, sinon la
    // poignée de main TLS échoue sur l'appareil. L'ancienne clé RSA (sans DIGEST_NONE) est supprimée.
    private const val ALIAS = "appteka-tv-link-v2-ec"
    private const val LEGACY_RSA_ALIAS = "appteka-tv-link-v2"

    @Synchronized
    fun server(): Pair<SSLContext, String> {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(LEGACY_RSA_ALIAS)) keys.deleteEntry(LEGACY_RSA_ALIAS)
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Appteka TV"))
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
        return clientContext(expected)
    }

    /** Only for the J-PAKE exchange. The peer fingerprint must be authenticated by J-PAKE. */
    fun codeHandshakeContext(): SSLContext = clientContext(null)

    private fun clientContext(expected: ByteArray?): SSLContext {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are unsupported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.size != 1 || (expected != null && !TvFrames.matches(expected, TvFrames.digest(chain[0].encoded)))) {
                    throw CertificateException("TV certificate does not match the pairing QR")
                }
                chain[0].checkValidity()
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    }
}
