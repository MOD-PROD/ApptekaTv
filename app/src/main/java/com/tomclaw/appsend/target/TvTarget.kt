package com.tomclaw.appsend.target

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.tvlink.TvLinkSender
import org.json.JSONObject

/**
 * ATV-03 : cible choisie par l'icône 📱/📺 de l'écran Store, partagée par les
 * écrans natifs du téléphone (fiche d'appli…) et mémorisée de façon chiffrée.
 * `null` = « Ce téléphone ».
 */
object TvTarget {
    private const val PREFS = "tv_target_v2"
    private const val KEY = "current"

    @Volatile
    private var cached: TvLinkSender.Connection? = null

    @Volatile
    private var loaded = false

    fun current(context: Context): TvLinkSender.Connection? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                cached = runCatching { read(context) }.getOrNull()
                loaded = true
            }
        }
        return cached
    }

    @Synchronized
    fun select(context: Context, connection: TvLinkSender.Connection?) {
        cached = connection
        loaded = true
        runCatching {
            val editor = preferences(context).edit()
            if (connection == null) editor.remove(KEY) else editor.putString(KEY, encode(connection))
            editor.apply()
        }
    }

    private fun read(context: Context): TvLinkSender.Connection? {
        val raw = preferences(context).getString(KEY, null) ?: return null
        val json = JSONObject(raw)
        val address = TvLink.Pairing(
            host = json.getString("host"),
            port = json.getInt("port"),
            tv = json.getString("tv"),
            secret = "",
            pin = json.getString("pin"),
        )
        val token = json.getString("token")
        require(token.matches(Regex("[0-9a-f]{64}")) && address.pin.matches(Regex("[0-9a-f]{64}")))
        return TvLinkSender.Connection(address, token, json.optString("name", "Appteka TV"))
    }

    private fun encode(connection: TvLinkSender.Connection): String = JSONObject()
        .put("host", connection.address.host)
        .put("port", connection.address.port)
        .put("tv", connection.address.tv)
        .put("pin", connection.address.pin)
        .put("token", connection.token)
        .put("name", connection.name)
        .toString()

    @Suppress("DEPRECATION")
    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
