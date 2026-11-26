package com.tomclaw.appsend.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import android.os.Build
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tomclaw.appsend.R
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.install.InstallRequest
import java.util.concurrent.atomic.AtomicBoolean

class UpdateController(private val activity: Activity) {
    private val busy = AtomicBoolean(false)
    private val downloader = UpdateDownloader(activity.applicationContext)
    private fun active() = !activity.isFinishing && !activity.isDestroyed
    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun text(id: Int, vararg args: Any): String = activity.getString(id, *args)
    private fun appLabel(): String = activity.applicationInfo.loadLabel(activity.packageManager).toString()

    fun check(manual: Boolean = false, channel: String = "stable") {
        require(channel in setOf("stable", "beta", "test"))
        if (!busy.compareAndSet(false, true)) return
        Thread {
            val result = runCatching { downloader.check(installedCode(), channel) }
            activity.runOnUiThread {
                if (!active()) { busy.set(false); return@runOnUiThread }
                result.onSuccess { update ->
                    if (update == null) {
                        busy.set(false)
                        if (manual) message(text(R.string.atv_update_none, UpdateChannel.label(activity, channel)))
                    } else {
                        val dialog = AlertDialog.Builder(activity)
                        .setTitle(text(R.string.atv_update_title, appLabel()))
                        .setMessage(text(R.string.atv_update_versions, com.tomclaw.appsend.BuildConfig.VERSION_NAME + " (" + com.tomclaw.appsend.BuildConfig.VERSION_CODE + ")", update.versionName + " (" + update.versionCode + ")"))
                        .setNegativeButton(R.string.atv_update_later, null)
                        .setPositiveButton(R.string.atv_update_download) { _, _ -> busy.set(false); download(update) }
                        .create()
                        dialog.setOnCancelListener { busy.set(false) }
                        dialog.show()
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { busy.set(false); dialog.dismiss() }
                    }
                }.onFailure { busy.set(false); if (manual) message(text(R.string.atv_update_check_failed, UpdateChannel.label(activity, channel))) }
            }
        }.start()
    }

    private fun download(update: ReleaseUpdate) {
        if (needsInstallPermission()) {
            AlertDialog.Builder(activity).setMessage(text(R.string.atv_update_allow_install, appLabel()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.atv_update_allow) { _, _ -> openUnknownSources() }.show()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val cancelled = AtomicBoolean(false)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.atv_update_downloading).setView(bar)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }.create()
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.show()
        Thread {
            val result = runCatching { downloader.download(update, { cancelled.get() || !active() }) { p ->
                activity.runOnUiThread { if (active()) bar.progress = p }
            } }
            activity.runOnUiThread {
                busy.set(false)
                if (active()) dialog.dismiss()
                val file = result.getOrNull()
                if (!active() || cancelled.get()) { file?.delete(); return@runOnUiThread }
                if (file == null) {
                    // ATV: say at which step it failed (sha256, signature, http_404, réseau…).
                    message(text(R.string.atv_update_failed, failureCode(result.exceptionOrNull())))
                    return@runOnUiThread
                }
                install(file, update)
            }
        }.start()
    }

    // ------------------------------------------------------------------ ATV tuile « À propos »
    // Gonzo 26/11 : sur la TV, la vérification et l'installation se font dans la tuile HTML
    // ouverte par le logo. L'état est envoyé à la page au lieu des fenêtres Android.

    fun interface Listener { fun onUpdateState(state: Map<String, Any?>) }

    @Volatile private var pending: ReleaseUpdate? = null
    @Volatile private var tileCancel: AtomicBoolean? = null

    fun checkForTile(channel: String, listener: Listener) {
        val ch = channel.takeIf { it in UpdateChannel.CHANNELS } ?: "stable"
        if (!busy.compareAndSet(false, true)) { listener.onUpdateState(mapOf("state" to "busy", "channel" to ch)); return }
        pending = null
        listener.onUpdateState(mapOf("state" to "checking", "channel" to ch))
        Thread {
            val result = runCatching { downloader.check(installedCode(), ch) }
            activity.runOnUiThread {
                busy.set(false)
                if (!active()) return@runOnUiThread
                result.onSuccess { update ->
                    pending = update
                    listener.onUpdateState(
                        if (update == null) mapOf("state" to "uptodate", "channel" to ch)
                        else mapOf("state" to "available", "channel" to ch, "versionName" to update.versionName,
                            "versionCode" to update.versionCode, "size" to update.size)
                    )
                }.onFailure { listener.onUpdateState(mapOf("state" to "error", "phase" to "check", "channel" to ch, "code" to failureCode(it))) }
            }
        }.start()
    }

