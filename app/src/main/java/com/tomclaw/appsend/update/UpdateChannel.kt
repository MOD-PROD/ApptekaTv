package com.tomclaw.appsend.update

import android.content.Context

/** ATV: canal de mise à jour choisi sur le téléphone (stable, beta ou test), mémorisé. */
object UpdateChannel {
    private const val PREFS = "self_update"
    private const val KEY = "channel"
    val CHANNELS = listOf("stable", "beta", "test")
    val LABELS = listOf("Stable", "Bêta", "Test")

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it in CHANNELS } ?: "stable"

    fun set(context: Context, channel: String) {
        require(channel in CHANNELS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, channel).apply()
    }

    fun label(channel: String): String = LABELS[CHANNELS.indexOf(channel).coerceAtLeast(0)]
}
