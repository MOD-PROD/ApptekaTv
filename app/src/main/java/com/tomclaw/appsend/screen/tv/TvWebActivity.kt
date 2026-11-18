package com.tomclaw.appsend.screen.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
    private val handler = Handler(Looper.getMainLooper())
    private var splashStart = 0L
    private var splashHidden = false

    private fun hideSplash() {
        if (splashHidden) return
        val elapsed = SystemClock.uptimeMillis() - splashStart
        if (elapsed < SPLASH_MIN_MS) {
            handler.postDelayed({ hideSplash() }, SPLASH_MIN_MS - elapsed)
            return
        }
        splashHidden = true
        webView.alpha = 0f
        webView.visibility = View.VISIBLE
        webView.animate().alpha(1f).setDuration(300).start()
        webView.requestFocus()
    }

    // Must be created while the activity is being constructed (registers permission launcher)
    private val downloadLauncher = DownloadLauncher(this) {
        Toast.makeText(this, "Téléchargement démarré", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        // La bannière (fond de fenêtre) reste visible jusqu'au 1er chargement des apps
        webView.visibility = View.INVISIBLE
        setContentView(webView)
        splashStart = SystemClock.uptimeMillis()
        handler.postDelayed({ hideSplash() }, SPLASH_MAX_MS)

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
        // Catégories Appteka : 37 = TV, 27 = Entertainment
        loadCategory(key = "tv", categoryId = CATEGORY_TV)
        loadCategory(key = "entertainment", categoryId = CATEGORY_ENTERTAINMENT)
    }

    private fun loadCategory(
        key: String,
        categoryId: Int,
        offsetAppId: String? = null,
        accumulated: List<AppEntity> = emptyList(),
        page: Int = 0
    ) {
        val api = createStoreApi()
        val locale = Locale.getDefault().language.ifBlank { "en" }

        val disposable = api.getTopList(
            appId = offsetAppId,
            locale = locale,
            categoryId = categoryId
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                val files = response.result.files
                val all = accumulated + files
                // Affiche dès la 1re page, puis complète avec les suivantes
                injectAppsIntoWebView(key, all)
                if (files.isNotEmpty() && page + 1 < MAX_PAGES) {
                    loadCategory(key, categoryId, files.last().appId, all, page + 1)
                }
            }, { error ->
                Log.e(TAG, "Failed to load category $categoryId", error)
                if (accumulated.isEmpty()) {
                    webView.evaluateJavascript("window.onCategoryError('$key');", null)
                    hideSplash()
                }
            })

        disposables.add(disposable)
    }

    private fun searchApps(query: String) {
        val api = createStoreApi()
        val locale = Locale.getDefault().language.ifBlank { "en" }
        val disposable = api.searchApps(
            query = query,
            tags = null,
            offset = 0,
            locale = locale
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                webView.evaluateJavascript(
                    "window.onSearchResults('${toJsJson(response.result.files)}');", null
                )
            }, { error ->
                Log.e(TAG, "Search failed", error)
                webView.evaluateJavascript("window.onSearchResults('[]');", null)
                Toast.makeText(this, "Erreur réseau", Toast.LENGTH_SHORT).show()
            })
        disposables.add(disposable)
    }

    private fun toJsJson(apps: List<AppEntity>): String {
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
        return gson.toJson(simplified)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
    }

    private fun injectAppsIntoWebView(key: String, apps: List<AppEntity>) {
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

        webView.evaluateJavascript("window.loadTvApps('$key', '$safeJson');", null)
        hideSplash()
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
        fun search(query: String) {
            runOnUiThread { searchApps(query) }
        }

        @JavascriptInterface
        fun getVersion(): String = "1.0-tv"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // La page gère d'abord Retour (sortir de la saisie, fermer les résultats)
        webView.evaluateJavascript(
            "(window.onTvBack && window.onTvBack()) ? 'yes' : 'no'"
        ) { result ->
            if (result?.contains("yes") != true) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disposables.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TvWebActivity"
        private const val CATEGORY_TV = 37
        private const val CATEGORY_ENTERTAINMENT = 27
        private const val MAX_PAGES = 5
        private const val SPLASH_MIN_MS = 1500L
        private const val SPLASH_MAX_MS = 6000L

        fun createIntent(context: Context): Intent {
            return Intent(context, TvWebActivity::class.java)
        }
    }
}
