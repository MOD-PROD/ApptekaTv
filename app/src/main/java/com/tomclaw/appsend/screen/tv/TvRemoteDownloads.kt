package com.tomclaw.appsend.screen.tv

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.tomclaw.appsend.download.ApkStorage
import com.tomclaw.appsend.util.versionCodeCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ATV (écran « Downloaded » en mode 📺, côté TV) : APK téléchargés par la TV
 * (dossier Download/Appteka de la TV), listés et manipulés depuis le téléphone associé.
 * Un nom de fichier n'est accepté que s'il figure dans la liste de la TV.
 * Méthodes appelées sur le thread de la liaison.
 */
class TvRemoteDownloads(private val activity: Activity, private val apkStorage: ApkStorage) {

    private val pm = activity.packageManager

    fun list(): JSONObject {
        val files = JSONArray()
        apkStorage.listApkFiles()
            .sortedByDescending { it.lastModified }
            .take(MAX_FILES)
            .forEach { info ->
                val entry = JSONObject()
                    .put("file", info.fileName)
                    .put("size", info.size)
                    .put("modified", info.lastModified)
                archive(info.fileName)?.let { (archive, label) ->
                    entry.put("label", label)
                        .put("package", archive.packageName)
                        .put("version", archive.versionName.orEmpty())
                        .put("code", archive.versionCodeCompat())
                }
                files.put(entry)
            }
        return JSONObject().put("files", files)
    }

    /** Chemin lisible d'un APK téléchargé par la TV, ou null s'il n'est pas dans la liste. */
    fun file(fileName: String): File? {
        if (!known(fileName)) return null
        return apkStorage.getFilePath(fileName)?.let(::File)?.takeIf { it.isFile && it.canRead() }
    }

    /**
     * `download_install` : installe l'APK sur la TV ([install] sur le thread principal) ;
     * `download_delete` : le supprime ; `download_permissions` : permissions demandées.
     */
    fun action(type: String, fileName: String, install: (Uri, String) -> Unit): JSONObject {
        if (!known(fileName)) return error("not_found")
        return when (type) {
            "download_install" -> {
                val uri = apkStorage.getInstallUri(fileName) ?: return error("not_found")
                val label = archive(fileName)?.second ?: fileName
                activity.runOnUiThread { install(uri, label) }
                ok().put("label", label)
            }
            "download_delete" -> if (apkStorage.delete(fileName)) ok() else error("not_found")
            "download_permissions" -> {
                val path = apkStorage.getFilePath(fileName) ?: return error("not_found")
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS)
                    ?: return error("not_apk")
                ok().put("permissions", JSONArray(info.requestedPermissions?.toList().orEmpty().take(500)))
            }
            else -> error("unknown")
        }
    }

    fun icon(fileName: String): ByteArray? {
        if (!known(fileName)) return null
        val path = apkStorage.getFilePath(fileName) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val appInfo = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            val drawable = appInfo.loadIcon(pm)
            val bitmap = createBitmap(ICON_PX, ICON_PX)
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun known(fileName: String) = apkStorage.listApkFiles().any { it.fileName == fileName }

    private fun archive(fileName: String): Pair<android.content.pm.PackageInfo, String>? = runCatching {
        val path = apkStorage.getFilePath(fileName) ?: return null
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(path, 0) ?: return null
        val appInfo = archive.applicationInfo
        val label = appInfo?.let {
            it.sourceDir = path
            it.publicSourceDir = path
            runCatching { it.loadLabel(pm).toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: archive.packageName
        archive to label
    }.getOrNull()

    private fun ok() = JSONObject().put("ok", true)
    private fun error(reason: String) = JSONObject().put("ok", false).put("error", reason)

    private companion object {
        const val ICON_PX = 96
        const val MAX_FILES = 300
    }
}
