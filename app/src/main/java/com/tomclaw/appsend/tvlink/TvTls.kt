package com.tomclaw.appsend.tvlink

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

internal object TvTls {
    private const val TAG = "TvTls"

    // ATV-01 durcissement : clé EC P-256. Conscrypt signe avec les clés AndroidKeyStore
    // par des opérations brutes (NONEwithECDSA) : DIGEST_NONE est indispensable, sinon la
    // poignée de main TLS échoue sur l'appareil. L'ancienne clé RSA (sans DIGEST_NONE) est supprimée.
    private const val ALIAS = "appteka-tv-link-v2-ec"
    private const val LEGACY_RSA_ALIAS = "appteka-tv-link-v2"

    // ATV (Gonzo 25/11, téléphone ↔ tablette « injoignable ») : sur certains téléphones récents,
    // la clé AndroidKeyStore fait échouer la poignée de main TLS côté serveur
    // (« SSLHandshakeException: connection closed »), alors qu'elle marche sur la box Android 9.
    // Au démarrage, on vérifie la clé par une poignée de main locale ; si elle échoue, on passe
    // définitivement à une clé EC logicielle, chiffrée par une clé AES du Keystore.
    private const val PREFS = "tv_tls_v1"
    private const val KEY_MODE = "mode"
    private const val MODE_SOFTWARE = "software"
    private const val KEY_PRIVATE = "key"
    private const val KEY_CERT = "cert"
    private const val KEY_WRAPPED = "wrapped"
    private const val WRAP_ALIAS = "appteka-tv-link-wrap"
    private const val SOFT_ALIAS = "tv"
    private const val SELF_CHECK_TIMEOUT_MS = 3000

    /** « keystore » ou « logicielle » : affiché par « Tester la connexion ». */
    @Volatile
    var mode: String = "keystore"
        private set

    @Synchronized
    fun server(context: Context): Pair<SSLContext, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_MODE, null) != MODE_SOFTWARE) {
            val keystore = runCatching { keystoreServer() }
                .onFailure { Log.w(TAG, "keystore TLS key unavailable", it) }.getOrNull()
            if (keystore != null && selfCheck(keystore.first)) {
                mode = "keystore"
                return keystore
            }
            Log.w(TAG, "keystore TLS key fails the local handshake: switching to a software key")
        }
        val software = softwareServer(context)
        prefs.edit().putString(KEY_MODE, MODE_SOFTWARE).apply()
        mode = MODE_SOFTWARE
        return software
    }

    private fun keystoreServer(): Pair<SSLContext, String> {
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
        return context to fingerprint(keys.getCertificate(ALIAS).encoded)
    }

    private fun fingerprint(certificate: ByteArray) =
        TvFrames.digest(certificate).joinToString("") { "%02x".format(it) }

    /**
     * Poignée de main TLS complète sur 127.0.0.1, dans un fil à part (pas de réseau sur le
     * fil principal). Vrai si le serveur sait signer avec sa clé.
     */
    private fun selfCheck(server: SSLContext): Boolean {
        var ok = false
        val worker = thread(name = "tv-tls-check", isDaemon = true) {
            ok = runCatching {
                val loopback = InetAddress.getByName("127.0.0.1")
                (server.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket).use { listener ->
                    listener.soTimeout = SELF_CHECK_TIMEOUT_MS
                    var serverOk = false
                    val side = thread(name = "tv-tls-check-server", isDaemon = true) {
                        serverOk = runCatching {
                            (listener.accept() as SSLSocket).use {
                                it.soTimeout = SELF_CHECK_TIMEOUT_MS
                                it.startHandshake()
                                it.outputStream.write(1)
                                it.outputStream.flush()
                            }
                        }.onFailure { Log.w(TAG, "local handshake (server side) failed", it) }.isSuccess
                    }
                    (codeHandshakeContext().socketFactory.createSocket() as SSLSocket).use {
                        it.connect(java.net.InetSocketAddress(loopback, listener.localPort), SELF_CHECK_TIMEOUT_MS)
                        it.soTimeout = SELF_CHECK_TIMEOUT_MS
                        it.startHandshake()
                        check(it.inputStream.read() == 1)
                    }
                    side.join(SELF_CHECK_TIMEOUT_MS.toLong())
                    serverOk
                }
            }.onFailure { Log.w(TAG, "local handshake failed", it) }.getOrDefault(false)
        }
        worker.join(TimeUnit.SECONDS.toMillis(8))
        return ok
    }

    // ------------------------------------------------------------ clé logicielle

    private fun softwareServer(context: Context): Pair<SSLContext, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = runCatching {
            val wrapped = prefs.getBoolean(KEY_WRAPPED, false)
            val key = prefs.getString(KEY_PRIVATE, null) ?: return@runCatching null
            val cert = prefs.getString(KEY_CERT, null) ?: return@runCatching null
            val keyBytes = Base64.decode(key, Base64.NO_WRAP).let { if (wrapped) unwrap(it) else it }
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            privateKey to parseCertificate(Base64.decode(cert, Base64.NO_WRAP))
        }.onFailure { Log.w(TAG, "stored software key unreadable: regenerating", it) }.getOrNull()
        val (privateKey, certificate) = stored ?: generateSoftware().also { (key, cert) ->
            val wrapped = runCatching { wrap(key.encoded) }
                .onFailure { Log.w(TAG, "Keystore AES unavailable: key kept in private app storage", it) }
                .getOrNull()
            check(prefs.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(wrapped ?: key.encoded, Base64.NO_WRAP))
                .putBoolean(KEY_WRAPPED, wrapped != null)
                .putString(KEY_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                .commit())
        }
        val context = SSLContext.getInstance("TLS")
            .apply { init(arrayOf<KeyManager>(SingleKeyManager(privateKey, certificate)), null, null) }
        return context to fingerprint(certificate.encoded)
    }

    private fun generateSoftware(): Pair<PrivateKey, X509Certificate> {
        val random = SecureRandom()
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()
        val now = System.currentTimeMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        val name = X500Name("CN=Appteka TV")
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(63, random).add(BigInteger.ONE)))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - day)))
            setEndDate(Time(Date(now + 20 * 365 * day)))
            setSignature(algorithm)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(tbs.getEncoded(ASN1Encoding.DER))
            sign()
        }
        val certificate = DERSequence(ASN1EncodableVector().apply {
            add(tbs)
            add(algorithm)
            add(DERBitString(signature))
        }).getEncoded(ASN1Encoding.DER)
        return pair.private to parseCertificate(certificate)
    }

    private fun parseCertificate(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    private fun wrapKey(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun unwrap(data: ByteArray): ByteArray {
        val ivSize = data[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, data, 1, ivSize))
        }
        return cipher.doFinal(data, 1 + ivSize, data.size - 1 - ivSize)
    }

    /** Une seule clé serveur, quel que soit l'algorithme demandé (TLS 1.2 ou 1.3). */
    private class SingleKeyManager(
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(SOFT_ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = SOFT_ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = SOFT_ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
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
