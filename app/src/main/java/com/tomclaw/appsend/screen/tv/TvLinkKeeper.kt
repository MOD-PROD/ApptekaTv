package com.tomclaw.appsend.screen.tv

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tomclaw.appsend.screen.linktarget.LocalLinkTarget
import com.tomclaw.appsend.tvlink.TvLink
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ATV (Gonzo 25/11, « pertes d'association ») : le serveur de la TV ne s'arrête plus avec
 * l'écran Appteka TV. Un seul serveur par processus, toujours sur le même port :
 * - écran ouvert : les ordres passent par l'écran (HTML, fenêtres) comme avant ;
 * - écran fermé (Retour, recréation) : les ordres passent par les briques natives, et
 *   « ouvrir / installer » rouvre Appteka TV sur la fiche demandée.
 * Le serveur ne s'arrête que si Android arrête l'appli ; au retour il reprend le même port.
 */
object TvLinkKeeper {

    private const val TAG = "TvLinkKeeper"

    @Volatile
    private var attached: TvLink.Host? = null

    @Volatile
    var server: TvLink.Server? = null
        private set

    /** Ordre « ouvrir / installer » reçu écran fermé : rejoué par le prochain écran. */
    @Volatile
    private var pendingOpen: Triple<String, Boolean, String>? = null

    private lateinit var fallback: TvLink.Host

    fun attach(
        context: Context,
        host: TvLink.Host,
        confirmFiles: (String, (Boolean) -> Unit) -> Unit,
        confirmPairRequest: ((String, (Boolean) -> Unit) -> Unit)? = null,
    ): TvLink.Server? = synchronized(this) {
        attached = host
        val app = context.applicationContext as Application
        if (!::fallback.isInitialized) fallback = ClosedScreenHost(app)
        val current = server ?: TvLink.Server(app, proxy()).also {
            try {
                it.start()
            } catch (e: Exception) {
                Log.w(TAG, "TV link unavailable", e)
                runCatching { it.stop() }
                return null
            }
            // ATV proximité : un appareil du Wi-Fi demande à s'associer → écran du code sur la TV.
            it.onPairInvite = { device ->
                com.tomclaw.appsend.screen.linktarget.PairInvites.show(app, device, TvWebActivity::class.java)
            }
            server = it
            // ATV arrière-plan (Gonzo « 1 et 2 ») : la TV reste joignable écran fermé.
            com.tomclaw.appsend.tvlink.LinkTargetService.start(app)
        }
        current.confirmFiles = confirmFiles
        current.confirmPairRequest = confirmPairRequest // association sans code : OK sur la TV
        current
    }

    /** L'écran se ferme : le serveur continue, sans fenêtres (accès fichiers refusé). */
    fun detach(host: TvLink.Host) = synchronized(this) {
        if (attached === host) {
            attached = null
            server?.confirmFiles = null
            server?.confirmPairRequest = null // écran fermé : le téléphone revient au code
        }
    }

    fun takePendingOpen(): Triple<String, Boolean, String>? = pendingOpen.also { pendingOpen = null }

    /**
     * ATV arrière-plan : démarre le serveur s'il n'existe pas, avec l'hôte de repli
     * (écran fermé). Idempotent.
     */
    fun ensureStarted(context: Context): TvLink.Server? = synchronized(this) {
        val app = context.applicationContext as Application
        if (!::fallback.isInitialized) fallback = ClosedScreenHost(app)
        server ?: attach(app, fallback, confirmFiles = { _, answer -> answer(false) })
    }

    private fun proxy(): TvLink.Host = Proxy.newProxyInstance(
        TvLink.Host::class.java.classLoader,
        arrayOf(TvLink.Host::class.java),
    ) { _, method, args ->
        val target = attached ?: fallback
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as TvLink.Host

    private class ClosedScreenHost(app: Application) : LocalLinkTarget.NativeHost(app) {
        override fun onOpen(appId: String, install: Boolean, device: String) {
            pendingOpen = Triple(appId, install, device)
            val intent = Intent(context, TvWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(intent) }
        }
    }
}
