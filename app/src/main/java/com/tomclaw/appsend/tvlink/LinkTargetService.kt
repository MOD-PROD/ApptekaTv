package com.tomclaw.appsend.tvlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tomclaw.appsend.R
import com.tomclaw.appsend.screen.linktarget.LocalLinkTarget
import com.tomclaw.appsend.screen.tv.TvLinkKeeper
import com.tomclaw.appsend.util.isTvDevice

/**
 * ATV : garde le serveur de connexion joignable en arrière-plan (TV / box / tablette).
 * Type connectedDevice + multicast (mDNS). START_STICKY.
 */
class LinkTargetService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        val app = applicationContext
        if (app.isTvDevice()) {
            TvLinkKeeper.ensureStarted(app)
        } else if (LocalLinkTarget.isEnabled(app)) {
            LocalLinkTarget.ensureRunning(app)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.link_target_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.link_target_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.link_target_notif_title))
            .setContentText(getString(R.string.link_target_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "LinkTargetService"
        private const val CHANNEL_ID = "link_target"
        private const val NOTIF_ID = 8336

        fun start(context: Context) {
            if (!isBackgroundEnabled(context)) return
            val intent = Intent(context, LinkTargetService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "cannot start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkTargetService::class.java))
        }

        fun isBackgroundEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BG, true)

        fun setBackgroundEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BG, enabled).apply()
            if (enabled) start(context) else stop(context)
        }

        private const val PREFS = "link_target_service"
        private const val KEY_BG = "background_enabled"
    }
}
