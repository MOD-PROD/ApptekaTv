package com.tomclaw.appsend.tvlink

import android.app.Activity
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tomclaw.appsend.R
import org.json.JSONObject
import kotlin.concurrent.thread

class TvLinkSender(
    private val activity: Activity,
    private val appId: String = "",
    private val label: String = "",
    private val onAssociated: ((Connection) -> Unit)? = null,
) {
    class Connection internal constructor(
        internal val address: TvLink.Pairing,
        internal val token: String,
        val name: String,
    )

    private fun associated(address: TvLink.Pairing, token: String, name: String) {
        val callback = onAssociated
        if (callback != null) callback(Connection(address, token, name))
        else chooseAction(address, token, name)
    }

    fun share(connection: Connection) =
        chooseAction(connection.address, connection.token, connection.name)

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
                if (!response.optBoolean("ok") || response.optString("tv") != address.tv) {
                    error(response.optString("error", "refused"))
                }
                response.getString("token").also {
                    check(it.matches(Regex("[0-9a-f]{64}")))
                    check(prefs.edit().putString(address.tv, it).commit())
                }
            }
            Triple(address.copy(secret = ""), token, hello.optString("name", "Appteka TV"))
        }
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                result.onSuccess { (address, token, name) -> associated(address, token, name) }
                    .onFailure { e ->
                        val res = when {
                            e is java.net.SocketTimeoutException ||
                                e is java.net.ConnectException ||
                                e is java.net.NoRouteToHostException -> R.string.tv_link_unreachable
                            e.message in listOf("expired", "bad_secret", "too_many_attempts") ->
                                R.string.tv_pair_code_failed
                            else -> R.string.tv_link_failed
                        }
                        toast(res)
                    }
            }
        }
    }

    fun pairCode(host: String, port: Int, code: String) = thread {
        val result = runCatching {
            val (address, response) = TvLink.pairByCode(host, port, code)
            val token = response.getString("token")
            check(token.matches(Regex("[0-9a-f]{64}")))
            check(preferences().edit().putString(address.tv, token).commit())
            Triple(address, token, response.optString("name", "Appteka TV"))
        }
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                result.onSuccess { (address, token, name) -> associated(address, token, name) }
                    .onFailure {
                        toast(if (it is java.net.SocketTimeoutException || it is java.net.ConnectException ||
                            it is java.net.NoRouteToHostException) R.string.tv_link_unreachable
                            else R.string.tv_pair_code_failed)
                    }
            }
        }
    }

    fun showPasteEntry() {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val field = android.widget.EditText(activity).apply {
            hint = "Invitation JSON (copie depuis la TV)"
            minLines = 3
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tv_pair_title)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tv_pair_confirm, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val raw = field.text.toString().trim()
            if (raw.isEmpty() || !raw.contains("\"v\"") && !raw.contains("\"host\"")) {
                field.error = "Colle l'invitation complète copiée sur la TV."
                return@setOnClickListener
            }
            dialog.dismiss()
            pair(raw)
        }
    }

    fun showCodeEntry() {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val address = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.tv_pair_address_hint)
            isSingleLine = true
        }
        val code = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.tv_pair_code_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val fields = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(address)
            addView(code)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tv_pair_title)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tv_pair_confirm, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val entered = address.text.toString().trim()
            val host = entered.substringBefore(':')
            val port = if (':' in entered) entered.substringAfter(':').toIntOrNull() else TvLink.DEFAULT_PORT
            val value = code.text.toString().trim().uppercase(java.util.Locale.ROOT)
            if (port == null || port !in 1..65535 || !host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
                address.error = activity.getString(R.string.tv_pair_address_hint)
                return@setOnClickListener
            }
            if (!value.matches(Regex("[A-Z2-9]{8}"))) {
                code.error = activity.getString(R.string.tv_pair_code_hint)
                return@setOnClickListener
            }
            dialog.dismiss()
            pairCode(host, port, value)
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
