package com.tomclaw.appsend.target

import android.content.Context
import com.tomclaw.imageloader.core.Loader
import java.io.File
import java.net.URI
import java.util.concurrent.Semaphore

/**
 * ATV (écran « Installées » en mode 📺) : icônes des applis de la TV, demandées à la TV
 * par l'ordre `app_icon`. Adresse : tvapp://<paquet>?tv=<id TV>&v=<versionCode>[&f=<APK téléchargé>].
 * Deux demandes à la fois au plus, pour ne pas saturer la liaison de la TV.
 */
class TvIconLoader(private val context: Context) : Loader {

    override val schemes: List<String>
        get() = listOf(SCHEME)

    override fun load(uriString: String, file: File): Boolean {
        return try {
            val uri = URI.create(uriString)
            val packageName = uri.authority ?: return false
            val tv = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("tv=") }?.removePrefix("tv=")
            val client = TvAppsClient.current(context)?.takeIf { it.tvId == tv } ?: return false
            // ATV (Downloaded en mode 📺) : icône lue dans l'APK téléchargé par la TV.
            val apkName = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("f=") }
                ?.removePrefix("f=")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            gate.acquire()
            val png = try {
                if (apkName != null) client.downloadIcon(apkName) else client.icon(packageName)
            } finally {
                gate.release()
            }
            if (png.isEmpty()) return false
            file.writeBytes(png)
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val SCHEME = "tvapp"
        private val gate = Semaphore(2)

        fun uri(tvId: String, packageName: String, versionCode: Long) =
            "$SCHEME://$packageName?tv=$tvId&v=$versionCode"

        /** Icône d'un APK téléchargé par la TV (fichier [fileName] de son dossier Appteka). */
        fun downloadUri(tvId: String, packageName: String, fileName: String, modified: Long) =
            "$SCHEME://${packageName.ifBlank { "apk" }}?tv=$tvId&v=$modified&f=" +
                java.net.URLEncoder.encode(fileName, "UTF-8")
    }
}
