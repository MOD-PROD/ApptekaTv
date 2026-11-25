package com.tomclaw.appsend.screen.linktarget

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tomclaw.appsend.R

/**
 * ATV proximité (Gonzo 25/11) : un appareil vu sur le Wi-Fi demande à s'associer. On ouvre
 * l'écran du code ici ; l'association elle-même exige toujours ce code, saisi sur l'autre appareil.
 * Appli au premier plan : l'écran s'ouvre directement. En arrière-plan, Android interdit d'ouvrir
 * un écran (Android 10+) : une notification le propose.
 */
internal object PairInvites {

    const val EXTRA_SHOW_PAIRING = "atv_show_pairing"
    private const val CHANNEL_ID = "pair_invite"
    private const val NOTIFICATION_ID = 8337

    fun show(app: Application, device: String, screen: Class<out Activity>) {
        val intent = Intent(app, screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_SHOW_PAIRING, true)
        val foreground = LocalLinkTarget.currentActivity() != null
        if (foreground || Build.VERSION.SDK_INT < 29) {
            Toast.makeText(app, app.getString(R.string.pair_invite_toast, device), Toast.LENGTH_LONG).show()
            runCatching { app.startActivity(intent) }.onSuccess { return }
        }
        notify(app, device, intent)
    }

    @SuppressLint("MissingPermission") // refusée : runCatching, rien ne s'affiche
    private fun notify(app: Application, device: String, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, app.getString(R.string.pair_invite_channel),
                    NotificationManager.IMPORTANCE_HIGH))
        }
        val pending = PendingIntent.getActivity(app, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(app.getString(R.string.pair_invite_title, device))
            .setContentText(app.getString(R.string.pair_invite_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }
    }
}
