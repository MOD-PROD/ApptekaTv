package com.tomclaw.appsend.target

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.tvlink.TvLinkSender
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ATV : revenir en mode 📺 sans refaire l'association. Le téléphone garde la dernière TV
 * (adresse, empreinte TLS, jeton) ; un `ping` suffit si la TV le connaît encore. Si la TV
 * a changé d'adresse IP, elle est retrouvée sur le réseau local (NSD) : seule une TV
 * présentant la même empreinte TLS et le même identifiant est acceptée.
 */
object TvReconnect {

    sealed interface Result {
        class Connected(val connection: TvLinkSender.Connection) : Result
        /** La TV a oublié ce téléphone : il faut une nouvelle association. */
        data object Forgotten : Result
        data object Unreachable : Result
    }

    /** Bloquant (thread de travail). */
    fun reconnect(context: Context, last: TvLinkSender.Connection): Result {
        when (ping(last)) {
            true -> return Result.Connected(last)
            false -> return Result.Forgotten
            null -> Unit
        }
        for ((host, port) in discover(context)) {
            if (host == last.address.host && port == last.address.port) continue
            val moved = TvLinkSender.Connection(last.address.copy(host = host, port = port), last.token, last.name)
            when (ping(moved)) {
                true -> return Result.Connected(moved)
                false -> return Result.Forgotten
                null -> Unit
            }
        }
        return Result.Unreachable
    }

    private val lastMiss = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * ATV (Gonzo 25/11) : appelé par [TvLink] quand l'adresse mémorisée ne répond plus. Cherche
     * l'appareil sur le réseau local (NSD) ; seul celui qui a le même identifiant et la même
     * empreinte TLS est accepté, puis l'adresse est mise à jour partout. Au plus une recherche
     * toutes les 30 s par appareil, pour ne pas ralentir chaque écran quand il est vraiment éteint.
     */
    fun relocate(context: Context, address: TvLink.Pairing): TvLink.Pairing? {
        val now = android.os.SystemClock.elapsedRealtime()
        val missed = lastMiss[address.tv]
        if (missed != null && now - missed < RELOCATE_COOLDOWN_MS) return null
        for ((host, port) in discover(context)) {
            if (host == address.host && port == address.port) continue
            val moved = address.copy(host = host, port = port)
            val same = runCatching {
                val hello = TvLink.request(moved, JSONObject().put("type", "hello"))
                hello.optBoolean("ok") && hello.optString("tv") == address.tv
            }.getOrDefault(false)
            if (same) {
                lastMiss.remove(address.tv)
                TvTarget.relocate(context, address.tv, host, port)
                return moved
            }
        }
        lastMiss[address.tv] = now
        return null
    }

    private const val RELOCATE_COOLDOWN_MS = 30_000L

    /** true = jeton accepté, false = refusé par cette TV, null = injoignable ou autre TV. */
    private fun ping(connection: TvLinkSender.Connection): Boolean? = try {
        val hello = TvLink.request(connection.address, JSONObject().put("type", "hello"))
        if (!hello.optBoolean("ok") || hello.optString("tv") != connection.address.tv) null
        else {
            val response = TvLink.request(
                connection.address,
                JSONObject().put("type", "ping").put("token", connection.token),
            )
            when {
                response.optBoolean("ok") -> true
                response.optString("error") == "unauthorized" -> false
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Appareil Appteka annoncé en NSD (nom de service, hôte, port) — sans TLS. */
    data class NearbyDevice(val name: String, val host: String, val port: Int)

    /** Appareils Appteka TV annoncés sur le réseau local (au plus 6, ~3 s). */
    fun discover(context: Context): List<Pair<String, Int>> =
        discoverNearby(context).map { it.host to it.port }

    /**
     * Découverte NSD publique pour la liste « À proximité ».
     * Ne fait pas de requête TLS (pas d'empreinte) : le nom vient du service NSD.
     */
    fun discoverNearby(context: Context, waitMs: Long = DISCOVERY_MS): List<NearbyDevice> {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()
        val found = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(found) { if (found.size < MAX_SERVICES) found += service }
            }
        }
        try {
            manager.discoverServices(TvLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Thread.sleep(waitMs)
        } catch (_: Exception) {
        } finally {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        val services = synchronized(found) { found.toList() }
        return services.mapNotNull { resolveNearby(manager, it) }.distinctBy { "${it.host}:${it.port}" }
    }

    @Suppress("DEPRECATION")
    private fun resolveNearby(manager: NsdManager, service: NsdServiceInfo): NearbyDevice? {
        val latch = CountDownLatch(1)
        var result: NearbyDevice? = null
        try {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = latch.countDown()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host
                    if (address is Inet4Address && address.isSiteLocalAddress && info.port in 1..65535) {
                        val host = address.hostAddress.orEmpty()
                        if (host.isNotEmpty()) {
                            val raw = info.serviceName.orEmpty().ifBlank { "Appteka TV" }
                            val name = raw.substringAfter("·", raw).trim().ifBlank { raw }
                            result = NearbyDevice(name = name, host = host, port = info.port)
                        }
                    }
                    latch.countDown()
                }
            })
            latch.await(RESOLVE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        return result
    }

    private const val DISCOVERY_MS = 3000L
    private const val RESOLVE_MS = 2000L
    private const val MAX_SERVICES = 6
}
