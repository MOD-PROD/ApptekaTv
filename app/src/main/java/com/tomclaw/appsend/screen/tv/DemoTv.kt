package com.tomclaw.appsend.screen.tv

import android.content.Context
import com.tomclaw.appsend.BuildConfig
import com.tomclaw.appsend.categories.Category
import com.tomclaw.appsend.dto.AppEntity
import com.tomclaw.appsend.screen.installed.InstalledAppEntity
import com.tomclaw.appsend.util.isTvDevice

/** Local fixture shared by the debug phone and its landscape TV preview. */
internal class DemoTv(context: Context) {
    private val prefs = context.getSharedPreferences("debug_demo_tv", Context.MODE_PRIVATE)

    var selected: Boolean
        get() = prefs.getBoolean("target_tv", false)
        set(value) { prefs.edit().putBoolean("target_tv", value).apply() }

    var connected: Boolean
        get() = prefs.getBoolean("connected", false)
        set(value) { prefs.edit().putBoolean("connected", value).apply() }

    fun isInstalled(app: App): Boolean = prefs.getBoolean(app.id, app.initiallyInstalled)

    fun setInstalled(app: App, installed: Boolean) {
        prefs.edit().putBoolean(app.id, installed).apply()
    }

    data class App(val id: String, val name: String, val category: Int, val initiallyInstalled: Boolean) {
        val packageName get() = "demo.apptv." + id.removePrefix("demo-")

        fun catalogEntry() = AppEntity(
            appId = id, packageName = packageName, icon = null, title = name,
            verName = "1.0", verCode = 1, time = 0, size = 8_388_608,
            rating = 0f, downloads = 0, status = 0,
            category = Category(category, mapOf("fr" to "Démo TV", "en" to "TV demo"), CATEGORY_ICON),
            exclusive = false, sourceUrl = null, abi = null,
        )

        fun installedEntry() = InstalledAppEntity(
            packageName, name, null, "1.0", 1L, 0L, 0L, 8_388_608L, null, true, null,
        )

        fun card(installed: Boolean) = mapOf(
            "id" to id,
            "name" to name,
            "category" to category,
            "downloads" to if (installed) "Installée" else "Application fictive",
        )
    }

    companion object {
        private const val CATEGORY_ICON =
            """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="#888888" d="M2 4h20v14H2z M8 20h8v2H8z"/></svg>"""

        fun onPhone(context: Context): DemoTv? =
            if (BuildConfig.DEBUG && !context.isTvDevice()) DemoTv(context) else null

        val apps = listOf(
            App("demo-orbite", "Orbite Vidéo", 37, true),
            App("demo-atlas", "Atlas météo", 37, true),
            App("demo-cine", "Ciné Boréal", 37, false),
            App("demo-prisme", "Prisme Arcade", 27, true),
            App("demo-ondes", "Ondes FM", 27, false),
            App("demo-puzzle", "Puzzle Stellaire", 27, false),
        )
    }
}
