package com.tomclaw.appsend.tvlink

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tomclaw.appsend.R
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Portable : « Envoyer à la TV » depuis la fiche d'une app.
 * Recherche des Appteka TV du Wi-Fi -> choix de la TV -> association par code (1re fois)
 * -> ouvrir la fiche ou installer sur la TV.
 */
class TvLinkSender(private val activity: Activity, private val appId: String, private val label: String) {

    private val prefs = activity.getSharedPreferences("tv_link_client", Context.MODE_PRIVATE)

    fun start() {
        val searching = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tv_link_title)
            .setMessage(R.string.tv_link_searching)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        TvLink.discover(activity, DISCOVERY_MS) { targets ->
            if (!searching.isShowing || activity.isFinishing) return@discover
            searching.dismiss()
            chooseTarget(targets)
        }
    }

    private fun chooseTarget(targets: List<TvLink.Target>) {
        if (targets.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tv_link_title)
                .setMessage(R.string.tv_link_none_found)
                .setPositiveButton(R.string.tv_link_enter_address) { _, _ -> askAddress() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val names = targets.map { it.name } + activity.getString(R.string.tv_link_enter_address)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tv_link_choose_tv)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < targets.size) connect(targets[which]) else askAddress()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askAddress() {
        val input = editText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input.hint = "192.168.1.20"
        input.setText(prefs.getString(KEY_LAST_ADDRESS, ""))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tv_link_enter_address)
            .setMessage(R.string.tv_link_address_hint)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                val host = text.substringBefore(':')
                val port = text.substringAfter(':', "").toIntOrNull() ?: TvLink.DEFAULT_PORT
                if (host.isNotBlank()) {
                    prefs.edit().putString(KEY_LAST_ADDRESS, text).apply()
                    connect(TvLink.Target(host, host, port))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tokenKey(target: TvLink.Target) = "token_" + target.name

    private fun connect(target: TvLink.Target) {
        val token = prefs.getString(tokenKey(target), null)
        background(target, JSONObject().put("type", "hello").put("token", token)) { response ->
            if (response.optBoolean("paired")) chooseAction(target, token!!) else pair(target)
        }
    }

    private fun pair(target: TvLink.Target) {
        val device = TvLink.deviceName()
        background(target, JSONObject().put("type", "pair_request").put("device", device)) {
            val input = editText(InputType.TYPE_CLASS_NUMBER)
            input.hint = "0000"
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.tv_link_pair_title, target.name))
                .setMessage(R.string.tv_link_pair_message)
                .setView(wrap(input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val request = JSONObject().put("type", "pair_confirm")
                        .put("device", device).put("code", input.text.toString().trim())
                    background(target, request) { r ->
                        if (r.optBoolean("ok")) {
                            val token = r.optString("token")
                            prefs.edit().putString(tokenKey(target), token).apply()
                            chooseAction(target, token)
                        } else {
                            toast(R.string.tv_link_bad_code)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun chooseAction(target: TvLink.Target, token: String) {
        val actions = arrayOf(
            activity.getString(R.string.tv_link_action_open),
            activity.getString(R.string.tv_link_action_install),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.tv_link_send_title, label, target.name))
            .setItems(actions) { _, which ->
                val type = if (which == 0) "open" else "install"
                val request = JSONObject().put("type", type).put("token", token).put("appId", appId)
                background(target, request) { r ->
                    when {
                        r.optBoolean("ok") ->
                            Toast.makeText(activity, activity.getString(R.string.tv_link_sent, target.name), Toast.LENGTH_SHORT).show()
                        r.optString("error") == "unauthorized" -> {
                            // la TV a oublié ce portable : on refait l'association
                            prefs.edit().remove(tokenKey(target)).apply()
                            pair(target)
                        }
                        else -> toast(R.string.tv_link_failed)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Requête réseau hors du thread principal, puis suite sur le thread principal. */
    private fun background(target: TvLink.Target, message: JSONObject, then: (JSONObject) -> Unit) {
        thread {
            val result = runCatching { TvLink.request(target.host, target.port, message) }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                result.onSuccess(then).onFailure { toast(R.string.tv_link_unreachable) }
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()

    private fun editText(type: Int) = EditText(activity).apply {
        inputType = type
        isSingleLine = true
    }

    private fun wrap(view: EditText): FrameLayout {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        return FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view)
        }
    }

    companion object {
        private const val DISCOVERY_MS = 3000L
        private const val KEY_LAST_ADDRESS = "last_address"
    }
}
