package com.tomclaw.appsend.update

import org.json.JSONObject
import java.net.URI

/** Shared stable release contract for the universal TV/phone APK. */
data class ReleaseUpdate(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long
) {
    companion object {
        const val MANIFEST_URL = "https://github.com/MOD-PROD/ApptekaTv/releases/latest/download/update.json"

        fun parse(json: String, installedVersion: Long): ReleaseUpdate? {
            val obj = JSONObject(json)
            require(obj.getString("packageName") == "app.modprod.apptekatv")
            require(obj.getString("channel") == "stable")
            val code = obj.getLong("versionCode")
            if (code <= installedVersion) return null
            val name = obj.getString("versionName")
            val url = obj.getString("url")
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" &&
                uri.userInfo == null && uri.port == -1 &&
                uri.path.startsWith("/MOD-PROD/ApptekaTv/releases/download/") &&
                uri.path.endsWith("/apptekatv-release.apk"))
            val hash = obj.getString("sha256")
            require(hash.matches(Regex("[0-9a-fA-F]{64}")))
            val size = obj.getLong("size")
            require(size in 1L..536870912L)
            require(name.isNotBlank())
            return ReleaseUpdate(code, name, url, hash.lowercase(), size)
        }
    }
}
