package com.tomclaw.appsend.tvlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Liaison Appteka (portable) <-> Appteka TV sur le Wi-Fi local.
 *
 * Protocole volontairement minimal : une connexion TCP = une requête JSON sur une ligne,
 * une réponse JSON sur une ligne. La TV s'annonce en DNS-SD (« _apptekatv._tcp »)
 * pour que le portable la trouve sans adresse IP.
 *
 * Messages (champ "type") :
 *  - hello                         -> {ok, name, paired}
 *  - pair_request {device}         -> la TV affiche un code à 4 chiffres
 *  - pair_confirm {device, code}   -> {ok, token}
 *  - open    {token, appId}        -> la TV ouvre la fiche
 *  - install {token, appId}        -> la TV ouvre la fiche et lance l'installation
 */
object TvLink {
    const val SERVICE_TYPE = "_apptekatv._tcp."
    const val DEFAULT_PORT = 8336
    private const val TAG = "TvLink"
    private const val TIMEOUT_MS = 6000

    /** Envoie une requête et renvoie la réponse (à appeler hors du thread principal). */
    fun request(host: String, port: Int, message: JSONObject): JSONObject {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            out.write(message.toString())
            out.write("\n")
            out.flush()
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                ?: throw IllegalStateException("no response")
            return JSONObject(line)
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
        fun onPairCode(device: String, code: String)
        fun onPaired(device: String)
        fun onOpen(appId: String, install: Boolean, device: String)
    }

    class Server(private val context: Context, private val host: Host) {
        private val prefs = context.getSharedPreferences("tv_link", Context.MODE_PRIVATE)
        private val main = Handler(Looper.getMainLooper())
        private val pool = Executors.newCachedThreadPool()
        private val random = SecureRandom()
        private var socket: ServerSocket? = null
        private var nsd: NsdManager? = null
        private var registration: NsdManager.RegistrationListener? = null
        private var pending: Pending? = null

        @Volatile
        var port: Int = 0
            private set

        private class Pending(val device: String, val code: String, val expires: Long, var attempts: Int = 0)

        fun start() {
            if (socket != null) return
            val server = try {
                ServerSocket(DEFAULT_PORT)
            } catch (e: Exception) {
                ServerSocket(0) // port déjà pris : n'importe quel port libre (annoncé en DNS-SD)
            }
            socket = server
            port = server.localPort
            thread(name = "tv-link", isDaemon = true) {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    pool.execute { handle(client) }
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
        }

        fun pairedDevices(): List<String> =
            prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty().map { it.substringAfter(':') }.sorted()

        fun forgetAll() {
            prefs.edit().remove(KEY_TOKENS).apply()
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

        private fun handle(client: Socket) {
            client.use {
                try {
                    it.soTimeout = TIMEOUT_MS
                    val line = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8)).readLine()
                        ?: return
                    val response = respond(JSONObject(line))
                    val out = OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8)
                    out.write(response.toString())
                    out.write("\n")
                    out.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "bad request", e)
                }
            }
        }

        private fun tokenDevice(token: String?): String? {
            if (token.isNullOrBlank()) return null
            return prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()
                .firstOrNull { it.substringBefore(':') == token }
                ?.substringAfter(':')
        }

        @Synchronized
        private fun respond(msg: JSONObject): JSONObject {
            val device = msg.optString("device").take(40).ifBlank { "?" }
            return when (msg.optString("type")) {
                "hello" -> JSONObject()
                    .put("ok", true)
                    .put("name", deviceName())
                    .put("paired", tokenDevice(msg.optString("token")) != null)

                "pair_request" -> {
                    val code = String.format("%04d", random.nextInt(10000))
                    pending = Pending(device, code, System.currentTimeMillis() + PAIR_TTL_MS)
                    main.post { host.onPairCode(device, code) }
                    JSONObject().put("ok", true)
                }

                "pair_confirm" -> {
                    val p = pending
                    when {
                        p == null || System.currentTimeMillis() > p.expires ->
                            JSONObject().put("ok", false).put("error", "expired")
                        p.attempts >= MAX_ATTEMPTS ->
                            JSONObject().put("ok", false).put("error", "too_many")
                        msg.optString("code") != p.code -> {
                            p.attempts++
                            JSONObject().put("ok", false).put("error", "bad_code")
                        }
                        else -> {
                            pending = null
                            val token = ByteArray(16).also { random.nextBytes(it) }
                                .joinToString("") { b -> "%02x".format(b) }
                            val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()
                                .filterNot { it.substringAfter(':') == device }.toMutableSet()
                            tokens.add("$token:$device")
                            prefs.edit().putStringSet(KEY_TOKENS, tokens).apply()
                            main.post { host.onPaired(device) }
                            JSONObject().put("ok", true).put("token", token)
                        }
                    }
                }

                "open", "install" -> {
                    val from = tokenDevice(msg.optString("token"))
                    val appId = msg.optString("appId")
                    when {
                        from == null -> JSONObject().put("ok", false).put("error", "unauthorized")
                        appId.isBlank() || appId.length > 64 -> JSONObject().put("ok", false).put("error", "bad_request")
                        else -> {
                            val install = msg.optString("type") == "install"
                            main.post { host.onOpen(appId, install, from) }
                            JSONObject().put("ok", true)
                        }
                    }
                }

                else -> JSONObject().put("ok", false).put("error", "unknown")
            }
        }

        companion object {
            private const val KEY_TOKENS = "tokens"
            private const val PAIR_TTL_MS = 2 * 60 * 1000L
            private const val MAX_ATTEMPTS = 5
        }
    }

    // ------------------------------------------------------------- portable

    data class Target(val name: String, val host: String, val port: Int)

    /** Cherche les Appteka TV du réseau pendant [durationMs], puis rend la liste. */
    fun discover(context: Context, durationMs: Long, done: (List<Target>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            done(emptyList())
            return
        }
        val found = LinkedHashMap<String, Target>()
        val toResolve = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = toResolve.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress
                    main.post {
                        if (host != null) {
                            found[info.serviceName] = Target(
                                info.serviceName.removePrefix("Appteka TV · "), host, info.port
                            )
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { toResolve.addLast(info); resolveNext() }
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            done(emptyList())
            return
        }
        main.postDelayed({
            try {
                manager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
            done(found.values.toList())
        }, durationMs)
    }
}
