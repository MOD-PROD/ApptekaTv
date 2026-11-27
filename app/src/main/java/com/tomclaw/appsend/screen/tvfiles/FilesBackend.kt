package com.tomclaw.appsend.screen.tvfiles

import android.content.Context
import android.net.Uri
import android.os.Build
import com.tomclaw.appsend.screen.tv.TvRemoteFiles
import com.tomclaw.appsend.target.DeviceKinds
import com.tomclaw.appsend.target.TvAppsClient
import com.tomclaw.appsend.target.TvRemoteException
import com.tomclaw.appsend.tvlink.TvFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ATV (Gonzo 24/11) : « Fichiers » explore l'appareil sélectionné. En mode TV, la TV par la
 * liaison chiffrée ; en mode téléphone, le stockage de cet appareil, avec les mêmes règles
 * de chemins (racines, Android/data interdit) que côté TV.
 */
interface FilesBackend {
    val name: String
    val local: Boolean
    fun access(): JSONObject
    fun list(path: String, hidden: Boolean): JSONObject
    fun mkdir(parent: String, name: String): JSONObject
    fun rename(path: String, name: String): JSONObject
    fun delete(paths: List<String>): JSONObject
    fun install(path: String): JSONObject
    fun copy(paths: List<String>, destDir: String): JSONObject
}

class RemoteFilesBackend(val client: TvAppsClient) : FilesBackend {
    override val name: String get() = client.tvName
    override val local = false
    override fun access() = client.fsAccess()
    override fun list(path: String, hidden: Boolean) = client.fsList(path, hidden)
    override fun mkdir(parent: String, name: String) = client.fsMkdir(parent, name)
    override fun rename(path: String, name: String) = client.fsRename(path, name)
    override fun delete(paths: List<String>) = client.fsDelete(paths)
    override fun install(path: String) = client.fsInstall(path)
    override fun copy(paths: List<String>, destDir: String) = client.fsCopy(paths, destDir)
}

class LocalFilesBackend(context: Context) : FilesBackend {
    private val app = context.applicationContext
    private val storage = TvRemoteFiles(app)

    override val name: String = app.getString(DeviceKinds.current(app, null).label)
    override val local = true

    val needsSettingsScreen: Boolean get() = storage.needsSettingsScreen
    fun openAllFilesSettings(): Boolean = storage.openAllFilesSettings()

    private fun roots(): List<File> = storage.roots().map { it.file }

    override fun access(): JSONObject {
        val mode = storage.accessMode()
        val roots = JSONArray()
        if (mode != "none") storage.roots().forEach { root ->
            val dir = runCatching { root.file.canonicalFile }.getOrNull() ?: return@forEach
            roots.put(
                JSONObject().put("path", dir.path).put("label", root.label).put("removable", root.removable)
                    .put("writable", dir.canWrite()).put("free", dir.usableSpace).put("total", dir.totalSpace)
            )
        }
        return JSONObject().put("ok", true).put("access", mode).put("roots", roots).put("sdk", Build.VERSION.SDK_INT)
    }

    override fun list(path: String, hidden: Boolean) = checked(TvFiles.list(roots(), path, hidden))
    override fun mkdir(parent: String, name: String) = checked(TvFiles.mkdir(roots(), parent, name))
    override fun rename(path: String, name: String) = checked(TvFiles.rename(roots(), path, name))
    override fun delete(paths: List<String>) = checked(TvFiles.delete(roots(), paths))
    override fun copy(paths: List<String>, destDir: String) = checked(TvFiles.copy(roots(), paths, destDir))

    /** Fichier local vérifié (sous une racine, lisible), pour l'ouvrir ou l'installer. */
    override fun install(path: String): JSONObject {
        val (file, problem) = TvFiles.readable(roots(), path)
        if (file == null) checked(problem ?: JSONObject().put("ok", false).put("error", "not_found"))
        return JSONObject().put("ok", true).put("path", file!!.path)
    }

    fun file(path: String): File {
        val (file, problem) = TvFiles.readable(roots(), path)
        if (file == null) checked(problem ?: JSONObject().put("ok", false).put("error", "not_found"))
        return file!!
    }

    /** Copie un fichier choisi dans le sélecteur vers le dossier [dir] de cet appareil. */
    fun import(uri: Uri, dir: String, name: String, size: Long): String {
        val (pair, problem) = TvFiles.incoming(roots(), dir, name, maxOf(size, 0))
        val (part, target) = pair ?: run {
            checked(problem ?: JSONObject().put("ok", false).put("error", "bad_request"))
            error("unreachable")
        }
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw TvRemoteException("unreadable")
            input.use { source -> part.outputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return checked(TvFiles.complete(part, target)).optString("name", name)
    }

    private fun checked(response: JSONObject): JSONObject {
        if (response.optBoolean("ok")) return response
        throw TvRemoteException(response.optString("error", "refused").take(32))
    }
}
