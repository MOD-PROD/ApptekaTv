package com.tomclaw.appsend.screen.tv

import android.content.Context

/** Local fixture shared by the debug phone and its landscape TV preview. */
internal class DemoTv(context: Context) {
    private val prefs = context.getSharedPreferences("debug_demo_tv", Context.MODE_PRIVATE)

    var connected: Boolean
        get() = prefs.getBoolean("connected", false)
        set(value) { prefs.edit().putBoolean("connected", value).apply() }

    fun isInstalled(app: App): Boolean = prefs.getBoolean(app.id, app.initiallyInstalled)

    fun setInstalled(app: App, installed: Boolean) {
        prefs.edit().putBoolean(app.id, installed).apply()
    }

    data class App(val id: String, val name: String, val category: Int, val initiallyInstalled: Boolean) {
        fun card(installed: Boolean) = mapOf(
            "id" to id,
            "name" to name,
            "category" to category,
            "downloads" to if (installed) "Installée" else "Application fictive",
        )
    }

    companion object {
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
