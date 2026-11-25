package com.tomclaw.appsend.screen.installed

import android.content.Context
import android.text.format.Formatter
import com.tomclaw.appsend.R
import com.tomclaw.appsend.target.TvAppsClient
import com.tomclaw.appsend.target.TvIconLoader
import com.tomclaw.appsend.target.TvRemoteException
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.util.SchedulersFactory
import io.reactivex.rxjava3.core.Single
import org.json.JSONObject
import java.io.File

/**
 * ATV : relie l'écran natif « Installed » à la TV associée.
 * - [remote] (mode 📺) : la liste et chaque action du menu portent sur les applis de la TV ;
 *   « Send to <ce téléphone> » rapatrie l'APK de la TV sur le téléphone.
 * - [sendTarget] (mode 📱) : « Send to <TV> » envoie l'APK du téléphone à la TV.
 */
class InstalledTvBridge(
    private val context: Context,
    val remote: TvAppsClient?,
    val sendTarget: TvAppsClient?,
    private val schedulers: SchedulersFactory,
) {

    /** Libellé « Send to … » remplaçant « Share APK via apps », ou null (pas de TV connue). */
    val sendToName: String?
        get() = if (remote != null) TvLink.deviceName() else sendTarget?.tvName

    @Volatile
    var storageSummary: String? = null
        private set

    fun listRemote(systemApps: Boolean): List<InstalledAppEntity> {
        val tv = checkNotNull(remote)
        val content = tv.list(systemApps)
        storageSummary = content.optJSONObject("storage")?.let {
            context.getString(
                R.string.tv_storage_summary,
                Formatter.formatShortFileSize(context, it.optLong("free")),
                Formatter.formatShortFileSize(context, it.optLong("total")),
            )
        }
        val apps = content.optJSONArray("apps") ?: return emptyList()
        return (0 until apps.length()).mapNotNull { i ->
            val app = apps.optJSONObject(i) ?: return@mapNotNull null
            val packageName = app.optString("package").takeIf { TvLink.validPackage(it) } ?: return@mapNotNull null
            val code = app.optLong("code")
            InstalledAppEntity(
                packageName = packageName,
                label = app.optString("label").ifBlank { packageName },
                icon = TvIconLoader.uri(tv.tvId, packageName, code),
                verName = app.optString("version"),
                verCode = code,
                firstInstallTime = app.optLong("installed"),
                lastUpdateTime = app.optLong("updated"),
                size = app.optLong("size"),
                path = null,
                isUserApp = app.optBoolean("user", true),
                editionKey = app.optString("edition").takeIf { it.isNotBlank() },
            )
        }.filter { it.isUserApp || systemApps }
    }

    fun action(type: String, packageName: String): Single<JSONObject> =
        Single.fromCallable { checkNotNull(remote).action(type, packageName) }
            .subscribeOn(schedulers.io())

    /** APK de l'appli de la TV, copié dans le cache du téléphone. */
    fun pullApk(packageName: String): Single<File> = Single.fromCallable {
        val dir = File(context.cacheDir, "apk_cache").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith(PULLED) }?.forEach { it.delete() }
        val file = File(dir, "$PULLED$packageName.apk")
        checkNotNull(remote).pullApk(packageName, file) { _, _ -> }
        file
    }.subscribeOn(schedulers.io())

    /** Envoie l'APK du téléphone à la TV ; renvoie le nom de la TV. */
    fun pushApk(path: String): Single<String> = Single.fromCallable {
        val tv = checkNotNull(sendTarget)
        tv.pushApk(File(path)) { _, _ -> }
        tv.tvName
    }.subscribeOn(schedulers.io())

    // ------------------------------------- Downloaded (écran « Downloaded » en mode 📺)

    /** APK téléchargés par la TV (dossier Download/Appteka de la TV). */
    fun listDownloads(): List<com.tomclaw.appsend.screen.distro.DistroAppEntity> {
        val tv = checkNotNull(remote)
        val files = tv.downloads().optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { i ->
            val entry = files.optJSONObject(i) ?: return@mapNotNull null
            val fileName = entry.optString("file").takeIf { TvLink.validFileName(it) } ?: return@mapNotNull null
            val packageName = entry.optString("package").takeIf { TvLink.validPackage(it) }.orEmpty()
            val modified = entry.optLong("modified")
            com.tomclaw.appsend.screen.distro.DistroAppEntity(
                packageName = packageName,
                label = entry.optString("label").ifBlank { fileName },
                icon = TvIconLoader.downloadUri(tv.tvId, packageName, fileName, modified),
                verName = entry.optString("version"),
                verCode = entry.optLong("code"),
                lastModified = modified,
                size = entry.optLong("size"),
                path = null,
                fileName = fileName,
            )
        }
    }

    fun downloadAction(type: String, fileName: String): Single<JSONObject> =
        Single.fromCallable { checkNotNull(remote).downloadAction(type, fileName) }
            .subscribeOn(schedulers.io())

    /** APK téléchargé par la TV, copié dans le cache du téléphone. */
    fun pullDownload(fileName: String): Single<File> = Single.fromCallable {
        val dir = File(context.cacheDir, "apk_cache").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith(PULLED) }?.forEach { it.delete() }
        val file = File(dir, PULLED + fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) + ".apk")
        checkNotNull(remote).pullDownload(fileName, file) { _, _ -> }
        file
    }.subscribeOn(schedulers.io())

    fun tvName(): String = remote?.tvName ?: sendTarget?.tvName.orEmpty()

    /** Message lisible pour un échec d'ordre envoyé à la TV. */
    fun describe(error: Throwable): String {
        val name = tvName()
        return when ((error as? TvRemoteException)?.code) {
            null -> context.getString(R.string.tv_offline, name)
            "unknown", "not_implemented" -> context.getString(R.string.tv_needs_update, name)
            "unauthorized" -> context.getString(R.string.tv_unauthorized)
            "not_launchable", "no_activity" -> context.getString(R.string.tv_not_launchable, name)
            "storage" -> context.getString(R.string.tv_no_space, name)
            "not_apk" -> context.getString(R.string.tv_not_apk, name)
            else -> context.getString(R.string.tv_refused, name, error.message.orEmpty())
        }
    }

    // Gonzo 25/11 : « la TV » devient « la tablette » / « le smartphone » selon l'appareil piloté.
    fun string(res: Int, vararg args: Any): String =
        com.tomclaw.appsend.target.DeviceWords.adapt(context, context.getString(res, *args))

    private companion object {
        const val PULLED = "from-tv-"
    }
}
