package com.tomclaw.appsend.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.concurrent.atomic.AtomicBoolean

class UpdateController(private val activity: Activity) {
    private val busy = AtomicBoolean(false)
    private val downloader = UpdateDownloader(activity.applicationContext)
    private fun active() = !activity.isFinishing && !activity.isDestroyed
    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    fun check(manual: Boolean = false) {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            val result = runCatching {
                val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                downloader.check(if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            }
            activity.runOnUiThread {
                busy.set(false)
                if (!active()) return@runOnUiThread
                result.onSuccess { update ->
                    if (update == null) {
                        if (manual) message("Aucune mise à jour disponible")
                    } else AlertDialog.Builder(activity)
                        .setTitle("Appteka TV — Mise à jour")
                        .setMessage("Version disponible : " + update.versionName)
                        .setNegativeButton("Plus tard", null)
                        .setPositiveButton("Télécharger") { _, _ -> download(update) }
                        .show()
                }.onFailure { if (manual) message("Vérification impossible") }
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
                runCatching {
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".provider", file)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { file.delete(); message("Installation impossible") }
            }
        }.start()
    }
}
