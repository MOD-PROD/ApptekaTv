package com.tomclaw.appsend.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Blocking operations: invoke on an IO executor, never the UI thread. */
class UpdateDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .build()

    fun check(installedVersion: Long, channel: String = "stable"): ReleaseUpdate? {
        client.newCall(Request.Builder().url(ReleaseUpdate.manifestUrl(channel)).build()).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful)
            val body = requireNotNull(response.body)
            val stream = body.byteStream()
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                require(bytes.size() + n <= 65536)
                bytes.write(buffer, 0, n)
            }
            return ReleaseUpdate.parse(bytes.toString("UTF-8"), installedVersion, channel)
        }
    }

    fun download(update: ReleaseUpdate, cancelled: () -> Boolean, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "self-update").apply { mkdirs() }
        val file = File.createTempFile("update-", ".apk", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            client.newCall(Request.Builder().url(update.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw UpdateFailure("http_${response.code}")
                if (!response.request.url.isHttps) throw UpdateFailure("https")
                requireNotNull(response.body).byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled()) { "Cancelled" }
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > update.size) throw UpdateFailure("taille")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            progress((total * 100 / update.size).toInt())
                        }
                    }
                }
            }
            if (total != update.size) throw UpdateFailure("taille")
            if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != update.sha256) {
                throw UpdateFailure("sha256")
            }
            verifyPackage(file, update)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, update: ReleaseUpdate) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateFailure("apk_illisible")
        if (candidate.packageName != context.packageName) throw UpdateFailure("paquet")
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val newCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (newCode != update.versionCode || newCode <= oldCode) throw UpdateFailure("version")
        val oldSignatures = signers(installed)
        val newSignatures = signers(candidate)
        if (oldSignatures.isEmpty() || newSignatures.isEmpty()) throw UpdateFailure("signature_absente")
        if (oldSignatures != newSignatures) throw UpdateFailure("signature")
    }

    /**
     * ATV (vraie TV, 24/11) : sur Android 9 (Xiaomi MIBOX4), getPackageArchiveInfo ne remplit pas
     * signingInfo pour un APK non installé : l'OTA échouait toujours à la vérification. On prend
     * signingInfo quand il existe, sinon les signatures classiques.
     */
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): Set<android.content.pm.Signature> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        return (modern?.takeIf { it.isNotEmpty() } ?: info.signatures).orEmpty().toSet()
    }
}

/** ATV : étape où la mise à jour a échoué, affichée à l'utilisateur pour le diagnostic. */
class UpdateFailure(val code: String) : Exception(code)
