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
    /** ATV : dernière TV choisie, gardée en mode 📱 pour « Send to <TV> ». */
    private const val KEY_LAST = "last"
    /** ATV (Gonzo 25/11) : tous les appareils associés (TV, tablette, téléphone…), une icône à la fois. */
    private const val KEY_KNOWN = "known"

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
            if (connection != null) {
                editor.putString(KEY_LAST, encode(connection))
                editor.putString(KEY_KNOWN, encodeAll(knownList(context).filter { it.address.tv != connection.address.tv } + connection))
            }
            editor.apply()
        }
    }

    /** TV associée la plus récente (même revenu sur « Ce téléphone »), ou null. */
    fun last(context: Context): TvLinkSender.Connection? =
        current(context) ?: runCatching { read(context, KEY_LAST) }.getOrNull()

    /**
     * La TV a refusé le jeton : on l'oublie aussi pour « Send to <TV> » et la liste des appareils.
     * [which] = l'appareil refusé (par défaut la cible actuelle, sinon la dernière).
     */
    @Synchronized
    fun forget(context: Context, which: TvLinkSender.Connection? = null) {
        val gone = (which ?: last(context))?.address?.tv ?: return select(context, null)
        if (current(context)?.address?.tv == gone) select(context, null)
        runCatching {
            val editor = preferences(context).edit()
            if (runCatching { read(context, KEY_LAST) }.getOrNull()?.address?.tv == gone) editor.remove(KEY_LAST)
            editor.putString(KEY_KNOWN, encodeAll(knownList(context).filter { it.address.tv != gone }))
            editor.apply()
        }
    }

    /** Appareils associés mémorisés (le plus récent en dernier), pour l'appui long sur l'icône. */
    fun known(context: Context): List<TvLinkSender.Connection> {
        val list = knownList(context)
        // Association faite avant cette version : seule la dernière TV était gardée.
        val last = runCatching { read(context, KEY_LAST) }.getOrNull()
        return if (last != null && list.none { it.address.tv == last.address.tv }) listOf(last) + list else list
    }

    private fun knownList(context: Context): List<TvLinkSender.Connection> = runCatching {
        val array = org.json.JSONArray(preferences(context).getString(KEY_KNOWN, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { runCatching { decode(array.getString(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun encodeAll(list: List<TvLinkSender.Connection>): String =
        org.json.JSONArray(list.takeLast(MAX_KNOWN).map { encode(it) }).toString()

    private const val MAX_KNOWN = 10

    private fun read(context: Context, key: String = KEY): TvLinkSender.Connection? {
        val raw = preferences(context).getString(key, null) ?: return null
        return decode(raw)
    }

    private fun decode(raw: String): TvLinkSender.Connection {
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
