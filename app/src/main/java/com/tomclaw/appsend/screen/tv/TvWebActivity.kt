package com.tomclaw.appsend.screen.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.tomclaw.appsend.core.HOST_URL
import com.tomclaw.appsend.core.StoreApi
import com.tomclaw.appsend.download.DownloadLauncher
import com.tomclaw.appsend.download.DownloadParams
import com.tomclaw.appsend.dto.AppEntity
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Android TV entry point: HTML UI + real TV apps + download via native pipeline.
 */
class TvWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val disposables = CompositeDisposable()
    private val gson = Gson()

    // Must be created while the activity is being constructed (registers permission launcher)
    private val downloadLauncher = DownloadLauncher(this) {
        Toast.makeText(this, "Téléchargement démarré", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        webView.loadUrl("file:///android_asset/tv/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadTvApps()
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(TvJsBridge(), "Android")
    }

    private fun loadTvApps() {
        val api = createStoreApi()
        val locale = Locale.getDefault().language.ifBlank { "en" }
        val tags = "tv,android tv,live tv"

        val disposable = api.searchApps(
            query = null,
            tags = tags,
            offset = 0,
            locale = locale
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                val apps = response.result.files
                injectAppsIntoWebView(apps)
            }, { error ->
                Log.e(TAG, "Failed to load TV apps", error)
                webView.evaluateJavascript(
                    "document.getElementById('status').textContent = 'Erreur réseau – données de démo';",
                    null
                )
            })

        disposables.add(disposable)
    }

    private fun injectAppsIntoWebView(apps: List<AppEntity>) {
        val simplified = apps.map { app ->
            mapOf(
                "id" to app.appId,
                "name" to app.title,
                "icon" to (app.icon ?: ""),
                "downloads" to formatDownloads(app.downloads),
                "size" to formatSize(app.size),
                "package" to app.packageName
            )
        }

        val json = gson.toJson(simplified)
        val safeJson = json
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")

        webView.evaluateJavascript("window.loadTvApps('$safeJson');", null)
    }

    private fun startDownloadForApp(appId: String) {
        val api = createStoreApi()
        val locale = Locale.getDefault().language.ifBlank { "en" }

        Toast.makeText(this, "Préparation du téléchargement…", Toast.LENGTH_SHORT).show()

        val disposable = api.getInfo(
            appId = appId,
            packageName = null,
            locale = locale
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                val details = response.result
                val info = details.info
                val params = DownloadParams(
                    label = info.label ?: info.packageName,
                    version = info.version,
                    icon = info.icon,
                    appId = info.appId,
                    url = details.url,
                    sha1 = info.sha1
                )
                downloadLauncher.launch(params)
                // Notify HTML
                webView.evaluateJavascript(
                    "window.onDownloadStarted && window.onDownloadStarted('${info.appId}');",
                    null
                )
            }, { error ->
                Log.e(TAG, "Failed to get app info for download", error)
                Toast.makeText(
                    this,
                    "Impossible de démarrer le téléchargement",
                    Toast.LENGTH_SHORT
                ).show()
            })

        disposables.add(disposable)
    }

    private fun formatDownloads(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
            count >= 1_000 -> String.format("%.1fk", count / 1_000f)
            else -> count.toString()
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024f * 1024f)
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024f)
    }

    private fun createStoreApi(): StoreApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .client(client)
            .baseUrl("$HOST_URL/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .build()
            .create(StoreApi::class.java)
    }

    inner class TvJsBridge {

        @JavascriptInterface
        fun openAppDetails(appId: String) {
            runOnUiThread {
                try {
                    val intent = createDetailsActivityIntent(
                        context = this@TvWebActivity,
                        appId = appId,
                        label = "",
                        moderation = false,
                        finishOnly = false
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@TvWebActivity,
                        "Impossible d'ouvrir l'app",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.e(TAG, "openAppDetails failed", e)
                }
            }
        }

        @JavascriptInterface
        fun downloadApp(appId: String) {
            runOnUiThread {
                startDownloadForApp(appId)
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@TvWebActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getVersion(): String = "1.0-tv"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TvWebActivity"

        fun createIntent(context: Context): Intent {
            return Intent(context, TvWebActivity::class.java)
        }
    }
}
