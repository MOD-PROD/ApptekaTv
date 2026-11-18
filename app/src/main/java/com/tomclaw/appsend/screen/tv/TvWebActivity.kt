package com.tomclaw.appsend.screen.tv

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.gson.Gson
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.core.StoreApi
import com.tomclaw.appsend.download.ApkStorage
import com.tomclaw.appsend.download.COMPLETED
import com.tomclaw.appsend.download.DownloadLauncher
import com.tomclaw.appsend.download.DownloadManager
import com.tomclaw.appsend.download.DownloadParams
import com.tomclaw.appsend.dto.AppEntity
import com.tomclaw.appsend.install.AppInstaller
import com.tomclaw.appsend.install.InstallRequest
import com.tomclaw.appsend.install.InstallStatus
import com.tomclaw.appsend.screen.details.api.Details
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import com.tomclaw.appsend.util.PackageObserver
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Point d'entrée Android TV : interface HTML (liste + fiche app) branchée
 * sur les vrais services natifs d'Appteka (API connectée, téléchargement,
 * installation, suivi des paquets installés).
 */
class TvWebActivity : AppCompatActivity() {

    @Inject
    lateinit var api: StoreApi

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var appInstaller: AppInstaller

    @Inject
    lateinit var packageObserver: PackageObserver

    @Inject
    lateinit var apkStorage: ApkStorage

    private lateinit var webView: WebView
    private val disposables = CompositeDisposable()
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var splashStart = 0L
    private var splashHidden = false

    /** Fiche actuellement ouverte dans la page HTML. */
    private var currentDetails: Details? = null
    private var stateSubscription: Disposable? = null
    private var aiPollSubscription: Disposable? = null
    private var aiPollStartedAt = 0L

    /** Apps dont l'installation doit démarrer dès la fin du téléchargement. */
    private val pendingInstalls = HashMap<String, PendingInstall>()
    private val pendingSubscriptions = CompositeDisposable()

    private var pendingStorageAction: (() -> Unit)? = null

    private val locale: String
        get() = Locale.getDefault().language.ifBlank { "en" }

    // Doivent être créés pendant la construction de l'activité
    private val downloadLauncher = DownloadLauncher(this)

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else toast("Autorisation de stockage refusée")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent.inject(activity = this)
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

    override fun onResume() {
        super.onResume()
        // Retour d'une désinstallation / d'un lancement : rafraîchit l'état
        currentDetails?.let { subscribeState(it) }
    }

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

    // ---------------------------------------------------------------- JS

