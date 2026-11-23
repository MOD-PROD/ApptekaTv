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
        .followSslRedirects(false)
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
                check(response.isSuccessful)
                require(response.request.url.isHttps)
                requireNotNull(response.body).byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled()) { "Cancelled" }
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= update.size)
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            progress((total * 100 / update.size).toInt())
                        }
                    }
                }
            }
            require(total == update.size)
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == update.sha256)
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
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = requireNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags))
        require(candidate.packageName == context.packageName)
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val newCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(newCode == update.versionCode && newCode > oldCode)
        val oldSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val newSignatures = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures
        require(!oldSignatures.isNullOrEmpty() && !newSignatures.isNullOrEmpty())
        require(oldSignatures.toSet() == newSignatures.toSet())
    }
}
