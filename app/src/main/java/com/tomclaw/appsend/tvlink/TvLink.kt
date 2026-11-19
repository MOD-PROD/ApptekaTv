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

    fun request(address: Pairing, message: JSONObject): JSONObject {
        val factory = TvTls.client(address.pin).socketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.connect(InetSocketAddress(address.host, address.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            TvFrames.write(socket.outputStream, message.put("v", 2).toString())
            return JSONObject(TvFrames.read(socket.inputStream)).also { require(it.optInt("v") == 2) }
        }
    }

    data class Pairing(val host: String, val port: Int, val tv: String, val secret: String, val pin: String) {
        companion object {
            fun parse(raw: String): Pairing {
                require(raw.length in 1..1024)
                val json = JSONObject(raw)
                require(json.optInt("v") == 2)
                val host = json.getString("host")
                val octets = host.split('.').map { it.toIntOrNull() ?: -1 }
                require(octets.size == 4 && octets.all { it in 0..255 })
                require(octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168)
                val port = json.getInt("port")
                require(port in 1..65535)
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

        @Volatile
        var port: Int = 0
            private set

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
        }

        fun pairedDevices(): List<Map<String, String>> =
            tokens().map {
                mapOf("id" to it.substringBefore(':'), "name" to it.substringAfter(':').substringAfter(':'))
            }.sortedBy { it["name"] }

        @Synchronized
        fun forget(id: String) {
            prefs.edit().putStringSet(KEY_TOKENS, tokens().filterNot { it.substringBefore(':') == id }.toSet()).apply()
        }

        @Synchronized
        fun pairingQr(): PairingQr? {
            val address = localIpv4() ?: return null
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
            client.use {
                try {
                    it.soTimeout = TIMEOUT_MS
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    val msg = JSONObject(TvFrames.read(input))
                    val response = if (msg.optInt("v") == 2 && msg.optString("type") == "code_start") {
                        val attempt = synchronized(this) { pairingWindow.beginCode() }
                        if (attempt == null) error("code_unavailable") else {
                            val first = com.google.gson.JsonParser.parseString(msg.getJSONObject("payload").toString()).asJsonObject
                            val exchange = TvCodeExchange(input, output)
                            exchange.tv(TvCodeAgreement(TvCodeAgreement.Side.TV, attempt.code, pin), first)
                            synchronized(this) {
                                if (pairingWindow.finishCode(attempt)) issueToken(msg) else error("expired")
                            }
                        }
                    } else respond(msg)
                    TvFrames.write(output, response.put("v", 2).toString())
                } catch (_: Exception) {
                    // Malformed or incomplete connections are closed without logging their contents.
                }
            }
        }

        private fun randomHex(): String = ByteArray(32).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }

        private fun tokens() = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()

        private fun tokenDevice(token: String): String? {
            val hash = TvFrames.tokenHash(token)?.toByteArray() ?: return null
            return tokens().firstOrNull {
                val storedHash = it.substringAfter(':').substringBefore(':')
                TvFrames.matches(storedHash.toByteArray(), hash)
            }?.substringAfter(':')?.substringAfter(':')
        }

        @Synchronized
        private fun respond(msg: JSONObject): JSONObject {
            if (msg.optInt("v") != 2) return error("version")
            val type = msg.optString("type")
            if (type == "hello") return ok().put("name", deviceName()).put("tv", tvId)
                .put("abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
            if (type == "pair") {
                when (pairingWindow.verify(msg.optString("secret"))) {
                    TvPairingWindow.Result.EXPIRED -> return error("expired")
                    TvPairingWindow.Result.INVALID -> return error("bad_secret")
                    TvPairingWindow.Result.LOCKED -> return error("too_many_attempts")
                    TvPairingWindow.Result.ACCEPTED -> Unit
                }
                return issueToken(msg)
            }
            val from = tokenDevice(msg.optString("token")) ?: return error("unauthorized")
            return when (type) {
                "ping" -> ok()
                "open", "install" -> {
                    val appId = msg.optString("appId")
                    if (!appId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) return error("bad_request")
                    main.post { host.onOpen(appId, type == "install", from) }
                    ok()
                }
                "status", "list_apps", "launch", "uninstall", "account_link" -> error("not_implemented")
                else -> error("unknown")
            }
        }

        private fun issueToken(msg: JSONObject): JSONObject {
            val device = msg.optString("device").take(40).ifBlank { "?" }
            val token = randomHex()
            val hash = checkNotNull(TvFrames.tokenHash(token))
            val entry = "${UUID.randomUUID()}:$hash:$device"
            if (!prefs.edit().putStringSet(KEY_TOKENS, tokens() + entry).commit()) return error("storage")
            main.post { host.onPaired(device) }
            return ok().put("token", token).put("tv", tvId)
        }

        private fun ok() = JSONObject().put("ok", true)
        private fun error(reason: String) = JSONObject().put("ok", false).put("error", reason)

        companion object {
            private const val KEY_TOKENS = "tokens"
        }
    }
}
