package com.tomclaw.appsend.target

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.tomclaw.appsend.tvlink.TvLinkSender
import org.json.JSONArray

/**
 * Gonzo 25/11 : comme sur la TV (Paramètres › Architectures), le Store du téléphone ne montre
 * que les applis compatibles avec le processeur de l'appareil actif : le sien en 📱, celui de
 * l'appareil piloté sinon (appris par l'ordre « hello »). Une appli sans architecture déclarée
 * reste visible. Réglage Settings « Applis compatibles seulement » (activé par défaut).
 */
object DeviceAbis {

    const val PREF_FILTER = "atv_arch_filter"
    private const val PREFS = "device_abis_v1"

    fun filterEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_FILTER, true)

    /** Architectures de l'appareil actif ; null = inconnues (pas encore de « hello »). */
    fun current(context: Context, connection: TvLinkSender.Connection? = TvTarget.current(context)): List<String>? {
        if (connection == null) return Build.SUPPORTED_ABIS.toList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(connection.address.tv, null)
            ?: return null
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull()
    }

    /** Clé de l'état du filtre : quand elle change, la liste du Store doit être rechargée. */
    fun stateKey(context: Context): String =
        if (!filterEnabled(context)) "off" else current(context)?.joinToString(",") ?: "unknown"

    /** Mémorise les architectures annoncées par un appareil piloté ; vrai si elles ont changé. */
    fun remember(context: Context, tvId: String, abis: JSONArray?): Boolean {
        if (abis == null || abis.length() == 0) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = abis.toString()
        if (prefs.getString(tvId, null) == value) return false
        prefs.edit().putString(tvId, value).apply()
        return true
    }

    fun isAllowed(context: Context, appAbis: List<String>?): Boolean {
        if (appAbis.isNullOrEmpty() || !filterEnabled(context)) return true
        val device = current(context) ?: return true
        return appAbis.any { it == "universal" || it in device }
    }
}
