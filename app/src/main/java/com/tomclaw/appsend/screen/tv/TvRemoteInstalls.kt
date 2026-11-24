package com.tomclaw.appsend.screen.tv

import android.content.Context
import com.tomclaw.appsend.download.DownloadManager
import com.tomclaw.appsend.download.IDLE
import com.tomclaw.appsend.install.AppInstaller
import com.tomclaw.appsend.install.InstallStatus
import com.tomclaw.appsend.screen.details.api.AppInfo
import com.tomclaw.appsend.target.TvRemoteState
import com.tomclaw.appsend.util.NOT_INSTALLED
import com.tomclaw.appsend.util.getPackageInfoCompat
import com.tomclaw.appsend.util.versionCodeCompat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.ConcurrentHashMap

/**
 * ATV-03 (côté TV) : suit les installations demandées par un téléphone associé
 * pour pouvoir répondre à l'ordre `status`. La TV télécharge et installe
 * elle-même avec les services natifs ; ce suivi ne fait que les observer.
 * [status] est appelé depuis le thread de la liaison TV, d'où les champs volatils.
 */
class TvRemoteInstalls(
    context: Context,
    private val downloadManager: DownloadManager,
    private val appInstaller: AppInstaller,
) {
    private val packageManager = context.applicationContext.packageManager

    private class Entry {
        @Volatile var download: Int = IDLE
        @Volatile var install: InstallStatus = InstallStatus.Idle
        @Volatile var sawInstall = false
        @Volatile var packageName: String? = null
        @Volatile var versionCode = 0L
        @Volatile var initialVersion = NOT_INSTALLED.toLong()
        @Volatile var failure: String? = null
        var subscription: Disposable? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val order = ArrayDeque<String>()

    /** Ordre `install` reçu : on commence à suivre (thread principal). */
    @Synchronized
    fun track(appId: String) {
        entries.remove(appId)?.subscription?.dispose()
        order.remove(appId)
        while (order.size >= MAX_TRACKED) {
            entries.remove(order.removeFirst())?.subscription?.dispose()
        }
        val entry = Entry()
        entry.subscription = Observable.combineLatest(
            downloadManager.status(appId),
            appInstaller.status(appId),
        ) { download, install -> download to install }
            .subscribeOn(Schedulers.io())
            .subscribe({ (download, install) ->
                entry.download = download
                entry.install = install
                if (install != InstallStatus.Idle) entry.sawInstall = true
            }, {})
        entries[appId] = entry
        order.addLast(appId)
    }

    /** Détails de l'appli connus : paquet et version visés. */
    fun attach(info: AppInfo) {
        val entry = entries[info.appId] ?: return
        entry.initialVersion = installedVersion(info.packageName)
        entry.versionCode = info.versionCode.toLong()
        entry.packageName = info.packageName
    }

    fun fail(appId: String, reason: String) {
        entries[appId]?.failure = reason
    }

    fun status(appId: String): TvRemoteState {
        val entry = entries[appId] ?: return TvRemoteState(TvRemoteState.UNKNOWN)
        val packageName = entry.packageName
        val installed = packageName != null && installedVersion(packageName) >= entry.versionCode &&
            (entry.initialVersion < entry.versionCode ||
                entry.sawInstall && entry.install == InstallStatus.Idle)
        // ATV: the phone is told why the TV could not download (storage, cleartext, http_403...).
        val failure = entry.failure ?: if (entry.download == com.tomclaw.appsend.download.ERROR && !entry.sawInstall) {
            downloadManager.failure(appId)?.let { "${TvRemoteState.REASON_DOWNLOAD}_$it" }
        } else null
        return TvRemoteState.from(entry.download, entry.install, installed, entry.sawInstall, failure)
    }

    @Synchronized
    fun dispose() {
        entries.values.forEach { it.subscription?.dispose() }
        entries.clear()
        order.clear()
    }

    private fun installedVersion(packageName: String): Long = try {
        packageManager.getPackageInfoCompat(packageName, 0).versionCodeCompat()
    } catch (_: Exception) {
        NOT_INSTALLED.toLong()
    }

    private companion object {
        const val MAX_TRACKED = 16
    }
}