    private fun js(function: String, payload: Any?) {
        val json = gson.toJson(payload)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace(" ", "")
            .replace(" ", "")
        webView.evaluateJavascript("window.$function && window.$function('$json');", null)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------- Listes

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
        disposables.add(
            api.getTopList(appId = offsetAppId, locale = locale, categoryId = categoryId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response ->
                    val files = response.result.files
                    val all = accumulated + files
                    // Affiche dès la 1re page, puis complète avec les suivantes
                    js("loadTvAppsJson", mapOf("key" to key, "apps" to all.map(TvDetailsMapper::card)))
                    hideSplash()
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
        )
    }

    private fun searchApps(query: String) {
        disposables.add(
            api.searchApps(query = query, tags = null, offset = 0, locale = locale)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response ->
                    js("onSearchResults", response.result.files.map(TvDetailsMapper::card))
                }, { error ->
                    Log.e(TAG, "Search failed", error)
                    js("onSearchResults", emptyList<Any>())
                    toast("Erreur réseau")
                })
        )
    }

    // ----------------------------------------------------------- Fiche

    private fun loadDetails(appId: String, silent: Boolean = false) {
        disposables.add(
            api.getInfo(appId = appId, packageName = null, locale = locale)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response ->
                    val details = response.result
                    val sameApp = currentDetails?.info?.appId == details.info.appId
                    currentDetails = details
                    js("onDetails", TvDetailsMapper.details(details, locale))
                    if (!sameApp || !silent) subscribeState(details)
                    ensureAIPolling(details)
                }, { error ->
                    Log.e(TAG, "Failed to load details $appId", error)
                    if (!silent) js("onDetailsError", mapOf("id" to appId))
                })
        )
    }

    /** Suit l'état installé / téléchargement / installation de la fiche ouverte. */
    private fun subscribeState(details: Details) {
        stateSubscription?.dispose()
        val appId = details.info.appId
        val packageName = details.info.packageName
        stateSubscription = packageObserver.observe(packageName)
            .switchMap { installedVersionCode ->
                Observable.combineLatest(
                    downloadManager.status(appId),
                    appInstaller.status(appId),
                ) { download, install ->
                    mapOf(
                        "id" to appId,
                        "installedVersionCode" to installedVersionCode,
                        "installedVersionName" to installedVersionName(packageName),
                        "download" to download,
                        "install" to installStateName(install),
                        "installPercent" to (install as? InstallStatus.Writing)?.percent,
                        "launchable" to (packageManager.getLaunchIntentForPackage(packageName) != null),
                        "pending" to pendingInstalls.containsKey(appId),
                    )
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state -> js("onAppState", state) }, { Log.e(TAG, "state failed", it) })
    }

    private fun installedVersionName(packageName: String): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    private fun installStateName(status: InstallStatus): String = when (status) {
        InstallStatus.Idle -> "idle"
        is InstallStatus.Writing -> "writing"
        InstallStatus.Installing -> "installing"
        InstallStatus.Confirming -> "confirming"
    }

    // ------------------------------------------------------ AI summary

    private fun requestAISummary(appId: String) {
        disposables.add(
            api.requestAIReview(appId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response ->
                    response.result.message?.takeIf { it.isNotBlank() }?.let { toast(it) }
                    aiPollStartedAt = 0L
                    loadDetails(appId, silent = true)
                }, { error ->
                    Log.e(TAG, "AI summary request failed", error)
                    js("onAIError", mapOf("id" to appId))
                    toast("Résumé IA indisponible (connexion au compte requise ?)")
                })
        )
    }

    private fun ensureAIPolling(details: Details) {
        if (details.meta?.aiStatus != AI_STATUS_PENDING) {
            aiPollSubscription?.dispose()
            aiPollStartedAt = 0L
            return
        }
        if (aiPollSubscription?.isDisposed == false) return
        if (aiPollStartedAt == 0L) aiPollStartedAt = System.currentTimeMillis()
        if (System.currentTimeMillis() - aiPollStartedAt > AI_POLL_TIMEOUT_MS) return
        val appId = details.info.appId
        aiPollSubscription = Observable.timer(AI_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                if (currentDetails?.info?.appId == appId) loadDetails(appId, silent = true)
            }, {})
    }

    // ------------------------------------------- Installer / supprimer

    private data class PendingInstall(val label: String, val version: String)

    /** Télécharge puis installe automatiquement une app (depuis une carte ou la fiche). */
    private fun installApp(appId: String) {
        val details = currentDetails?.takeIf { it.info.appId == appId }
        if (details != null) {
            withStoragePermission { startInstall(details) }
            return
        }
        toast("Préparation du téléchargement…")
        disposables.add(
            api.getInfo(appId = appId, packageName = null, locale = locale)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response ->
                    withStoragePermission { startInstall(response.result) }
                }, { error ->
                    Log.e(TAG, "Failed to get app info for download", error)
                    toast("Impossible de démarrer le téléchargement")
                })
        )
    }

    private fun startInstall(details: Details) {
        val info = details.info
        val label = info.label ?: info.packageName
        pendingInstalls[info.appId] = PendingInstall(label, info.version)

        // Fichier déjà téléchargé : installation directe
        if (downloadManager.exists(label, info.version, info.appId)) {
            installDownloaded(info.appId)
            return
        }

        downloadLauncher.launch(
            DownloadParams(
                label = label,
                version = info.version,
                icon = info.icon,
                appId = info.appId,
                url = details.url,
                sha1 = info.sha1
            )
        )
        js("onDownloadStarted", mapOf("id" to info.appId, "name" to label))

        // Installe dès que le téléchargement est terminé
        pendingSubscriptions.add(
            downloadManager.status(info.appId)
                .filter { it == COMPLETED }
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ installDownloaded(info.appId) }, {})
        )
    }

    private fun installDownloaded(appId: String, attempt: Int = 0) {
        val pending = pendingInstalls[appId] ?: return
        val uri = downloadManager.getInstallUri(pending.label, pending.version, appId)
        if (uri == null) {
            // MediaStore peut mettre un instant à exposer le fichier
            if (attempt < INSTALL_RETRY_COUNT) {
                handler.postDelayed({ installDownloaded(appId, attempt + 1) }, INSTALL_RETRY_DELAY_MS)
            } else {
                pendingInstalls.remove(appId)
                toast("Fichier introuvable, réessaie l'installation")
            }
            return
        }
        pendingInstalls.remove(appId)
        appInstaller.install(InstallRequest(key = appId, label = pending.label, uri = uri))
    }

    private fun withStoragePermission(action: () -> Unit) {
        if (!apkStorage.isPermissionRequired()) {
            action()
            return
        }
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingStorageAction = action
            storagePermissionLauncher.launch(permission)
        }
    }

    private fun cancelDownload(appId: String) {
        pendingInstalls.remove(appId)
        downloadManager.cancel(appId)
        appInstaller.cancel(appId)
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)
        val launched = intent != null && try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
        if (!launched) toast("Cette app ne peut pas être ouverte directement")
    }

    private fun uninstallApp(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()))
        } catch (e: Exception) {
            Log.e(TAG, "uninstall failed", e)
            toast("Désinstallation impossible")
        }
    }

    private fun openNativeDetails(appId: String) {
        try {
            startActivity(
                createDetailsActivityIntent(
                    context = this,
                    appId = appId,
                    label = "",
                    moderation = false,
                    finishOnly = false
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "openNativeDetails failed", e)
            toast("Impossible d'ouvrir l'app")
        }
    }

    // -------------------------------------------------------- Bridge

    inner class TvJsBridge {

        /** Ouvre la fiche HTML (remplace l'écran de détails natif). */
        @JavascriptInterface
        fun openAppDetails(appId: String): Unit = runOnUiThread { loadDetails(appId) }

        @JavascriptInterface
        fun reloadDetails(appId: String): Unit = runOnUiThread { loadDetails(appId, silent = true) }

        /** La fiche HTML est fermée : on arrête le suivi. */
        @JavascriptInterface
        fun closeDetails(): Unit = runOnUiThread {
            currentDetails = null
            stateSubscription?.dispose()
            aiPollSubscription?.dispose()
        }

        /** Écran natif complet (avis, discussion…), en secours. */
        @JavascriptInterface
        fun openNativeDetails(appId: String): Unit = runOnUiThread { this@TvWebActivity.openNativeDetails(appId) }

        @JavascriptInterface
        fun install(appId: String): Unit = runOnUiThread { installApp(appId) }

        /** Ancien nom, utilisé par les cartes. */
        @JavascriptInterface
        fun downloadApp(appId: String): Unit = runOnUiThread { installApp(appId) }

        @JavascriptInterface
        fun cancel(appId: String): Unit = runOnUiThread { cancelDownload(appId) }

        @JavascriptInterface
        fun launch(packageName: String): Unit = runOnUiThread { launchApp(packageName) }

        @JavascriptInterface
        fun uninstall(packageName: String): Unit = runOnUiThread { uninstallApp(packageName) }

        @JavascriptInterface
        fun requestAISummary(appId: String): Unit = runOnUiThread { requestAISummary(appId) }

        @JavascriptInterface
        fun search(query: String): Unit = runOnUiThread { searchApps(query) }

        @JavascriptInterface
        fun showToast(message: String): Unit = runOnUiThread { toast(message) }

        @JavascriptInterface
        fun getVersion(): String = "1.1-tv"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // La page gère d'abord Retour (fermer la fiche, sortir de la saisie…)
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
        stateSubscription?.dispose()
        aiPollSubscription?.dispose()
        pendingSubscriptions.clear()
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
        private const val AI_STATUS_PENDING = "pending"
        private const val AI_POLL_INTERVAL_MS = 4000L
        private const val AI_POLL_TIMEOUT_MS = 3 * 60 * 1000L
        private const val INSTALL_RETRY_COUNT = 5
        private const val INSTALL_RETRY_DELAY_MS = 700L

        fun createIntent(context: Context): Intent {
            return Intent(context, TvWebActivity::class.java)
        }
    }
}
