package com.tomclaw.appsend.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.tomclaw.appsend.BuildConfig

/**
 * Détecte un appareil TV (Android TV, Google TV, box Android, Fire TV...).
 * Beaucoup de box ne déclarent pas le mode TV : on regarde aussi
 * leanback et l'absence d'écran tactile.
 */
fun Context.isTvDevice(): Boolean {
    if (BuildConfig.FORCE_TV_SIMULATOR) return true
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = packageManager
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    if (pm.hasSystemFeature("android.software.leanback_only")) return true
    if (pm.hasSystemFeature("amazon.hardware.fire_tv")) return true
    return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}
