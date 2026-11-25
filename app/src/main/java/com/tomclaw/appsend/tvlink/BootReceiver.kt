package com.tomclaw.appsend.tvlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tomclaw.appsend.screen.linktarget.LocalLinkTarget
import com.tomclaw.appsend.util.isTvDevice

/** Redémarre le serveur de connexion après un boot si souhaité. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        when {
            app.isTvDevice() -> LinkTargetService.start(app)
            LocalLinkTarget.isEnabled(app) -> LinkTargetService.start(app)
        }
    }
}
