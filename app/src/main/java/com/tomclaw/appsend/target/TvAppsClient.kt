package com.tomclaw.appsend.target

import android.content.Context
import android.util.Base64
import com.tomclaw.appsend.tvlink.TvFiles
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.tvlink.TvLinkSender
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * ATV (écran « Installées » unifié, côté téléphone) : ordres envoyés à la TV associée pour
 * lister ses applis et exécuter les actions du menu natif. Appels bloquants (thread d'E/S).
 * Une réponse refusée lève [TvRemoteException] avec le code d'erreur de la TV.
 */
class TvAppsClient(private val context: Context, val connection: TvLinkSender.Connection) {

    val tvName: String get() = connection.name
    val tvId: String get() = connection.address.tv

    fun list(system: Boolean): JSONObject =
        checked(TvLink.request(connection.address, order("list_apps").put("system", system)))
            .optJSONObject("content") ?: JSONObject()

    fun action(type: String, packageName: String): JSONObject =
        checked(TvLink.request(connection.address, order(type).put("package", packageName)))

    /** Version installée sur la TV, ou -1 si l'appli n'y est pas. */
    fun installedVersion(packageName: String): Long =
        action("app_state", packageName).optLong("code", -1L)

    fun icon(packageName: String): ByteArray =
        Base64.decode(action("app_icon", packageName).getString("png"), Base64.DEFAULT)

    /** Copie l'APK de l'appli de la TV dans [target] (fichier complet ou supprimé). */
    fun pullApk(packageName: String, target: File, progress: (Long, Long) -> Unit) {
        try {
            target.outputStream().use { sink ->
                checked(TvLink.pull(connection.address, order("pull_apk").put("package", packageName), sink, progress))
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Envoie [file] à la TV, qui l'installe (confirmation sur la TV). Renvoie le libellé lu par la TV. */
    fun pushApk(file: File, progress: (Long, Long) -> Unit): String {
        val response = file.inputStream().use { source ->
            checked(TvLink.push(connection.address, order("push_apk"), source, file.length(), progress))
        }
        return response.optString("label")
    }

    // ------------------------------------------------ Downloaded (APK téléchargés par la TV)

    fun downloads(): JSONObject =
        checked(TvLink.request(connection.address, order("list_downloads")))
            .optJSONObject("content") ?: JSONObject()

    fun downloadAction(type: String, fileName: String): JSONObject =
        checked(TvLink.request(connection.address, order(type).put("file", fileName)))

    fun downloadIcon(fileName: String): ByteArray =
        Base64.decode(downloadAction("download_icon", fileName).getString("png"), Base64.DEFAULT)

    /** Copie un APK téléchargé par la TV dans [target] (fichier complet ou supprimé). */
    fun pullDownload(fileName: String, target: File, progress: (Long, Long) -> Unit) {
        try {
            target.outputStream().use { sink ->
                checked(TvLink.pull(connection.address, order("pull_download").put("file", fileName), sink, progress))
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    // ------------------------------------------------ ATV-09 : fichiers à distance

    /** Ouvre les fichiers de la TV (accord une fois sur la TV) : `access` et `roots`. */
    fun fsAccess(): JSONObject = checked(TvLink.request(connection.address, order("fs_access")))

    fun fsList(path: String, hidden: Boolean): JSONObject =
        checked(TvLink.request(connection.address, order("fs_list").put("path", path).put("hidden", hidden)))

    fun fsMkdir(parent: String, name: String): JSONObject =
        checked(TvLink.request(connection.address, order("fs_mkdir").put("path", parent).put("name", name)))

    fun fsRename(path: String, name: String): JSONObject =
        checked(TvLink.request(connection.address, order("fs_rename").put("path", path).put("name", name)))

    fun fsDelete(paths: List<String>): JSONObject =
        checked(TvLink.request(connection.address, order("fs_delete").put("paths", org.json.JSONArray(paths))))

    fun fsInstall(path: String): JSONObject =
        checked(TvLink.request(connection.address, order("fs_install").put("path", path)))

    /** Copie le fichier [path] de la TV dans [sink]. */
    fun fsPull(path: String, sink: java.io.OutputStream, progress: (Long, Long) -> Unit): JSONObject =
        checked(TvLink.pull(connection.address, order("fs_pull").put("path", path), sink, progress, 0..TvFiles.MAX_FILE_BYTES))

    /** Envoie [size] octets de [source] dans le dossier [dir] de la TV ; renvoie le nom retenu. */
    fun fsPush(dir: String, name: String, source: java.io.InputStream, size: Long, progress: (Long, Long) -> Unit): String =
        checked(
            TvLink.push(
                connection.address, order("fs_push").put("path", dir).put("name", name),
                source, size, progress, 0..TvFiles.MAX_FILE_BYTES,
            )
        ).optString("name", name)

    private fun order(type: String) = JSONObject().put("type", type).put("token", connection.token)

    private fun checked(response: JSONObject): JSONObject {
        if (response.optBoolean("ok")) return response
        val error = response.optString("error", "refused").take(32)
        // Le jeton a été révoqué sur la TV (« Oublier ») : retour à « Ce téléphone ».
        if (error == "unauthorized") TvTarget.forget(context)
        throw TvRemoteException(error)
    }

    companion object {
        fun current(context: Context): TvAppsClient? =
            TvTarget.current(context)?.let { TvAppsClient(context.applicationContext, it) }

        /** TV associée la plus récente, même en mode 📱 (pour « Send to <TV> »). */
        fun last(context: Context): TvAppsClient? =
            TvTarget.last(context)?.let { TvAppsClient(context.applicationContext, it) }
    }
}

class TvRemoteException(val code: String) : IOException(code)
