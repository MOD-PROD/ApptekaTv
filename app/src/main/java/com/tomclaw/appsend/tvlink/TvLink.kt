package com.tomclaw.appsend.tvlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.concurrent.thread
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

object TvLink {
    const val SERVICE_TYPE = "_apptekatv._tcp."
    const val DEFAULT_PORT = 8336
    private const val TAG = "TvLink"
    private const val TIMEOUT_MS = 6000
    /** ATV-01 durcissement : durée maximale d'une connexion entrante sur la TV. */
    private const val CONNECTION_DEADLINE_MS = 50_000L
    /** ATV-01 durcissement : temps laissé à l'utilisateur pour accepter l'association sur la TV. */
    private const val CONFIRM_TIMEOUT_MS = 30_000L
    /** Côté téléphone : attente de la réponse finale pendant la confirmation sur la TV. */
    private const val PAIR_RESPONSE_TIMEOUT_MS = 40_000

    /** ATV-01 durcissement : efface les préférences du protocole v1 (jetons en clair). */
    internal fun purgeLegacy(context: Context) {
        for (name in listOf("tv_link", "tv_link_client")) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.deleteSharedPreferences(name)
                else context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            } catch (_: Exception) {
            }
        }
    }

    fun request(address: Pairing, message: JSONObject): JSONObject {
        val factory = TvTls.client(address.pin).socketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.connect(InetSocketAddress(connectHost(address.host), address.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            if (message.optString("type") == "pair") socket.soTimeout = PAIR_RESPONSE_TIMEOUT_MS
            TvFrames.write(socket.outputStream, message.put("v", 2).toString())
            return JSONObject(TvFrames.read(socket.inputStream)).also { require(it.optInt("v") == 2) }
        }
    }

    internal fun pairByCode(host: String, port: Int, code: String, install: String = ""): Pair<Pairing, JSONObject> {
        validateLocalEndpoint(host, port)
        val factory = TvTls.codeHandshakeContext().socketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.connect(InetSocketAddress(connectHost(host), port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.single()
            val pin = TvFrames.digest(certificate.encoded).joinToString("") { "%02x".format(it) }
            val proof = TvCodeAgreement(TvCodeAgreement.Side.PHONE, code, pin)
            TvCodeExchange(socket.inputStream, socket.outputStream).phone(proof, deviceName(), install)
            socket.soTimeout = PAIR_RESPONSE_TIMEOUT_MS // the TV asks its user to confirm
            val response = JSONObject(TvFrames.read(socket.inputStream))
            check(response.optInt("v") == 2)
            check(response.optBoolean("ok")) { response.optString("error", "refused") }
            val tv = UUID.fromString(response.getString("tv")).toString()
            return Pairing(host, port, tv, "", pin) to response
        }
    }

    private fun validateLocalEndpoint(host: String, port: Int) {
        require(host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        val octets = host.split('.').map(String::toInt)
        require(octets.all { it in 0..255 })
        // ATV: 127.0.0.1 only reaches this same device (ATKsimulTv beside Appteka TV on one phone
        // without Wi-Fi); the TLS pin and the code still authenticate the TV.
        require(octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168 ||
            host == "127.0.0.1")
        require(port in 1..65535)
    }

    data class Pairing(val host: String, val port: Int, val tv: String, val secret: String, val pin: String) {
        companion object {
            fun parse(raw: String): Pairing {
                require(raw.length in 1..1024)
                val json = JSONObject(raw)
                require(json.optInt("v") == 2)
                val host = json.getString("host")
                val port = json.getInt("port")
                validateLocalEndpoint(host, port)
                val tv = UUID.fromString(json.getString("tv")).toString()
                val secret = json.getString("secret")
                val pin = json.getString("pin")
                require(secret.matches(Regex("[0-9a-f]{64}")) && pin.matches(Regex("[0-9a-f]{64}")))
                return Pairing(host, port, tv, secret, pin)
            }
        }
    }

    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    /**
     * ATV: when the "TV" is this same device (ATKsimulTv beside Appteka TV on one phone),
     * reach it through loopback: connecting to one's own Wi-Fi/mobile address can fail
     * (VPN, firewall). The TLS pin and the code authenticate the certificate, not the host.
     */
    private fun connectHost(host: String): String = try {
        val own = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
        if (host in own) "127.0.0.1" else host
    } catch (_: Exception) {
        host
    }

    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ TV

    /** Ce que la TV fait des demandes reçues (appelé sur le thread principal). */
    interface Host {
        fun onPaired(device: String)
        fun onOpen(appId: String, install: Boolean, device: String)

        /** ATV-03 : progression d'une installation demandée (appelé sur le thread de la liaison). */
        fun status(appId: String): JSONObject
    }

    class Server(private val context: Context, private val host: Host) {
        private val prefs = context.getSharedPreferences("tv_link_v2", Context.MODE_PRIVATE)
        private val main = Handler(Looper.getMainLooper())
        private val pool = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8))
        private val tvId = prefs.getString("tv_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("tv_id", it).apply()
        }
        private lateinit var pin: String
        private val random = SecureRandom()
        private var socket: ServerSocket? = null
        private var nsd: NsdManager? = null
        private var registration: NsdManager.RegistrationListener? = null
        private val pairingWindow = TvPairingWindow(SystemClock::elapsedRealtime)
        private val watchdog = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "tv-link-watchdog").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }

        init {
            purgeLegacy(context)
        }

        @Volatile
        var port: Int = 0
            private set

        /**
         * ATV-01 durcissement : demande à l'utilisateur de la TV d'accepter le téléphone
         * (appelé sur le thread principal ; répondre une seule fois). Sans confirmateur, refus.
         */
        @Volatile
        var confirmPairing: ((device: String, answer: (Boolean) -> Unit) -> Unit)? = null

        data class PairingQr(val sessionId: UUID, val payload: String, val code: String)

        fun start() {
            if (socket != null) return
            val (tls, fingerprint) = TvTls.server()
            pin = fingerprint
            val server = try {
                tls.serverSocketFactory.createServerSocket(DEFAULT_PORT)
            } catch (_: java.net.BindException) {
                tls.serverSocketFactory.createServerSocket(0)
            } as SSLServerSocket
            server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket = server
            port = server.localPort
            thread(name = "tv-link", isDaemon = true) {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    try {
                        pool.execute { handle(client) }
                    } catch (_: RejectedExecutionException) {
                        client.close()
                    }
                }
            }
            register()
        }

        fun stop() {
            try {
                registration?.let { nsd?.unregisterService(it) }
            } catch (_: Exception) {
            }
            registration = null
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            pool.shutdown()
            watchdog.shutdownNow()
        }

        fun pairedDevices(): List<Map<String, String>> =
            entries().map {
                mapOf("id" to it.id, "name" to it.device, "added" to it.created.toString())
            }.sortedBy { it["name"] }

        @Synchronized
        fun forget(id: String) {
            save(TvTokens.remove(entries(), id))
        }

        /** ATV-01 durcissement : révoque tous les téléphones associés. */
        @Synchronized
        fun forgetAll() {
            save(emptyList())
        }

        @Synchronized
        fun pairingQr(): PairingQr? {
            // ATV: ATKsimulTv falls back to loopback so a phone on mobile data can still pair
            // with Appteka TV installed on the same phone.
            val address = localIpv4()
                ?: (if (com.tomclaw.appsend.BuildConfig.FORCE_TV_SIMULATOR) "127.0.0.1" else return null)
            if (port == 0) return null
            val secret = randomHex()
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val code = buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val sessionId = pairingWindow.open(secret, code)
            val payload = JSONObject().put("v", 2).put("host", address).put("port", port)
                .put("tv", tvId).put("secret", secret).put("pin", pin).toString()
            return PairingQr(sessionId, payload, code)
        }

        @Synchronized
        fun closePairing(sessionId: UUID) {
            pairingWindow.close(sessionId)
        }

        /** Le QR a été fermé : l'invitation reste valable au plus 60 s (ATV-01 durcissement). */
        @Synchronized
        fun dismissPairing(sessionId: UUID) {
            pairingWindow.dismiss(sessionId)
        }

        private fun register() {
            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "Appteka TV · ${deviceName()}"
                serviceType = SERVICE_TYPE
                port = this@Server.port
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "NSD registration failed: $code")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            try {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                nsd = manager
                registration = listener
            } catch (e: Exception) {
                Log.w(TAG, "NSD unavailable", e)
            }
        }

        private fun handle(client: java.net.Socket) {
            // ATV-01 durcissement : clients du réseau local IPv4 uniquement, et durée bornée.
            val remote = client.inetAddress
            val simulatorLoopback = com.tomclaw.appsend.BuildConfig.FORCE_TV_SIMULATOR && remote.isLoopbackAddress
            if (remote !is Inet4Address || !(remote.isSiteLocalAddress || remote.isLinkLocalAddress || simulatorLoopback)) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
                return
            }
            val deadline = try {
                watchdog.schedule({ try { client.close() } catch (_: Exception) {} }, CONNECTION_DEADLINE_MS, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
            client.use {
                try {
                    it.soTimeout = TIMEOUT_MS
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    val msg = JSONObject(TvFrames.read(input))
                    val response = if (msg.optInt("v") == 2 && msg.optString("type") == "code_start") {
                        val attempt = synchronized(this) { pairingWindow.peekCode() }
                        if (attempt == null) error("code_unavailable") else {
                            val first = com.google.gson.JsonParser.parseString(msg.getJSONObject("payload").toString()).asJsonObject
                            val exchange = TvCodeExchange(input, output)
                            // The attempt only counts once the phone's J-PAKE round 1 is valid.
                            exchange.tv(TvCodeAgreement(TvCodeAgreement.Side.TV, attempt.code, pin), first) {
                                synchronized(this) { pairingWindow.commitCode(attempt) }
                            }
                            val consumed = synchronized(this) { pairingWindow.finishCode(attempt) }
                            if (consumed) confirmedToken(msg) else error("expired")
                        }
                    } else if (msg.optInt("v") == 2 && msg.optString("type") == "pair") {
                        val refused = synchronized(this) { checkSecret(msg) }
                        refused ?: confirmedToken(msg)
                    } else respond(msg)
                    TvFrames.write(output, response.put("v", 2).toString())
                } catch (_: Exception) {
                    // Malformed or incomplete connections are closed without logging their contents.
                } finally {
                    deadline?.cancel(false)
                }
            }
        }

        private fun randomHex(): String = ByteArray(32).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }

        /** Associations enregistrées ; migre au passage l'ancien format « id:hash:device ». */
        private fun entries(): List<TvTokens.Entry> {
            val current = prefs.getStringSet(KEY_ENTRIES, null)
            if (current != null) return current.mapNotNull(TvTokens::decode)
            val legacy = prefs.getStringSet(KEY_LEGACY_TOKENS, emptySet()).orEmpty().mapNotNull(TvTokens::decodeLegacy)
            return legacy
        }

        private fun save(entries: List<TvTokens.Entry>): Boolean =
            prefs.edit()
                .putStringSet(KEY_ENTRIES, entries.map { it.encode() }.toSet())
                .remove(KEY_LEGACY_TOKENS)
                .commit()

        private fun tokenDevice(token: String): String? =
            TvTokens.find(entries(), TvFrames.tokenHash(token))?.device

        @Synchronized
        private fun respond(msg: JSONObject): JSONObject {
            if (msg.optInt("v") != 2) return error("version")
            val type = msg.optString("type")
            if (type == "hello") return ok().put("name", deviceName()).put("tv", tvId)
                .put("abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
            val from = tokenDevice(msg.optString("token")) ?: return error("unauthorized")
            return when (type) {
                "ping" -> ok()
                "open", "install" -> {
                    val appId = msg.optString("appId")
                    if (!appId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) return error("bad_request")
                    main.post { host.onOpen(appId, type == "install", from) }
                    ok()
                }
                "status" -> {
                    val appId = msg.optString("appId")
                    if (!appId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) return error("bad_request")
                    ok().put("status", host.status(appId))
                }
                "list_apps", "launch", "uninstall", "account_link" -> error("not_implemented")
                else -> error("unknown")
            }
        }

        /** Consumes the QR invitation; returns an error response, or null when the secret is accepted. */
        private fun checkSecret(msg: JSONObject): JSONObject? {
            // A malformed secret cannot be the invitation: reject it without using up an attempt.
            if (!msg.optString("secret").matches(Regex("[0-9a-f]{64}"))) return error("bad_secret")
            return when (pairingWindow.verify(msg.optString("secret"))) {
                TvPairingWindow.Result.EXPIRED -> error("expired")
                TvPairingWindow.Result.INVALID -> error("bad_secret")
                TvPairingWindow.Result.LOCKED -> error("too_many_attempts")
                TvPairingWindow.Result.ACCEPTED -> null
            }
        }

        /**
         * The phone proved it knows the invitation; the TV user must still accept it before a
         * token exists. Runs outside the server lock so other requests are not blocked meanwhile.
         */
        private fun confirmedToken(msg: JSONObject): JSONObject {
            val device = TvTokens.cleanDevice(msg.optString("device"))
            val confirm = confirmPairing ?: return error("refused")
            val latch = java.util.concurrent.CountDownLatch(1)
            val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            main.post {
                confirm(device) { yes ->
                    if (answered.compareAndSet(false, true)) {
                        accepted.set(yes)
                        latch.countDown()
                    }
                }
            }
            val inTime = latch.await(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            answered.set(true) // a late answer is ignored
            if (!inTime || !accepted.get()) return error("refused")
            return synchronized(this) { issueToken(msg) }
        }

        private fun issueToken(msg: JSONObject): JSONObject {
            val device = TvTokens.cleanDevice(msg.optString("device"))
            val install = TvTokens.normalizeInstall(msg.optString("install"))
            val token = randomHex()
            val hash = checkNotNull(TvFrames.tokenHash(token))
            // Same phone installation: the new token replaces the old one (real revocation).
            if (!save(TvTokens.add(entries(), hash, install, device, System.currentTimeMillis()))) return error("storage")
            main.post { host.onPaired(device) }
            return ok().put("token", token).put("tv", tvId).put("name", deviceName())
        }

        private fun ok() = JSONObject().put("ok", true)
        private fun error(reason: String) = JSONObject().put("ok", false).put("error", reason)

        companion object {
            private const val KEY_LEGACY_TOKENS = "tokens"
            private const val KEY_ENTRIES = "tokens_v2"
        }
    }
}
