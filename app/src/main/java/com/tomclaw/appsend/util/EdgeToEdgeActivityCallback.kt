package com.tomclaw.appsend.util

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * Enables [androidx.activity.enableEdgeToEdge] for all [ComponentActivity] instances.
 * Registered on [Application] so individual screens stay routers without duplicated setup.
 */
class EdgeToEdgeActivityCallback : Application.ActivityLifecycleCallbacks {

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lockPhonePortrait(activity)
            applyEdgeToEdge(activity)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lockPhonePortrait(activity)
            applyEdgeToEdge(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun lockPhonePortrait(activity: Activity) {
        // ATV : affichage TV forcé sur un téléphone/tablette → paysage, comme une TV.
        if (activity.isTvDisplayForced() && !activity.isRealTvDevice()) {
            try {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } catch (_: IllegalStateException) {
                // Translucent windows on API 26 inherit the underlying orientation.
            }
            return
        }
        if (activity.isTvDevice()) return
        // Android 8.0 rejects fixed orientation for translucent activities.
        try {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_: IllegalStateException) {
            // Translucent windows on API 26 inherit the underlying portrait activity.
        }
    }

    private fun applyEdgeToEdge(activity: Activity) {
        (activity as? ComponentActivity)?.enableEdgeToEdge()
    }
}
