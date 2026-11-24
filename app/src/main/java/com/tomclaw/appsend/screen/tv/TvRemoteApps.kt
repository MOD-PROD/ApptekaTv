package com.tomclaw.appsend.screen.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.tomclaw.appsend.util.getPackageInfoCompat
import com.tomclaw.appsend.util.versionCodeCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * ATV (Installées en mode 📺, côté TV) : exécute sur la TV les actions du menu natif
 * « Installed » demandées par le téléphone associé, et échange les APK.
 * Les méthodes sont appelées sur le thread de la liaison ; les écrans s'ouvrent sur la TV.
 */
class TvRemoteApps(private val activity: Activity) {

    private val pm = activity.packageManager

    fun action(type: String, packageName: String): JSONObject {
        // ATV (fiche en mode 📺) : version installée sur la TV, -1 si absente.
        if (type == "app_state") {
            val code = runCatching { pm.getPackageInfoCompat(packageName, 0).versionCodeCompat() }.getOrDefault(-1L)
            return ok().put("code", code)
        }
        if (!installed(packageName)) return error("not_found")
        return when (type) {
            "launch" -> {
                val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
                    ?: pm.getLaunchIntentForPackage(packageName)
                    ?: return error("not_launchable")
                start(intent)
            }
            "uninstall" -> start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            "app_details" -> start(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            "open_market" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                if (intent.resolveActivity(pm) == null) error("no_market") else start(intent)
            }
            "permissions" -> {
                val info = pm.getPackageInfoCompat(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                ok().put("permissions", JSONArray(info.requestedPermissions?.toList().orEmpty().take(500)))
            }
            else -> error("unknown")
        }
    }

    fun icon(packageName: String): ByteArray? = runCatching {
        val drawable = pm.getApplicationIcon(packageName)
        val bitmap = createBitmap(ICON_PX, ICON_PX)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    fun apkFile(packageName: String): File? = runCatching {
        File(pm.getApplicationInfo(packageName, 0).sourceDir).takeIf { it.isFile && it.canRead() }
    }.getOrNull()

    fun incomingApk(size: Long): File? {
        val dir = File(activity.cacheDir, "apk_cache").apply { mkdirs() }
        // Earlier transfers already handed to the installer, or abandoned, are not kept.
        dir.listFiles()?.filter {
            it.name.startsWith(INCOMING) && System.currentTimeMillis() - it.lastModified() > KEEP_MS
        }?.forEach { it.delete() }
        if (dir.usableSpace < size + MARGIN_BYTES) return null
        val name = INCOMING + ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } + ".apk"
        return File(dir, name)
    }

    /** Vérifie que le fichier reçu est un APK, puis [install] (thread principal) avec son libellé. */
    fun received(file: File, install: (Uri, String) -> Unit): JSONObject {
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.path, 0)
        val appInfo = archive?.applicationInfo
        if (archive == null || appInfo == null) {
            file.delete()
            return error("not_apk")
        }
        appInfo.sourceDir = file.path
        appInfo.publicSourceDir = file.path
        val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: archive.packageName
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
        activity.runOnUiThread { install(uri, label) }
        return ok().put("label", label).put("package", archive.packageName)
    }

    private fun installed(packageName: String) = runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess

    private fun start(intent: Intent): JSONObject {
        if (intent.resolveActivity(pm) == null) return error("no_activity")
        activity.runOnUiThread {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return ok()
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun error(reason: String) = JSONObject().put("ok", false).put("error", reason)

    private companion object {
        const val ICON_PX = 96
        const val INCOMING = "incoming-"
        const val KEEP_MS = 30 * 60 * 1000L
        const val MARGIN_BYTES = 50L * 1024 * 1024
    }
}
