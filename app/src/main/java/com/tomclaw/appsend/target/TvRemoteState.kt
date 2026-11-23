package com.tomclaw.appsend.target

import com.tomclaw.appsend.download.AWAIT
import com.tomclaw.appsend.download.COMPLETED
import com.tomclaw.appsend.download.ERROR
import com.tomclaw.appsend.download.STARTED
import com.tomclaw.appsend.install.InstallStatus
import org.json.JSONObject

/**
 * ATV-03 : état d'une installation demandée par le téléphone, tel que la TV
 * le décrit dans sa réponse `status` (canal v2 chiffré).
 */
data class TvRemoteState(val state: String, val percent: Int = -1, val reason: String = "") {

    val isFinal: Boolean get() = state == INSTALLED || state == FAILED || state == UNKNOWN

    fun toJson(): JSONObject = JSONObject().put("state", state).put("percent", percent).put("reason", reason)

    companion object {
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val INSTALLING = "installing"
        const val CONFIRM = "confirm"
        const val INSTALLED = "installed"
        const val FAILED = "failed"
        const val UNKNOWN = "unknown"

        const val REASON_INFO = "info"
        const val REASON_DOWNLOAD = "download"
        const val REASON_INSTALL = "install"

        private val STATES = setOf(QUEUED, DOWNLOADING, INSTALLING, CONFIRM, INSTALLED, FAILED, UNKNOWN)

        /** Lecture défensive de la réponse de la TV (côté téléphone). */
        fun parse(json: JSONObject?): TvRemoteState? {
            json ?: return null
            val state = json.optString("state")
            if (state !in STATES) return null
            val percent = json.optInt("percent", -1).takeIf { it in 0..100 } ?: -1
            val reason = json.optString("reason").take(32).filter { it.isLetterOrDigit() || it == '_' }
            return TvRemoteState(state, percent, reason)
        }

        /** Calcul côté TV à partir des états natifs de téléchargement et d'installation. */
        fun from(
            download: Int,
            install: InstallStatus,
            installed: Boolean,
            sawInstall: Boolean,
            failure: String?,
        ): TvRemoteState = when {
            failure != null -> TvRemoteState(FAILED, reason = failure)
            installed -> TvRemoteState(INSTALLED)
            install == InstallStatus.Confirming -> TvRemoteState(CONFIRM)
            install is InstallStatus.Writing -> TvRemoteState(INSTALLING, install.percent.coerceIn(0, 100))
            install == InstallStatus.Installing -> TvRemoteState(INSTALLING)
            sawInstall -> TvRemoteState(FAILED, reason = REASON_INSTALL)
            download == ERROR -> TvRemoteState(FAILED, reason = REASON_DOWNLOAD)
            download == COMPLETED -> TvRemoteState(INSTALLING)
            download == AWAIT || download == STARTED -> TvRemoteState(DOWNLOADING)
            download in 0..100 -> TvRemoteState(DOWNLOADING, download)
            else -> TvRemoteState(QUEUED)
        }

        /** Même règle que la fiche d'origine, mais avec les ABI de la TV (envoyées au `hello`). */
        fun abiCompatible(appAbis: List<String>?, deviceAbis: List<String>): Boolean {
            if (appAbis.isNullOrEmpty() || "universal" in appAbis) return true
            if (deviceAbis.isEmpty()) return true
            return appAbis.any { it in deviceAbis }
        }
    }
}
