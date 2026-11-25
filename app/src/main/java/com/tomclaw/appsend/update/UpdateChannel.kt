package com.tomclaw.appsend.update

import android.content.Context

/** ATV: canal de mise à jour choisi sur le téléphone (stable, beta ou test), mémorisé. */
object UpdateChannel {
    private const val PREFS = "self_update"
    private const val KEY = "channel"
    val CHANNELS = listOf("stable", "beta", "test")
    private val LABELS = listOf(com.tomclaw.appsend.R.string.atv_channel_stable, com.tomclaw.appsend.R.string.atv_channel_beta, com.tomclaw.appsend.R.string.atv_channel_test)

    fun labels(context: Context): List<String> = LABELS.map(context::getString)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it in CHANNELS } ?: "stable"

    fun set(context: Context, channel: String) {
        require(channel in CHANNELS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, channel).apply()
    }

    fun label(context: Context, channel: String): String =
        CHANNELS.indexOf(channel).takeIf { it >= 0 }?.let { context.getString(LABELS[it]) } ?: channel
}
