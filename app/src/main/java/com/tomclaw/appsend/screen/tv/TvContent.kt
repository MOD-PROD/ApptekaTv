package com.tomclaw.appsend.screen.tv

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import com.tomclaw.appsend.util.getInstalledApplicationsCompat
import com.tomclaw.appsend.util.getPackageInfoCompat
import com.tomclaw.appsend.util.versionCodeCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ATV (vraie TV, 24/11) : ce que la TV montre au téléphone en mode 📺 — applis installées
 * par l'utilisateur et mémoire libre/totale. Réponse bornée pour tenir dans une trame (256 Ko).
 */
object TvContent {
    private const val MAX_APPS = 400
    private const val MAX_LABEL = 60

    fun build(context: Context): JSONObject {
        val pm = context.packageManager
        val apps = pm.getInstalledApplicationsCompat(0)
            .filter { info ->
                info.packageName != context.packageName &&
                    (info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
            }
            .mapNotNull { info -> runCatching { app(pm, info) }.getOrNull() }
            .sortedBy { it.optString("label").lowercase() }
            .take(MAX_APPS)
        val storage = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            JSONObject().put("free", stat.availableBytes).put("total", stat.totalBytes)
        }.getOrNull()
        return JSONObject().put("apps", JSONArray(apps)).apply { if (storage != null) put("storage", storage) }
    }

    private fun app(pm: PackageManager, info: ApplicationInfo): JSONObject {
        val pkg = pm.getPackageInfoCompat(info.packageName, 0)
        val size = info.sourceDir?.let { File(it).length() } ?: 0L
        return JSONObject()
            .put("label", info.loadLabel(pm).toString().take(MAX_LABEL))
            .put("package", info.packageName)
            .put("version", pkg.versionName.orEmpty().take(32))
            .put("code", pkg.versionCodeCompat())
            .put("size", size)
    }
}
