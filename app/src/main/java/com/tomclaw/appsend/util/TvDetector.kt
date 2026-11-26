package com.tomclaw.appsend.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.preference.PreferenceManager

/** ATV (Gonzo 25/11) : réglage du téléphone « Forcer l'affichage TV », remplace l'ancien simulateur. */
const val PREF_FORCE_TV = "atv_force_tv"

fun Context.isTvDisplayForced(): Boolean =
    PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_FORCE_TV, false)

fun Context.setTvDisplayForced(forced: Boolean) {
    PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_FORCE_TV, forced).apply()
}

/** Appareil en mode TV : vraie TV/box, ou téléphone/tablette avec l'affichage TV forcé. */
fun Context.isTvDevice(): Boolean = isTvDisplayForced() || isRealTvDevice()

/**
 * Détecte un appareil TV (Android TV, Google TV, box Android, Fire TV...).
 * Beaucoup de box ne déclarent pas le mode TV : on regarde aussi
 * leanback et l'absence d'écran tactile.
 */
fun Context.isRealTvDevice(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = packageManager
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    if (pm.hasSystemFeature("android.software.leanback_only")) return true
    if (pm.hasSystemFeature("amazon.hardware.fire_tv")) return true
    return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}
