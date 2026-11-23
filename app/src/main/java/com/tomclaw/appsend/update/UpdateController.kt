package com.tomclaw.appsend.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import android.os.Build
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.install.InstallRequest
import java.util.concurrent.atomic.AtomicBoolean

class UpdateController(private val activity: Activity) {
    private val busy = AtomicBoolean(false)
    private val downloader = UpdateDownloader(activity.applicationContext)
    private fun active() = !activity.isFinishing && !activity.isDestroyed
    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    fun check(manual: Boolean = false, channel: String = "stable") {
        require(channel in setOf("stable", "beta", "test"))
        if (!busy.compareAndSet(false, true)) return
        Thread {
            val result = runCatching {
                val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                downloader.check(if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), channel)
            }
            activity.runOnUiThread {
                if (!active()) { busy.set(false); return@runOnUiThread }
                result.onSuccess { update ->
                    if (update == null) {
                        busy.set(false)
                        if (manual) message("Aucune mise à jour disponible ($channel)")
                    } else {
                        val dialog = AlertDialog.Builder(activity)
                        .setTitle("Appteka TV — Mise à jour")
                        .setMessage("Version installée : " + com.tomclaw.appsend.BuildConfig.VERSION_NAME + " (" + com.tomclaw.appsend.BuildConfig.VERSION_CODE + ")\nVersion disponible : " + update.versionName + " (" + update.versionCode + ")")
                        .setNegativeButton("Plus tard", null)
                        .setPositiveButton("Télécharger") { _, _ -> busy.set(false); download(update) }
                        .create()
                        dialog.setOnCancelListener { busy.set(false) }
                        dialog.show()
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { busy.set(false); dialog.dismiss() }
                    }
                }.onFailure { busy.set(false); if (manual) message("Vérification impossible ($channel)") }
            }
        }.start()
    }

    private fun download(update: ReleaseUpdate) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setMessage("Autorisez Appteka TV à installer des applications, puis relancez la mise à jour.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Autoriser") { _, _ ->
                    runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + activity.packageName))) }
                        .onFailure { message("Réglage indisponible") }
                }.show()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val cancelled = AtomicBoolean(false)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val dialog = AlertDialog.Builder(activity).setTitle("Téléchargement").setView(bar)
            .setNegativeButton("Annuler") { _, _ -> cancelled.set(true) }.create()
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
                if (file == null) { message("Téléchargement ou vérification impossible"); return@runOnUiThread }
                val uri = runCatching {
                    FileProvider.getUriForFile(activity, activity.packageName + ".provider", file)
                }.getOrElse { error ->
                    Log.e("ApptekaUpdate", "Cannot share the verified APK", error)
                    message("Installation impossible : " + (error.localizedMessage ?: error.javaClass.simpleName))
                    return@runOnUiThread
                }
                // Same route as catalog installs: a PackageInstaller session (installer of record),
                // whose confirmation Android shows itself, also on Android TV. ACTION_VIEW /
                // ACTION_INSTALL_PACKAGE often resolve to nothing on TV firmwares.
                runCatching {
                    activity.appComponent.appInstaller().install(
                        InstallRequest(key = SELF_UPDATE_KEY, label = "Appteka TV " + update.versionName, uri = uri)
                    )
                    message("Confirmez l'installation sur l'écran Android qui s'ouvre")
                }.onFailure { error ->
                    Log.e("ApptekaUpdate", "Session install failed to start; trying the system installer", error)
                    openSystemInstaller(uri)
                }
            }
        }.start()
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
