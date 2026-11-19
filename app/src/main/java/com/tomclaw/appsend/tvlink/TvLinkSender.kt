package com.tomclaw.appsend.tvlink

import android.app.Activity
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tomclaw.appsend.R
import org.json.JSONObject
import kotlin.concurrent.thread

class TvLinkSender(private val activity: Activity, private val appId: String, private val label: String) {
    fun pair(raw: String) = thread {
        val result = runCatching {
            val address = TvLink.Pairing.parse(raw)
            val hello = TvLink.request(address, JSONObject().put("type", "hello"))
            check(hello.optBoolean("ok") && hello.optString("tv") == address.tv)
            val prefs = preferences()
            val saved = prefs.getString(address.tv, null)
            val token = if (saved != null &&
                TvLink.request(address, JSONObject().put("type", "ping").put("token", saved)).optBoolean("ok")) {
                saved
            } else {
                val response = TvLink.request(address, JSONObject().put("type", "pair")
                    .put("device", TvLink.deviceName()).put("secret", address.secret))
                check(response.optBoolean("ok") && response.optString("tv") == address.tv)
                response.getString("token").also {
                    check(it.matches(Regex("[0-9a-f]{64}")))
                    check(prefs.edit().putString(address.tv, it).commit())
                }
            }
            Triple(address.copy(secret = ""), token, hello.optString("name", "Appteka TV"))
        }
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                result.onSuccess { (address, token, name) -> chooseAction(address, token, name) }
                    .onFailure { toast(R.string.tv_link_failed) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun preferences() = EncryptedSharedPreferences.create(
        activity,
        "tv_link_v2_client",
        MasterKey.Builder(activity).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun chooseAction(address: TvLink.Pairing, token: String, name: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.tv_link_send_title, label, name))
            .setItems(arrayOf(activity.getString(R.string.tv_link_action_open), activity.getString(R.string.tv_link_action_install))) { _, which ->
                thread {
                    val sent = runCatching {
                        TvLink.request(address, JSONObject().put("type", if (which == 0) "open" else "install")
                            .put("token", token).put("appId", appId)).optBoolean("ok")
                    }.getOrDefault(false)
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            if (sent) Toast.makeText(activity, activity.getString(R.string.tv_link_sent, name), Toast.LENGTH_SHORT).show()
                            else toast(R.string.tv_link_failed)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()
}
