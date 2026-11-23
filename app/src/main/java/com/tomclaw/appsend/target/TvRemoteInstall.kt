package com.tomclaw.appsend.target

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tomclaw.appsend.R
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.tvlink.TvLinkSender
import org.json.JSONObject
import java.io.IOException
import kotlin.concurrent.thread

/**
 * ATV-03 (côté téléphone) : « Installer sur la TV » depuis la fiche native.
 * L'ordre `install` part sur le canal v2 chiffré ; la TV télécharge et installe
 * elle-même (confirmation Android sur la TV). Le téléphone suit la progression
 * en interrogeant `status` et l'affiche dans un dialogue.
 */
class TvRemoteInstall(
    private val activity: Activity,
    private val connection: TvLinkSender.Connection,
) {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    @Volatile
    private var stopped = false
    private var startedAt = 0L
    private var failures = 0
    private lateinit var appId: String

    private sealed interface Outcome {
        data object Sent : Outcome
        data class Abi(val tvAbis: String) : Outcome
        data class Refused(val error: String) : Outcome
    }

    fun start(appId: String, label: String, appAbis: List<String>?) {
        this.appId = appId
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.tv_install_title, label.ifBlank { appId }, connection.name))
            .setMessage(R.string.tv_install_checking)
            .setPositiveButton(R.string.tv_install_hide, null)
            .setOnDismissListener { stopped = true }
            .show()
        thread(name = "tv-install") {
            val result = runCatching {
                val hello = TvLink.request(connection.address, JSONObject().put("type", "hello"))
                check(hello.optBoolean("ok") && hello.optString("tv") == connection.address.tv)
                val abis = hello.optJSONArray("abis")
                    ?.let { array -> List(array.length()) { array.optString(it) } }
                    .orEmpty()
                if (!TvRemoteState.abiCompatible(appAbis, abis)) {
                    Outcome.Abi(abis.joinToString(", "))
                } else {
                    val response = TvLink.request(
                        connection.address,
                        JSONObject().put("type", "install").put("token", connection.token).put("appId", appId),
                    )
                    if (response.optBoolean("ok")) Outcome.Sent
                    else Outcome.Refused(response.optString("error", "refused").take(32))
                }
            }
            ui {
                result.onSuccess { outcome ->
                    when (outcome) {
                        Outcome.Sent -> {
                            show(activity.getString(R.string.tv_install_queued))
                            startedAt = SystemClock.elapsedRealtime()
                            schedulePoll()
                        }
                        is Outcome.Abi -> finish(activity.getString(R.string.tv_install_abi, outcome.tvAbis))
                        is Outcome.Refused -> refused(outcome.error)
                    }
                }.onFailure { finish(activity.getString(R.string.tv_install_offline)) }
            }
        }
    }

    private fun refused(error: String) {
        if (error == "unauthorized") {
            // Le jeton a été révoqué sur la TV (« Oublier ») : retour à « Ce téléphone ».
            TvTarget.select(activity, null)
            finish(activity.getString(R.string.tv_install_unauthorized))
        } else {
            finish(activity.getString(R.string.tv_install_refused, error))
        }
    }

    private fun schedulePoll() {
        if (stopped) return
        main.postDelayed({ poll() }, POLL_MS)
    }

    private fun poll() {
        if (stopped) return
        if (SystemClock.elapsedRealtime() - startedAt > MAX_FOLLOW_MS) {
            finish(activity.getString(R.string.tv_install_status_lost))
            return
        }
        thread(name = "tv-install-status") {
            val result = runCatching {
                TvLink.request(
                    connection.address,
                    JSONObject().put("type", "status").put("token", connection.token).put("appId", appId),
                )
            }
            ui {
                result.onSuccess { response ->
                    failures = 0
                    if (!response.optBoolean("ok")) {
                        val error = response.optString("error")
                        if (error == "not_implemented") finish(activity.getString(R.string.tv_install_sent_no_status))
                        else refused(error)
                        return@ui
                    }
                    val state = TvRemoteState.parse(response.optJSONObject("status"))
                    if (state == null) {
                        finish(activity.getString(R.string.tv_install_sent_no_status))
                    } else {
                        show(describe(state))
                        if (!state.isFinal) schedulePoll() else stopped = true
                    }
                }.onFailure { error ->
                    failures++
                    if (error !is IOException || failures >= MAX_FAILURES) {
                        finish(activity.getString(R.string.tv_install_status_lost))
                    } else {
                        schedulePoll()
                    }
                }
            }
        }
    }

    private fun describe(state: TvRemoteState): String = when (state.state) {
        TvRemoteState.QUEUED -> activity.getString(R.string.tv_install_queued)
        TvRemoteState.DOWNLOADING -> if (state.percent >= 0) {
            activity.getString(R.string.tv_install_downloading, state.percent)
        } else activity.getString(R.string.tv_install_downloading_start)
        TvRemoteState.INSTALLING -> activity.getString(R.string.tv_install_installing)
        TvRemoteState.CONFIRM -> activity.getString(R.string.tv_install_confirm)
        TvRemoteState.INSTALLED -> activity.getString(R.string.tv_install_done)
        TvRemoteState.FAILED -> when (state.reason) {
            TvRemoteState.REASON_INFO -> activity.getString(R.string.tv_install_failed_info)
            TvRemoteState.REASON_DOWNLOAD -> activity.getString(R.string.tv_install_failed_download)
            else -> activity.getString(R.string.tv_install_failed_install)
        }
        else -> activity.getString(R.string.tv_install_lost)
    }

    private fun show(text: String) {
        dialog?.setMessage(text)
    }

    private fun finish(text: String) {
        stopped = true
        show(text)
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed && dialog?.isShowing == true) block()
    }

    private companion object {
        const val POLL_MS = 1500L
        const val MAX_FOLLOW_MS = 20 * 60 * 1000L
        const val MAX_FAILURES = 5
    }
}
