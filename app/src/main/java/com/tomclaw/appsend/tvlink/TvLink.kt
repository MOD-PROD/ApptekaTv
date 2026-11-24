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
    /** ATV : taille maximale d'un APK échangé entre le téléphone et la TV. */
    const val MAX_APK_BYTES = 1536L * 1024 * 1024
    /** ATV : durée maximale d'un échange d'APK (connexion entrante sur la TV). */
    private const val STREAM_DEADLINE_MS = 15 * 60 * 1000L
    private const val PUSH_CHECK_TIMEOUT_MS = 60_000
    private const val DOWNLOAD_RESPONSE_TIMEOUT_MS = 45_000
    /** ATV-09 : un échange de fichier peut durer longtemps ; chaque lecture reste bornée par soTimeout. */
    private const val FS_STREAM_DEADLINE_MS = 3 * 60 * 60 * 1000L
    /** ATV-09 : `fs_access` peut attendre l'accord de l'utilisateur de la TV. */
    private const val FS_ACCESS_RESPONSE_TIMEOUT_MS = 40_000

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
        open(address).use { socket ->
            if (message.optString("type") == "pair") socket.soTimeout = PAIR_RESPONSE_TIMEOUT_MS
            // ATV (Downloaded en mode 📺) : la TV lit chaque archive avant de répondre.
            if (message.optString("type") in DOWNLOAD_TYPES) socket.soTimeout = DOWNLOAD_RESPONSE_TIMEOUT_MS
            // ATV-09 : accord sur la TV, gros dossiers, suppressions récursives.
            if (message.optString("type") == "fs_access") socket.soTimeout = FS_ACCESS_RESPONSE_TIMEOUT_MS
            else if (message.optString("type") in FS_TYPES) socket.soTimeout = DOWNLOAD_RESPONSE_TIMEOUT_MS
            TvFrames.write(socket.outputStream, message.put("v", 2).toString())
            return JSONObject(TvFrames.read(socket.inputStream)).also { require(it.optInt("v") == 2) }
        }
    }

    private fun open(address: Pairing): SSLSocket {
        val factory = TvTls.client(address.pin).socketFactory
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.connect(InetSocketAddress(connectHost(address.host), address.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }

    /**
     * ATV (Installées en mode 📺) : reçoit un APK de la TV (ordre `pull_apk`). La TV répond
     * par une trame JSON avec `size`, puis envoie exactement `size` octets bruts.
     * Renvoie la réponse JSON ; le fichier n'est complet que si `ok` vaut vrai.
     */
    fun pull(
        address: Pairing,
        message: JSONObject,
        sink: java.io.OutputStream,
        progress: (Long, Long) -> Unit,
        sizes: LongRange = 1..MAX_APK_BYTES,
    ): JSONObject {
        open(address).use { socket ->
            TvFrames.write(socket.outputStream, message.put("v", 2).toString())
            val header = JSONObject(TvFrames.read(socket.inputStream))
            require(header.optInt("v") == 2)
            if (!header.optBoolean("ok")) return header
            val size = header.getLong("size")
            require(size in sizes)
            copyExactly(socket.inputStream, sink, size, progress)
            sink.flush()
            return header
        }
    }

    /**
     * ATV (Installées en mode 📱) : envoie un APK à la TV (ordre `push_apk`). La TV accepte
     * d'abord la taille annoncée, reçoit les octets bruts, vérifie l'APK puis répond.
     */
    fun push(
        address: Pairing,
        message: JSONObject,
        source: java.io.InputStream,
        size: Long,
        progress: (Long, Long) -> Unit,
        sizes: LongRange = 1..MAX_APK_BYTES,
    ): JSONObject {
        require(size in sizes)
        open(address).use { socket ->
            TvFrames.write(socket.outputStream, message.put("size", size).put("v", 2).toString())
            val ready = JSONObject(TvFrames.read(socket.inputStream))
            require(ready.optInt("v") == 2)
            if (!ready.optBoolean("ok")) return ready
            copyExactly(source, socket.outputStream, size, progress)
            socket.outputStream.flush()
            socket.soTimeout = PUSH_CHECK_TIMEOUT_MS // the TV reads the archive before answering
            return JSONObject(TvFrames.read(socket.inputStream)).also { require(it.optInt("v") == 2) }
        }
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long, progress: (Long, Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var reported = 0L
        while (done < size) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - done).toInt())
            if (read < 0) throw java.io.EOFException("truncated")
            output.write(buffer, 0, read)
            done += read
            if (done - reported >= 256 * 1024 || done == size) {
                reported = done
                progress(done, size)
            }
        }
    }

    /** Nom d'un APK téléchargé (sans extension) : pas de chemin, longueur bornée. */
    fun validFileName(value: String): Boolean =
        value.length in 1..200 && !value.startsWith(".") && value.none { it == '/' || it == '\\' || it < ' ' }

    /** ATV-09 : ordres « Fichiers » traités hors du verrou de la liaison (hors flux). */
    internal val FS_TYPES = setOf("fs_access", "fs_list", "fs_mkdir", "fs_rename", "fs_delete", "fs_install")

    /** Ordres « Downloaded » : traités hors du verrou de la liaison (lecture des archives). */
    internal val DOWNLOAD_TYPES = setOf("list_downloads", "download_install", "download_delete", "download_permissions", "download_icon")

    /** Nom de paquet Android plausible (ordres sur une appli de la TV). */
    fun validPackage(value: String): Boolean =
        value.length in 3..255 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

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

        /** ATV : contenu de la TV (applis installées + mémoire) pour le téléphone en mode 📺. */
        fun content(): JSONObject = JSONObject().put("apps", org.json.JSONArray())

        /** ATV (Installées en mode 📺) : idem, avec les applis système si [system]. */
        fun content(system: Boolean): JSONObject = content()

        /**
         * ATV (Installées en mode 📺) : action du menu sur une appli de la TV
         * (`launch`, `uninstall`, `app_details`, `open_market`, `permissions`).
         * Appelé sur le thread de la liaison ; renvoie `ok` ou `error`.
         */
        fun appAction(type: String, packageName: String): JSONObject =
            JSONObject().put("ok", false).put("error", "not_implemented")

        /** Icône PNG (petite) d'une appli de la TV, ou null. */
        fun icon(packageName: String): ByteArray? = null

        /** Fichier APK (de base) d'une appli de la TV à envoyer au téléphone, ou null. */
        fun apkFile(packageName: String): java.io.File? = null

        /** Fichier où recevoir un APK de [size] octets envoyé par le téléphone, ou null (place). */
        fun incomingApk(size: Long): java.io.File? = null

        /** ATV (Downloaded en mode 📺) : APK téléchargés par la TV (`files`). */
        fun downloads(): JSONObject = JSONObject().put("files", org.json.JSONArray())

        /** `download_install`, `download_delete`, `download_permissions` sur un APK téléchargé par la TV. */
        fun downloadAction(type: String, fileName: String): JSONObject =
            JSONObject().put("ok", false).put("error", "not_implemented")

        /** Icône PNG d'un APK téléchargé par la TV, ou null. */
        fun downloadIcon(fileName: String): ByteArray? = null

        /** Fichier d'un APK téléchargé par la TV à envoyer au téléphone, ou null. */
        fun downloadFile(fileName: String): java.io.File? = null

        /**
         * ATV-09 : accès de l'appli au stockage de la TV : `full` (tout le stockage), ou `none`
         * (permission à accorder sur la TV).
         */
        fun fsAccessMode(): String = "none"

        /** ATV-09 : racines visibles par le téléphone (stockage interne, clés USB…). */
        fun fsRoots(): List<FsRoot> = emptyList()

        /** ATV-09 : demande sur la TV l'accès au stockage (thread principal). */
        fun fsRequestStorage() {}

        /** ATV-09 : un téléphone autorisé ouvre les fichiers de la TV (thread principal). */
        fun onFilesOpened(device: String) {}

        /** ATV-09 : installe l'APK [file] présent sur la TV (thread de la liaison). */
        fun fsInstall(file: java.io.File, device: String): JSONObject =
            JSONObject().put("ok", false).put("error", "not_implemented")

        /** APK reçu du téléphone [device] : vérifier puis lancer l'installation sur la TV. */
        fun onApkReceived(file: java.io.File, device: String): JSONObject {
            file.delete()
            return JSONObject().put("ok", false).put("error", "not_implemented")
        }
    }

    /** ATV-09 : racine de fichiers de la TV ([label] lisible, [removable] pour une clé USB / carte SD). */
    data class FsRoot(val file: java.io.File, val label: String, val removable: Boolean)

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

        /** ATV-09 : demande à l'utilisateur de la TV d'autoriser un téléphone à gérer les fichiers. */
        @Volatile
        var confirmFiles: ((device: String, answer: (Boolean) -> Unit) -> Unit)? = null

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

        fun pairedDevices(): List<Map<String, String>> {
            val grants = fsGrants()
            return entries().map {
                mapOf(
                    "id" to it.id, "name" to it.device, "added" to it.created.toString(),
                    "files" to (it.id in grants).toString(),
                )
            }.sortedBy { it["name"] }
        }

        /** ATV-09 : retire le droit « Fichiers » d'un téléphone associé. */
        @Synchronized
        fun revokeFiles(id: String) {
            prefs.edit().putStringSet(KEY_FS_GRANTS, fsGrants() - id).commit()
        }

        /** ATV-09 : journal des dernières opérations sur les fichiers (plus récente d'abord). */
        fun filesLog(): List<Map<String, Any>> = runCatching {
            val array = org.json.JSONArray(prefs.getString(KEY_FS_LOG, "[]"))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                mapOf("at" to item.optLong("at"), "device" to item.optString("device"),
                    "op" to item.optString("op"), "path" to item.optString("path"))
            }
        }.getOrDefault(emptyList())

        @Synchronized
        fun forget(id: String) {
            save(TvTokens.remove(entries(), id))
            prefs.edit().putStringSet(KEY_FS_GRANTS, fsGrants() - id).commit()
        }

        /** ATV-01 durcissement : révoque tous les téléphones associés. */
        @Synchronized
        fun forgetAll() {
            save(emptyList())
            prefs.edit().remove(KEY_FS_GRANTS).commit()
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
            var deadline = closeAfter(client, CONNECTION_DEADLINE_MS)
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
                    } else if (msg.optInt("v") == 2 && msg.optString("type") in STREAM_TYPES) {
                        // ATV : un APK peut être long à transférer ; chaque lecture reste bornée par soTimeout.
                        deadline?.cancel(false)
                        deadline = closeAfter(client, STREAM_DEADLINE_MS)
                        stream(msg, input, output)
                    } else if (msg.optInt("v") == 2 && msg.optString("type") in FS_STREAM_TYPES) {
                        deadline?.cancel(false)
                        deadline = closeAfter(client, FS_STREAM_DEADLINE_MS)
                        fsStream(msg, input, output)
                    } else if (msg.optInt("v") == 2 && msg.optString("type") in FS_TYPES) {
                        files(msg)
                    } else if (msg.optInt("v") == 2 && msg.optString("type") in DOWNLOAD_TYPES) {
                        downloads(msg)
                    } else respond(msg)
                    if (response != null) TvFrames.write(output, response.put("v", 2).toString())
                } catch (_: Exception) {
                    // Malformed or incomplete connections are closed without logging their contents.
                } finally {
                    deadline?.cancel(false)
                }
            }
        }

        private fun closeAfter(client: java.net.Socket, delayMs: Long) = try {
            watchdog.schedule({ try { client.close() } catch (_: Exception) {} }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }

        /**
         * ATV : échange d'APK. Renvoie la réponse à écrire, ou null quand la réponse
         * (en-tête + octets bruts) a déjà été envoyée.
         */
        private fun stream(msg: JSONObject, input: java.io.InputStream, output: java.io.OutputStream): JSONObject? {
            val from = synchronized(this) { tokenDevice(msg.optString("token")) } ?: return error("unauthorized")
            when (msg.optString("type")) {
                "pull_apk" -> {
                    val packageName = msg.optString("package")
                    if (!validPackage(packageName)) return error("bad_request")
                    val file = host.apkFile(packageName) ?: return error("not_found")
                    val size = file.length()
                    if (size !in 1..MAX_APK_BYTES) return error("too_big")
                    file.inputStream().use { source ->
                        TvFrames.write(output, ok().put("size", size).put("v", 2).toString())
                        copyExactly(source, output, size) { _, _ -> }
                        output.flush()
                    }
                    return null
                }
                "pull_download" -> {
                    val fileName = msg.optString("file")
                    if (!validFileName(fileName)) return error("bad_request")
                    val file = host.downloadFile(fileName) ?: return error("not_found")
                    val size = file.length()
                    if (size !in 1..MAX_APK_BYTES) return error("too_big")
                    file.inputStream().use { source ->
                        TvFrames.write(output, ok().put("size", size).put("v", 2).toString())
                        copyExactly(source, output, size) { _, _ -> }
                        output.flush()
                    }
                    return null
                }
                "push_apk" -> {
                    val size = msg.optLong("size")
                    if (size !in 1..MAX_APK_BYTES) return error("bad_request")
                    val target = host.incomingApk(size) ?: return error("storage")
                    try {
                        TvFrames.write(output, ok().put("v", 2).toString())
                        java.io.FileOutputStream(target).use { sink -> copyExactly(input, sink, size) { _, _ -> } }
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                    return host.onApkReceived(target, from)
                }
            }
            return error("unknown")
        }

        // ------------------------------------------------ ATV-09 : fichiers à distance

        private fun fsGrants(): Set<String> = prefs.getStringSet(KEY_FS_GRANTS, emptySet()).orEmpty().toSet()

        private fun tokenEntry(token: String): TvTokens.Entry? =
            TvTokens.find(entries(), TvFrames.tokenHash(token))

        private fun fsRootFiles(): List<java.io.File> = host.fsRoots().map { it.file }

        private fun log(device: String, op: String, path: String) = synchronized(this) {
            val previous = runCatching { org.json.JSONArray(prefs.getString(KEY_FS_LOG, "[]")) }.getOrNull() ?: org.json.JSONArray()
            val next = org.json.JSONArray().put(
                JSONObject().put("at", System.currentTimeMillis()).put("device", device).put("op", op).put("path", path.take(512))
            )
            for (i in 0 until minOf(previous.length(), FS_LOG_SIZE - 1)) next.put(previous.get(i))
            prefs.edit().putString(KEY_FS_LOG, next.toString()).apply()
        }

        /**
         * Téléphone associé ET autorisé à gérer les fichiers, stockage accessible : renvoie
         * l'entrée, sinon une réponse d'erreur.
         */
        private fun fsAuthorized(msg: JSONObject): Pair<TvTokens.Entry?, JSONObject?> {
            val entry = synchronized(this) { tokenEntry(msg.optString("token")) } ?: return null to error("unauthorized")
            if (entry.id !in synchronized(this) { fsGrants() }) return null to error("fs_forbidden")
            if (host.fsAccessMode() == "none") return null to error("fs_storage")
            return entry to null
        }

        private fun files(msg: JSONObject): JSONObject {
            val type = msg.optString("type")
            if (type == "fs_access") return fsAccess(msg)
            val (entry, refused) = fsAuthorized(msg)
            if (entry == null) return refused ?: error("unauthorized")
            val roots = fsRootFiles()
            val path = msg.optString("path")
            return when (type) {
                "fs_list" -> TvFiles.list(roots, path, msg.optBoolean("hidden"))
                "fs_mkdir" -> TvFiles.mkdir(roots, path, msg.optString("name")).also {
                    if (it.optBoolean("ok")) log(entry.device, "mkdir", it.optString("path"))
                }
                "fs_rename" -> TvFiles.rename(roots, path, msg.optString("name")).also {
                    if (it.optBoolean("ok")) log(entry.device, "rename", path + " → " + msg.optString("name"))
                }
                "fs_delete" -> {
                    val array = msg.optJSONArray("paths") ?: return error("bad_request")
                    if (array.length() !in 1..500) return error("bad_request")
                    val paths = (0 until array.length()).map { array.optString(it) }
                    TvFiles.delete(roots, paths).also { result ->
                        paths.take(20).forEach { log(entry.device, "delete", it) }
                        if (!result.optBoolean("ok")) Log.w(TAG, "fs_delete partial: ${result.optString("error")}")
                    }
                }
                "fs_install" -> {
                    val (file, problem) = TvFiles.readable(roots, path)
                    if (file == null) return problem ?: error("not_found")
                    if (!file.name.endsWith(".apk", ignoreCase = true) || file.length() !in 1..MAX_APK_BYTES) return error("not_apk")
                    log(entry.device, "install", path)
                    host.fsInstall(file, entry.device)
                }
                else -> error("unknown")
            }
        }

        /** Ouverture de l'écran « Fichiers » : accord une fois sur la TV, puis état du stockage. */
        private fun fsAccess(msg: JSONObject): JSONObject {
            val entry = synchronized(this) { tokenEntry(msg.optString("token")) } ?: return error("unauthorized")
            if (entry.id !in synchronized(this) { fsGrants() }) {
                if (!askTv(confirmFiles, entry.device)) return error("fs_refused")
                synchronized(this) {
                    // The phone may have been forgotten while the TV user was answering.
                    if (entries().none { it.id == entry.id }) return error("unauthorized")
                    prefs.edit().putStringSet(KEY_FS_GRANTS, fsGrants() + entry.id).commit()
                }
            }
            val mode = host.fsAccessMode()
            if (mode == "none") main.post { host.fsRequestStorage() } else main.post { host.onFilesOpened(entry.device) }
            val roots = org.json.JSONArray()
            if (mode != "none") host.fsRoots().forEach { root ->
                val dir = runCatching { root.file.canonicalFile }.getOrNull() ?: return@forEach
                roots.put(
                    JSONObject().put("path", dir.path).put("label", root.label).put("removable", root.removable)
                        .put("writable", dir.canWrite()).put("free", dir.usableSpace).put("total", dir.totalSpace)
                )
            }
            return ok().put("access", mode).put("roots", roots).put("sdk", Build.VERSION.SDK_INT)
        }

        /** Pose une question oui/non à l'utilisateur de la TV (30 s), hors du verrou. */
        private fun askTv(question: ((String, (Boolean) -> Unit) -> Unit)?, device: String): Boolean {
            val ask = question ?: return false
            val latch = java.util.concurrent.CountDownLatch(1)
            val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            main.post {
                ask(device) { yes ->
                    if (answered.compareAndSet(false, true)) {
                        accepted.set(yes)
                        latch.countDown()
                    }
                }
            }
            val inTime = latch.await(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            answered.set(true) // a late answer is ignored
            return inTime && accepted.get()
        }

        /** `fs_pull` (TV → téléphone) et `fs_push` (téléphone → TV) : en-tête JSON puis octets bruts. */
        private fun fsStream(msg: JSONObject, input: java.io.InputStream, output: java.io.OutputStream): JSONObject? {
            val (entry, refused) = fsAuthorized(msg)
            if (entry == null) return refused ?: error("unauthorized")
            val roots = fsRootFiles()
            when (msg.optString("type")) {
                "fs_pull" -> {
                    val (file, problem) = TvFiles.readable(roots, msg.optString("path"))
                    if (file == null) return problem ?: error("not_found")
                    val size = file.length()
                    log(entry.device, "pull", file.path)
                    file.inputStream().use { source ->
                        TvFrames.write(output, ok().put("size", size).put("name", file.name).put("v", 2).toString())
                        copyExactly(source, output, size) { _, _ -> }
                        output.flush()
                    }
                    return null
                }
                "fs_push" -> {
                    val size = msg.optLong("size", -1)
                    val (pair, problem) = TvFiles.incoming(roots, msg.optString("path"), msg.optString("name"), size)
                    val (part, target) = pair ?: return problem ?: error("bad_request")
                    try {
                        TvFrames.write(output, ok().put("v", 2).toString())
                        java.io.FileOutputStream(part).use { sink ->
                            copyExactly(input, sink, size) { _, _ -> }
                            sink.fd.sync()
                        }
                    } catch (e: Exception) {
                        part.delete()
                        throw e
                    }
                    return TvFiles.complete(part, target).also {
                        if (it.optBoolean("ok")) log(entry.device, "push", it.optString("path"))
                    }
                }
            }
            return error("unknown")
        }

        /** ATV (Downloaded en mode 📺) : hors du verrou, la TV lisant les archives (lent). */
        private fun downloads(msg: JSONObject): JSONObject {
            synchronized(this) { tokenDevice(msg.optString("token")) } ?: return error("unauthorized")
            val type = msg.optString("type")
            if (type == "list_downloads") return ok().put("content", host.downloads())
            val fileName = msg.optString("file")
            if (!validFileName(fileName)) return error("bad_request")
            if (type == "download_icon") {
                val png = host.downloadIcon(fileName) ?: return error("not_found")
                return ok().put("png", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
            }
            return host.downloadAction(type, fileName)
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
                .put("kind", com.tomclaw.appsend.target.DeviceKinds.detectLocal(context).id)
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
                "list_apps" -> ok().put("content", host.content(msg.optBoolean("system")))
                // ATV (Installées en mode 📺) : le menu natif agit sur l'appli de la TV.
                "launch", "uninstall", "app_details", "open_market", "permissions", "app_state" -> {
                    val packageName = msg.optString("package")
                    if (!validPackage(packageName)) return error("bad_request")
                    host.appAction(type, packageName)
                }
                "app_icon" -> {
                    val packageName = msg.optString("package")
                    if (!validPackage(packageName)) return error("bad_request")
                    val png = host.icon(packageName) ?: return error("not_found")
                    ok().put("png", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
                }
                "account_link" -> error("not_implemented")
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
            private val STREAM_TYPES = setOf("pull_apk", "push_apk", "pull_download")
            private val FS_STREAM_TYPES = setOf("fs_pull", "fs_push")
            private const val KEY_FS_GRANTS = "fs_grants"
            private const val KEY_FS_LOG = "fs_log"
            private const val FS_LOG_SIZE = 50
        }
    }
}
