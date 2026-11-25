package com.tomclaw.appsend.target

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tomclaw.appsend.R
import com.tomclaw.appsend.tvlink.TvLinkSender
import com.tomclaw.appsend.util.isTvDevice

/** ATV : type d'appareil affiché par l'icône du Store (une seule icône à la fois). */
enum class DeviceKind(val id: String, @DrawableRes val icon: Int, @StringRes val label: Int) {
    PHONE("phone", R.drawable.ic_target_mobile, R.string.device_kind_phone),
    TABLET("tablet", R.drawable.ic_target_tablet, R.string.device_kind_tablet),
    TV("tv", R.drawable.ic_target_tv, R.string.device_kind_tv),
    BOX("box", R.drawable.ic_target_box, R.string.device_kind_box);

    companion object {
        fun of(id: String?): DeviceKind? = values().firstOrNull { it.id == id }
    }
}

/**
 * Détection automatique + choix manuel mémorisé (appui long sur l'icône).
 * - Cet appareil : téléphone par défaut ; tablette si plus petite largeur ≥ 600 dp sans
 *   téléphonie ; TV si mode TV/leanback.
 * - TV associée : elle annonce son type dans « hello » (champ kind) ; TV par défaut.
 * Box ou TV : seule la propriété HDMI-CEC ro.hdmi.device_type les distingue (4 = lecteur,
 * donc box) ; elle n'existe pas partout, le choix manuel reste la référence.
 */
object DeviceKinds {
    private const val PREFS = "device_kind_v1"
    private const val LOCAL = "local"

    @Volatile
    private var hdmiType: String? = null

    fun detectLocal(context: Context): DeviceKind {
        if (context.isTvDevice()) return if (isPlaybackBox()) DeviceKind.BOX else DeviceKind.TV
        val pm = context.packageManager
        val telephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val wide = context.resources.configuration.smallestScreenWidthDp >= 600
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return DeviceKind.TV
        return if (wide && !telephony) DeviceKind.TABLET else DeviceKind.PHONE
    }

    private fun isPlaybackBox(): Boolean {
        val type = hdmiType ?: runCatching {
            ProcessBuilder("getprop", "ro.hdmi.device_type").redirectErrorStream(true).start()
                .inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("").also { hdmiType = it }
        // Valeurs possibles « 4 », « 0 », « 0,4 »… ; 0 = écran TV, 4 = lecteur (box).
        val parts = type.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        return "4" in parts && "0" !in parts
    }

    /** Type affiché pour [connection] (null = cet appareil). */
    fun current(context: Context, connection: TvLinkSender.Connection?): DeviceKind =
        chosen(context, connection) ?: detected(context, connection)

    fun chosen(context: Context, connection: TvLinkSender.Connection?): DeviceKind? =
        DeviceKind.of(prefs(context).getString(key(connection), null))

    fun detected(context: Context, connection: TvLinkSender.Connection?): DeviceKind =
        if (connection == null) detectLocal(context)
        else DeviceKind.of(prefs(context).getString("detected:" + key(connection), null)) ?: DeviceKind.TV

    /** null = revenir à la détection automatique. */
    fun choose(context: Context, connection: TvLinkSender.Connection?, kind: DeviceKind?) {
        val editor = prefs(context).edit()
        if (kind == null) editor.remove(key(connection)) else editor.putString(key(connection), kind.id)
        editor.apply()
    }

    /** Type annoncé par la TV ; renvoie true si l'affichage doit changer. */
    fun remember(context: Context, connection: TvLinkSender.Connection, announced: String?): Boolean {
        val kind = DeviceKind.of(announced) ?: return false
        val k = "detected:" + key(connection)
        if (prefs(context).getString(k, null) == kind.id) return false
        prefs(context).edit().putString(k, kind.id).apply()
        return true
    }

    /** Icône de la TV associée (écrans Installées/Downloaded en mode TV). */
    @DrawableRes
    fun remoteIcon(context: Context): Int {
        val tv = TvTarget.current(context) ?: TvTarget.last(context) ?: return DeviceKind.TV.icon
        return current(context, tv).icon
    }

    /** Gonzo 25/11 : nom donné par l'utilisateur, sinon celui annoncé par l'appareil. */
    fun displayName(context: Context, connection: TvLinkSender.Connection): String =
        prefs(context).getString("name:" + key(connection), null)?.takeIf { it.isNotBlank() } ?: connection.name

    fun rename(context: Context, connection: TvLinkSender.Connection, name: String) {
        val clean = name.trim().take(40)
        val editor = prefs(context).edit()
        if (clean.isEmpty()) editor.remove("name:" + key(connection)) else editor.putString("name:" + key(connection), clean)
        editor.apply()
    }

    /** Appareil supprimé de la liste : on oublie aussi son icône et son nom. */
    fun forget(context: Context, connection: TvLinkSender.Connection) {
        val k = key(connection)
        prefs(context).edit().remove(k).remove("detected:$k").remove("name:$k").apply()
    }

    private fun key(connection: TvLinkSender.Connection?) =
        if (connection == null) LOCAL else "tv:" + connection.address.tv

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