    fun installFromTile(listener: Listener) {
        val update = pending ?: run { listener.onUpdateState(mapOf("state" to "error", "phase" to "download", "code" to "none")); return }
        if (needsInstallPermission()) {
            listener.onUpdateState(mapOf("state" to "permission"))
            openUnknownSources()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val cancelled = AtomicBoolean(false)
        tileCancel = cancelled
        listener.onUpdateState(mapOf("state" to "downloading", "progress" to 0))
        Thread {
            var last = -1
            val result = runCatching { downloader.download(update, { cancelled.get() || !active() }) { p ->
                if (p != last) {
                    last = p
                    activity.runOnUiThread { if (active() && !cancelled.get()) listener.onUpdateState(mapOf("state" to "downloading", "progress" to p)) }
                }
            } }
            activity.runOnUiThread {
                busy.set(false)
                tileCancel = null
                val file = result.getOrNull()
                if (!active() || cancelled.get()) {
                    file?.delete()
                    if (active()) listener.onUpdateState(mapOf("state" to "available", "versionName" to update.versionName, "versionCode" to update.versionCode))
                    return@runOnUiThread
                }
                if (file == null) {
                    listener.onUpdateState(mapOf("state" to "error", "phase" to "download", "code" to failureCode(result.exceptionOrNull())))
                    return@runOnUiThread
                }
                listener.onUpdateState(mapOf("state" to "installing", "versionName" to update.versionName))
                install(file, update)
            }
        }.start()
    }

    fun cancelTile() { tileCancel?.set(true) }

    // ------------------------------------------------------------------ commun

    @Suppress("DEPRECATION")
    private fun installedCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()

    private fun openUnknownSources() {
        runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:" + activity.packageName))) }
            .onFailure { message(text(R.string.atv_update_setting_unavailable)) }
    }

    private fun failureCode(error: Throwable?): String = (error as? UpdateFailure)?.code ?: when (error) {
        is javax.net.ssl.SSLException -> "tls"
        is java.net.UnknownHostException -> "dns"
        is java.io.IOException -> "network"
        else -> error?.javaClass?.simpleName ?: "unknown"
    }

    private fun install(file: java.io.File, update: ReleaseUpdate) {
        val uri = runCatching {
            FileProvider.getUriForFile(activity, activity.packageName + ".provider", file)
        }.getOrElse { error ->
            Log.e("ApptekaUpdate", "Cannot share the verified APK", error)
            message(text(R.string.atv_update_install_failed, error.localizedMessage ?: error.javaClass.simpleName))
            return
        }
        // Same route as catalog installs: a PackageInstaller session (installer of record),
        // whose confirmation Android shows itself, also on Android TV. ACTION_VIEW /
        // ACTION_INSTALL_PACKAGE often resolve to nothing on TV firmwares.
        runCatching {
            activity.appComponent.appInstaller().install(
                InstallRequest(key = SELF_UPDATE_KEY, label = "Appteka TV " + update.versionName, uri = uri)
            )
            message(text(R.string.atv_update_confirm))
        }.onFailure { error ->
            Log.e("ApptekaUpdate", "Session install failed to start; trying the system installer", error)
            openSystemInstaller(uri)
        }
    }

    /** Last resort when the session cannot start: Android's own installer screen. */
    private fun openSystemInstaller(uri: android.net.Uri) {
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val opened = listOf(install, view).any { intent ->
            runCatching { activity.startActivity(intent) }
                .onFailure { Log.w("ApptekaUpdate", "Installer intent refused: " + intent.action, it) }
                .isSuccess
        }
        if (!opened) message("Aucun installateur Android disponible sur cet appareil")
    }

    private companion object {
        const val SELF_UPDATE_KEY = "appteka-tv-self-update"
    }
}
