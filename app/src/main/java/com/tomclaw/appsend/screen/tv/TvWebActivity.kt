package com.tomclaw.appsend.screen.tv

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tomclaw.appsend.BuildConfig
import com.google.gson.Gson
import com.tomclaw.appsend.R
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.target.TvRemoteState
import org.json.JSONObject
import com.tomclaw.appsend.tvlink.TvLinkSender
import com.tomclaw.appsend.util.isTvDevice
import com.tomclaw.appsend.categories.Category
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
import io.reactivex.rxjava3.core.Single
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

    private val updateController by lazy { com.tomclaw.appsend.update.UpdateController(this) }
    private lateinit var webView: WebView
    private val disposables = CompositeDisposable()
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var gravitySplashStarted = false

    /** Fiche actuellement ouverte dans la page HTML. */
    private var currentDetails: Details? = null
    private var stateSubscription: Disposable? = null
    private var aiPollSubscription: Disposable? = null
    private var aiPollStartedAt = 0L

    /** Apps dont l'installation doit démarrer dès la fin du téléchargement. */
    private val pendingInstalls = HashMap<String, PendingInstall>()
    private val pendingSubscriptions = CompositeDisposable()

    private var pendingStorageAction: (() -> Unit)? = null

    /** Langue de l'API Appteka : celle choisie dans les réglages TV, sinon celle de la TV. */
    private val locale: String
        get() = prefs.getString("js_lang", null)?.takeIf { it.isNotBlank() && it != "auto" }
            ?: Locale.getDefault().language.ifBlank { "en" }

    // Doivent être créés pendant la construction de l'activité
    private val downloadLauncher = DownloadLauncher(this)

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else toast("Autorisation de stockage refusée")
        }

    private var pendingConfig: String? = null
    private val configExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val config = pendingConfig
            pendingConfig = null
            if (uri != null && config != null) {
                val saved = runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri)).use { output ->
                        output.write(config.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
                toast(if (saved) "Configuration enregistrée" else "Impossible d’enregistrer la configuration")
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingTvConfig", pendingConfig)
        outState.putString("pendingPairApp", pendingPairApp)
        outState.putString("pendingPairLabel", pendingPairLabel)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent.inject(activity = this)
        super.onCreate(savedInstanceState)
        pendingConfig = savedInstanceState?.getString("pendingTvConfig")
        pendingPairApp = savedInstanceState?.getString("pendingPairApp")
        pendingPairLabel = savedInstanceState?.getString("pendingPairLabel")

        webView = WebView(this)
        webView.setBackgroundColor(0xffe0e5ec.toInt())
        setContentView(webView)
        applySimulatorFullscreen()
        webView.requestFocus()

        setupWebView()
        webView.loadUrl("file:///android_asset/tv/index.html")
        if (isTv) startTvLink()
        if (savedInstanceState == null) updateController.check()
    }

    // ------------------------------------------- Liaison portable -> TV

    /** TV (ou box) : interface 10 pieds ; sinon interface portable. */
    private val isTv by lazy { isTvDevice() }
    private var tvLink: TvLink.Server? = null
    private val remoteInstalls by lazy { TvRemoteInstalls(this, downloadManager, appInstaller) }
    private val remoteApps by lazy { TvRemoteApps(this) }
    private val remoteDownloads by lazy { TvRemoteDownloads(this, apkStorage) }
    private val remoteFiles by lazy { TvRemoteFiles(this) }
    private var pendingPairApp: String? = null
    private var pendingPairLabel: String? = null
    private val pairingScanner = registerForActivityResult(ScanContract()) { result ->
        val appId = pendingPairApp
        val label = pendingPairLabel
        pendingPairApp = null
        pendingPairLabel = null
        if (appId != null && result.contents != null) {
            TvLinkSender(this, appId, label ?: appId).pair(result.contents)
        }
    }

    /** Fenêtre du QR d'association ouverte (fermée automatiquement après une association). */
    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showPairingQr() {
        val server = tvLink ?: return toast("Association TV indisponible")
        val pairing = server.pairingQr() ?: return toast("Association TV indisponible")
        val size = 560
        val matrix = QRCodeWriter().encode(pairing.payload, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val image = ImageView(this).apply {
            setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888))
            adjustViewBounds = true
            // ATV: on a phone in landscape (ATKsimulTv) a full-size QR pushed the OK /
            // code buttons off screen; keep it within ~40 % of the height.
            maxHeight = if (BuildConfig.FORCE_TV_SIMULATOR) {
                minOf(size, (resources.displayMetrics.heightPixels * 0.4f).toInt())
            } else size
        }
        val hostPort = TvLink.Pairing.parse(pairing.payload).let { "${it.host}:${it.port}" }
        val dialog = if (BuildConfig.FORCE_TV_SIMULATOR) {
            // ATV: ATKsimulTv, same phone as Appteka TV: long press on the code or the address
            // copies it, to paste into "Taper le code" (the QR secret itself is never copied).
            val pad = (16 * resources.displayMetrics.density).toInt()
            fun copyable(label: String, value: String, textSize: Float) = android.widget.TextView(this).apply {
                text = "$label : $value"
                this.textSize = textSize
                setPadding(pad, pad / 2, pad, pad / 2)
                setOnLongClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    if (clipboard == null) {
                        toast("Presse-papiers indisponible")
                    } else {
                        val clip = android.content.ClipData.newPlainText(label, value)
                        // ATV-01: keep the code out of the clipboard preview.
                        if (Build.VERSION.SDK_INT >= 24) clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(if (Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE
                                else "android.content.extra.IS_SENSITIVE", true)
                        }
                        clipboard.setPrimaryClip(clip)
                        toast("$label copié")
                    }
                    true
                }
            }
            val content = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad, pad, 0)
                addView(copyable("Code", pairing.code, 22f))
                addView(copyable("Adresse", hostPort, 18f))
                addView(android.widget.TextView(this@TvWebActivity).apply {
                    text = "Appui long sur le code ou l'adresse pour les copier."
                    setPadding(pad, 0, pad, pad / 2)
                })
                addView(image)
            }
            MaterialAlertDialogBuilder(this)
                .setView(android.widget.ScrollView(this).apply { addView(content) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.tv_pair_display_code, pairing.code))
                .setMessage(getString(R.string.tv_pair_display_address, hostPort))
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        pairingDialog = dialog
        val expireDialog = Runnable { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (pairingDialog === dialog) pairingDialog = null
            handler.removeCallbacks(expireDialog)
            // ATV: keep the invitation briefly after the QR is closed (a phone may be mid-scan),
            // at most 60 s instead of the full 5 min TTL (ATV-01 durcissement).
            server.dismissPairing(pairing.sessionId)
        }
        handler.postDelayed(expireDialog, 5 * 60 * 1000L)
    }

    private fun startTvLink() {
        val server = TvLink.Server(this, object : TvLink.Host {
            override fun onPaired(device: String) {
                // ATV (vraie TV, 24/11) : la fenêtre du QR / code se ferme dès que l'association réussit.
                runOnUiThread { pairingDialog?.takeIf { it.isShowing }?.dismiss() }
                js("onPaired", mapOf("device" to device))
                // ATV-01 durcissement : une association ne doit jamais passer inaperçue.
                Toast.makeText(this@TvWebActivity, getString(R.string.tv_link_paired_notice, device), Toast.LENGTH_LONG).show()
            }

            override fun onOpen(appId: String, install: Boolean, device: String) {
                js("onRemoteOpen", mapOf("id" to appId, "install" to install, "device" to device))
                if (install) {
                    remoteInstalls.track(appId) // ATV-03: suivi pour l'ordre status
                    installApp(appId)
                }
            }

            override fun status(appId: String): JSONObject = remoteInstalls.status(appId).toJson()

            // ATV: the phone in 📺 mode lists the TV's apps and free memory.
            override fun content(): JSONObject = TvContent.build(this@TvWebActivity)

            // ATV (Installées en mode 📺) : le menu natif du téléphone agit sur les applis de la TV.
            override fun content(system: Boolean): JSONObject = TvContent.build(this@TvWebActivity, system)

            override fun appAction(type: String, packageName: String): JSONObject =
                remoteApps.action(type, packageName)

            override fun icon(packageName: String): ByteArray? = remoteApps.icon(packageName)

            override fun apkFile(packageName: String): java.io.File? = remoteApps.apkFile(packageName)

            override fun incomingApk(size: Long): java.io.File? = remoteApps.incomingApk(size)

            override fun downloads(): JSONObject = remoteDownloads.list()

            override fun downloadAction(type: String, fileName: String): JSONObject =
                remoteDownloads.action(type, fileName) { uri, label ->
                    appInstaller.install(InstallRequest(key = "download:$fileName", label = label, uri = uri))
                }

            override fun downloadIcon(fileName: String): ByteArray? = remoteDownloads.icon(fileName)

            // ATV : compte Appteka de la TV connecté depuis le téléphone en mode 📺.
            override fun accountStatus(): JSONObject = remoteAccountStatus()

            override fun accountRequestCode(email: String): JSONObject = remoteAccountRequestCode(email)

            override fun accountVerify(requestId: String, code: String, name: String, device: String): JSONObject =
                remoteAccountVerify(requestId, code, name, device)

            override fun downloadFile(fileName: String): java.io.File? = remoteDownloads.file(fileName)

            // ATV-09 : fichiers à distance.
            override fun fsAccessMode(): String = remoteFiles.accessMode()

            override fun fsRoots(): List<TvLink.FsRoot> = remoteFiles.roots()

            override fun fsRequestStorage() = requestFilesStorage()

            override fun onFilesOpened(device: String) {
                // Gonzo 24/11 : la TV montre quand un téléphone gère ses fichiers.
                Toast.makeText(this@TvWebActivity, getString(R.string.tv_files_opened, device), Toast.LENGTH_LONG).show()
            }

            override fun fsInstall(file: java.io.File, device: String): JSONObject {
                val copy = remoteApps.incomingApk(file.length()) ?: return JSONObject().put("ok", false).put("error", "storage")
                return try {
                    file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
                    remoteApps.received(copy) { uri, label ->
                        toast(getString(R.string.tv_apk_received, label, device))
                        appInstaller.install(InstallRequest(key = "fs:" + copy.name, label = label, uri = uri))
                    }
                } catch (e: Exception) {
                    copy.delete()
                    JSONObject().put("ok", false).put("error", "unreadable")
                }
            }

            override fun onApkReceived(file: java.io.File, device: String): JSONObject =
                remoteApps.received(file) { uri, label ->
                    toast(getString(R.string.tv_apk_received, label, device))
                    appInstaller.install(InstallRequest(key = "push:" + file.name, label = label, uri = uri))
                }
        })
        server.confirmPairing = ::confirmPairing // ATV-01 durcissement : accord explicite sur la TV
        server.confirmFiles = ::confirmFiles // ATV-09 : droit « Fichiers » accordé sur la TV
        runCatching { server.start() }.onFailure { Log.w(TAG, "TV link unavailable", it) }
        tvLink = server
    }

    /** ATV-01 durcissement : un téléphone n'est associé que si l'utilisateur de la TV accepte. */
    private fun confirmPairing(device: String, answer: (Boolean) -> Unit) {
        if (isFinishing || isDestroyed) return answer(false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_pair_confirm_title)
            .setMessage(getString(R.string.tv_pair_confirm_message, device))
            .setCancelable(true)
            .setPositiveButton(R.string.tv_pair_confirm_accept) { _, _ -> answer(true) }
            .setNegativeButton(R.string.tv_pair_confirm_refuse) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) } // answering twice is ignored by the server
            .show()
        // Refuse is focused first: the remote's OK key must not accept by accident.
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        handler.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 30_000L)
    }

    /** ATV-09 : un téléphone n'accède aux fichiers de la TV que si l'utilisateur de la TV accepte. */
    private fun confirmFiles(device: String, answer: (Boolean) -> Unit) {
        if (isFinishing || isDestroyed) return answer(false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_files_confirm_title)
            .setMessage(getString(R.string.tv_files_confirm_message, device))
            .setCancelable(true)
            .setPositiveButton(R.string.tv_pair_confirm_accept) { _, _ -> answer(true) }
            .setNegativeButton(R.string.tv_pair_confirm_refuse) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        handler.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 30_000L)
    }

    /** ATV-09 : le téléphone veut les fichiers mais l'appli n'a pas encore accès au stockage de la TV. */
    private var filesStorageDialog: androidx.appcompat.app.AlertDialog? = null

    private fun requestFilesStorage() {
        if (isFinishing || isDestroyed || filesStorageDialog?.isShowing == true) return
        filesStorageDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_files_storage_title)
            .setMessage(R.string.tv_files_storage_message)
            .setPositiveButton(R.string.tv_files_storage_allow) { _, _ ->
                if (remoteFiles.needsSettingsScreen) {
                    if (!remoteFiles.openAllFilesSettings()) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.tv_files_storage_title)
                            .setMessage(getString(R.string.tv_files_storage_adb, packageName))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    pendingStorageAction = { toast(getString(R.string.tv_files_storage_granted)) }
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tvLinkInfo(): Map<String, Any?> = mapOf(
        "name" to TvLink.deviceName(),
        "ip" to TvLink.localIpv4(),
        "port" to tvLink?.port,
        "devices" to (tvLink?.pairedDevices() ?: emptyList<String>()),
        "filesLog" to (tvLink?.filesLog() ?: emptyList<String>()),
    )

    // ------------------------------------- Écrans classiques (connecteurs)

    private fun openClassic(screen: String, param: String) {
        val userId = param.toIntOrNull() ?: 0
        val intent: Intent? = when (screen) {
            "upload" -> com.tomclaw.appsend.screen.upload.createUploadActivityIntent(this, null, null, null)
            "uploads" -> com.tomclaw.appsend.screen.uploads.createUploadsActivityIntent(this, userId)
            "downloads" -> com.tomclaw.appsend.screen.downloads.createDownloadsActivityIntent(this, userId)
            "installed" -> com.tomclaw.appsend.screen.installed.createInstalledActivityIntent(this)
            "favorites" -> com.tomclaw.appsend.screen.favorite.createFavoriteActivityIntent(this, userId)
            "reviews" -> com.tomclaw.appsend.screen.reviews.createReviewsActivityIntent(this, userId)
            "feed" -> com.tomclaw.appsend.screen.feed.createFeedActivityIntent(this, userId)
            "profile" -> com.tomclaw.appsend.screen.profile.createProfileActivityIntent(this, userId)
            "moderation" -> com.tomclaw.appsend.screen.moderation.createModerationActivityIntent(this)
            "settings" -> com.tomclaw.appsend.screen.settings.createSettingsActivityIntent(this)
            "about" -> com.tomclaw.appsend.screen.about.createAboutActivityIntent(this)
            "details" -> createDetailsActivityIntent(
                context = this, appId = param, label = "", moderation = false, finishOnly = false
            )
            "home" -> com.tomclaw.appsend.screen.home.createClassicHomeIntent(this)
            else -> null
        }
        if (intent == null) {
            toast("Écran indisponible")
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openClassic $screen failed", e)
            toast("Écran indisponible")
        }
    }

    /** ATV: ATKsimulTv fills the phone screen like a TV (system bars hidden, swipe to show). */
    private fun applySimulatorFullscreen() {
        if (!BuildConfig.FORCE_TV_SIMULATOR) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { applySimulatorFullscreen() }
    }

    override fun onResume() {
        super.onResume()
        // Retour d'une désinstallation / d'un lancement : rafraîchit l'état
        currentDetails?.let { subscribeState(it) }
    }

    private fun playStartupAnimation() {
        if (gravitySplashStarted || isFinishing || isDestroyed) return
        gravitySplashStarted = true
        webView.postOnAnimation {
            if (!isFinishing && !isDestroyed) js("playGravitySplash", null)
        }
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
                handler.postDelayed({ playStartupAnimation() }, GRAVITY_FALLBACK_MS)
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

    /** Message court, traduit par le dictionnaire de la page (i18n.js) quand il existe. */
    private fun toast(text: String) {
        if (!::webView.isInitialized) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return
        }
        val quoted = gson.toJson(text)
        webView.evaluateJavascript("(window.TvI18n ? window.TvI18n.t($quoted) : $quoted)") { result ->
            val translated = runCatching { gson.fromJson(result, String::class.java) }.getOrNull()
            Toast.makeText(this, translated?.takeIf { it.isNotBlank() } ?: text, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------- Listes

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    /** Les 42 catégories Appteka (nom localisé + icône SVG), avec cache hors ligne. */
    private fun loadCategories() {
        disposables.add(
            api.getCategories()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response ->
                    val list = response.result.categories.map { categoryJson(it) }
                    prefs.edit().putString(KEY_CATEGORIES_CACHE, gson.toJson(list)).apply()
                    js("onCategories", list)
                }, { error ->
                    Log.e(TAG, "Failed to load categories", error)
                    val cache = prefs.getString(KEY_CATEGORIES_CACHE, null)
                    if (cache != null) {
                        js("onCategories", gson.fromJson(cache, List::class.java))
                    } else {
                        js("onCategories", emptyList<Any>())
                    }
                })
        )
    }

    private fun categoryJson(category: Category): Map<String, Any> = mapOf(
        "id" to category.id,
        "name" to (category.name[locale] ?: category.name["en"] ?: category.name.values.firstOrNull() ?: ""),
        "icon" to category.icon,
    )

    /**
     * Charge une rangée. type = "category" (param = id), "all", "profile" (param = n° de profil).
     * Plusieurs pages sont enchaînées ; la 1re s'affiche immédiatement.
     */
    private fun loadSource(key: String, type: String, param: String) {
        val fetch: (String?) -> Single<List<AppEntity>> = when (type) {
            "category" -> { offset ->
                api.getTopList(appId = offset, locale = locale, categoryId = param.toIntOrNull())
                    .map { it.result.files }
            }
            "profile" -> { offset ->
                api.getUserApps(userId = param.toIntOrNull(), appId = offset)
                    .map { it.result.files }
            }
            else -> { offset ->
                api.getTopList(appId = offset, locale = locale).map { it.result.files }
            }
        }
        loadPages(key, fetch, offset = null, accumulated = emptyList(), page = 0)
    }

    private fun loadPages(
        key: String,
        fetch: (String?) -> Single<List<AppEntity>>,
        offset: String?,
        accumulated: List<AppEntity>,
        page: Int,
    ) {
        disposables.add(
            fetch(offset)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ files ->
                    val all = (accumulated + files).distinctBy { it.appId }
                    js("loadTvAppsJson", mapOf("key" to key, "apps" to all.map(TvDetailsMapper::card)))
                    if (all.isNotEmpty()) playStartupAnimation()
                    if (files.isNotEmpty() && page + 1 < MAX_PAGES) {
                        loadPages(key, fetch, files.last().appId, all, page + 1)
                    }
                }, { error ->
                    Log.e(TAG, "Failed to load source $key", error)
                    if (accumulated.isEmpty()) {
                        // ATV (Gonzo 25/11, « Erreur réseau » sur toutes les rangées) : la cause exacte
                        // s'affiche dans la rangée pour le diagnostic ; la page relance seule.
                        val reason = loadErrorReason(error)
                        webView.evaluateJavascript("window.onCategoryError && window.onCategoryError('$key', '$reason');", null)
                    }
                })
        )
    }

    /** Cause courte et sûre (lettres, chiffres, _) d'un échec de chargement. */
    private fun loadErrorReason(error: Throwable): String {
        var e: Throwable = error
        while (e.cause != null && e.cause !== e && e !is retrofit2.HttpException) e = e.cause!!
        return when (e) {
            is retrofit2.HttpException -> "http_" + e.code()
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.UnknownHostException -> "dns"
            is java.net.ConnectException -> "connect"
            is javax.net.ssl.SSLException -> "ssl"
            is com.google.gson.JsonParseException, is IllegalStateException -> "format"
            else -> e.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(40)
        }
    }

    private var searchSubscription: Disposable? = null

    /** Recherche sur plusieurs pages : la page filtre ensuite par catégories choisies. */
    private fun searchApps(query: String, acc: List<AppEntity> = emptyList(), page: Int = 0) {
        if (page == 0) searchSubscription?.dispose()
        searchSubscription = api.searchApps(
            query = query, tags = null, offset = acc.size.takeIf { it > 0 }, locale = locale
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ r ->
                val files = r.result.files
                val all = (acc + files).distinctBy { it.appId }
                js("onSearchResults", all.map(TvDetailsMapper::card))
                if (files.isNotEmpty() && all.size > acc.size && page + 1 < SEARCH_PAGES) {
                    searchApps(query, all, page + 1)
                }
            }, { error ->
                Log.e(TAG, "Search failed", error)
                if (acc.isEmpty()) js("onSearchError", mapOf("query" to query))
            })
    }

    // ---------------------------------------------------- Versions

    /** Architectures et date de chaque version (une requête par version, 3 en parallèle). */
    private fun loadVersionsInfo(ids: List<String>) {
        disposables.add(
            Observable.fromIterable(ids.take(MAX_VERSIONS_INFO))
                .flatMap({ id: String ->
                    api.getInfo(appId = id, packageName = null, locale = locale)
                        .map<Map<String, Any?>> { r ->
                            mapOf(
                                "id" to id,
                                "abi" to r.result.info.abi.orEmpty(),
                                "time" to r.result.info.time,
                            )
                        }
                        .onErrorReturnItem(mapOf("id" to id, "error" to true))
                        .toObservable()
                }, 3)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ info: Map<String, Any?> -> js("onVersionInfo", info) }, { e: Throwable -> Log.e(TAG, "versions info", e) })
        )
    }

    // ------------------------------------------------------ Compte

    private fun loadAccount() {
        disposables.add(
            api.getProfile(userId = null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ r ->
                    val p = r.result.profile
                    js("onAccount", mapOf(
                        "userId" to p.userId,
                        "name" to p.name,
                        "email" to p.email,
                        "registered" to p.isRegistered,
                        "verified" to p.isVerified,
                        "files" to p.filesCount,
                        "favorites" to p.favoritesCount,
                        "downloads" to p.totalDownloads,
                        "reviews" to p.reviewsCount,
                        "joinTime" to p.joinTime,
                    ))
                }, { error ->
                    Log.e(TAG, "account failed", error)
                    js("onAccountError", mapOf("message" to "Profil indisponible"))
                })
        )
    }

    private fun requestLoginCode(email: String) {
        disposables.add(
            api.requestCode(email = email)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ r ->
                    val res = r.result
                    js("onAuthCode", mapOf(
                        "requestId" to res.requestId,
                        "registered" to res.registered,
                        "codeRegex" to res.codeRegex,
                        "nameRegex" to res.nameRegex,
                    ))
                }, { error ->
                    Log.e(TAG, "request code failed", error)
                    js("onAuthError", mapOf("message" to authError(error)))
                })
        )
    }

    private fun verifyLoginCode(requestId: String, code: String, name: String) {
        disposables.add(
            api.verifyCode(requestId = requestId, code = code, name = name.ifBlank { null })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    js("onAuthDone", mapOf("ok" to true))
                    loadAccount()
                }, { error ->
                    Log.e(TAG, "verify code failed", error)
                    js("onAuthError", mapOf("message" to authError(error)))
                })
        )
    }

    // ATV : mêmes appels que le formulaire des Paramètres TV, demandés par le téléphone en mode 📺
    // (thread de la liaison, bloquants et bornés). La session reste celle de la TV.
    // L'état du compte ne doit jamais bloquer la connexion : en cas d'échec, `profile` est absent
    // et `statusError` donne la cause (http_401, network…) pour le diagnostic.
    private fun remoteAccountStatus(): JSONObject = try {
        val p = api.getProfile(userId = null).timeout(ACCOUNT_CALL_TIMEOUT_S, TimeUnit.SECONDS).blockingGet().result.profile
        JSONObject().put("ok", true).put("profile", JSONObject()
            .put("userId", p.userId)
            .put("name", p.name ?: "")
            .put("email", p.email ?: "")
            .put("registered", p.isRegistered)
            .put("verified", p.isVerified))
    } catch (e: Exception) {
        Log.e(TAG, "remote account status failed", e)
        val reason = accountFailure(e).optString("error")
        // Le serveur répond 401 au profil tant que la TV n'est connectée à aucun compte.
        if (reason == "http_401") JSONObject().put("ok", true).put("profile", JSONObject().put("registered", false).put("userId", ""))
        else JSONObject().put("ok", true).put("statusError", reason)
    }

    private fun remoteAccountRequestCode(email: String): JSONObject = try {
        val r = api.requestCode(email = email).timeout(ACCOUNT_CALL_TIMEOUT_S, TimeUnit.SECONDS).blockingGet().result
        JSONObject().put("ok", true)
            .put("requestId", r.requestId)
            .put("registered", r.registered)
    } catch (e: Exception) {
        Log.e(TAG, "remote request code failed", e)
        accountFailure(e)
    }

    private fun remoteAccountVerify(requestId: String, code: String, name: String, device: String): JSONObject = try {
        api.verifyCode(requestId = requestId, code = code, name = name.ifBlank { null })
            .timeout(ACCOUNT_CALL_TIMEOUT_S, TimeUnit.SECONDS).blockingGet()
        runOnUiThread {
            js("onAuthDone", mapOf("ok" to true))
            loadAccount()
            // Comme pour l'association : un changement de compte ne doit pas passer inaperçu sur la TV.
            Toast.makeText(this, getString(R.string.tv_account_linked_notice, device), Toast.LENGTH_LONG).show()
        }
        remoteAccountStatus()
    } catch (e: Exception) {
        Log.e(TAG, "remote verify code failed", e)
        accountFailure(e)
    }

    private fun accountFailure(error: Throwable): JSONObject {
        val cause = (error as? RuntimeException)?.cause ?: error
        val code = when {
            cause is retrofit2.HttpException && cause.code() == 429 -> "rate_limit"
            cause is retrofit2.HttpException -> "http_" + cause.code()
            else -> "network"
        }
        return JSONObject().put("ok", false).put("error", code)
    }

    private fun authError(error: Throwable): String =
        if (error is retrofit2.HttpException) {
            if (error.code() == 429) "Trop de tentatives, réessaie plus tard" else "Code ou adresse refusé"
        } else {
            "Erreur réseau"
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
                        "downloadError" to downloadManager.failure(appId), // ATV: why it failed
                        "downloadErrorDetail" to downloadManager.failureDetail(appId),
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
                    remoteInstalls.fail(appId, TvRemoteState.REASON_INFO) // ATV-03
                    toast("Impossible de démarrer le téléchargement")
                })
        )
    }

    private fun startInstall(details: Details) {
        val info = details.info
        val label = info.label ?: info.packageName
        pendingInstalls[info.appId] = PendingInstall(label, info.version)
        remoteInstalls.attach(info) // ATV-03: paquet et version visés

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
                // Vraie cause des échecs sur la MIBOX4 : `url` est l'adresse de la page web de
                // l'appli (238 Ko de HTML) ; le fichier est `link`, comme dans la fiche native.
                url = details.link,
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
                remoteInstalls.fail(appId, TvRemoteState.REASON_DOWNLOAD) // ATV-03
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
        @JavascriptInterface
        fun checkSelfUpdate(channel: String): Unit = runOnUiThread { updateController.check(manual = true, channel = channel.takeIf { it in setOf("stable", "beta", "test", "simultv") } ?: "stable") }

        /** ATV: ATKsimulTv (single update channel "simultv"). */
        @JavascriptInterface
        fun isSimulator(): Boolean = com.tomclaw.appsend.BuildConfig.FORCE_TV_SIMULATOR

        @JavascriptInterface
        fun installedUpdateVersion(): String = com.tomclaw.appsend.BuildConfig.VERSION_NAME + " (" + com.tomclaw.appsend.BuildConfig.VERSION_CODE + ")"


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
        fun requestAISummary(appId: String): Unit = runOnUiThread { this@TvWebActivity.requestAISummary(appId) }

        @JavascriptInterface
        fun loadCategories(): Unit = runOnUiThread { this@TvWebActivity.loadCategories() }

        @JavascriptInterface
        fun loadSource(key: String, type: String, param: String): Unit =
            runOnUiThread { this@TvWebActivity.loadSource(key, type, param) }

        /** Préférences de la page (catégories cochées, favoris, profil Store). */
        @JavascriptInterface
        fun getPref(key: String): String? = prefs.getString("js_$key", null)

        @JavascriptInterface
        fun setPref(key: String, value: String) {
            prefs.edit().putString("js_$key", value).apply()
        }

        @JavascriptInterface
        fun exportConfig(json: String): Unit = runOnUiThread {
            if (pendingConfig == null && json.length <= 32_768) {
                pendingConfig = json
                try {
                    configExportLauncher.launch("appteka-tv-config.json")
                } catch (_: ActivityNotFoundException) {
                    pendingConfig = null
                    toast("Sélecteur de fichiers indisponible sur cette TV")
                }
            }
        }

        /** Recharge la page (changement de langue). */
        @JavascriptInterface
        fun reloadUi(): Unit = runOnUiThread {
            disposables.clear()
            webView.reload()
        }

        /** true sur TV / box (interface 10 pieds), false sur portable. */
        @JavascriptInterface
        fun isTv(): Boolean = this@TvWebActivity.isTv

        /** Ouvre un écran de l'interface classique (fonctions pas encore en HTML). */
        @JavascriptInterface
        fun openClassic(screen: String, param: String): Unit =
            runOnUiThread { this@TvWebActivity.openClassic(screen, param) }

        /** Portable : envoie l'app à une Appteka TV du Wi-Fi. */
        @JavascriptInterface
        fun sendToTv(appId: String, label: String): Unit = runOnUiThread {
            MaterialAlertDialogBuilder(this@TvWebActivity)
                .setTitle(R.string.tv_pair_title)
                .setItems(arrayOf(getString(R.string.tv_pair_scan), getString(R.string.tv_pair_enter))) { _, which ->
                    if (which == 0) {
                        pendingPairApp = appId
                        pendingPairLabel = label
                        pairingScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setOrientationLocked(false).setPrompt(getString(R.string.tv_pair_scan)))
                    } else {
                        TvLinkSender(this@TvWebActivity, appId, label).showCodeEntry()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** TV : nom, adresse et appareils associés. */
        @JavascriptInterface
        fun tvLinkInfo(): String = gson.toJson(this@TvWebActivity.tvLinkInfo())

        @JavascriptInterface
        fun tvLinkForget(id: String): Unit = runOnUiThread { tvLink?.forget(id) }

        @JavascriptInterface
        fun tvLinkForgetAll(): Unit = runOnUiThread { tvLink?.forgetAll() }

        @JavascriptInterface
        fun tvLinkRevokeFiles(id: String): Unit = runOnUiThread { tvLink?.revokeFiles(id) }

        @JavascriptInterface
        fun tvLinkShowQr(): Unit = runOnUiThread { showPairingQr() }

        @JavascriptInterface
        fun getDeviceAbis(): String = gson.toJson(Build.SUPPORTED_ABIS.toList())

        @JavascriptInterface
        fun loadVersionsInfo(idsJson: String): Unit = runOnUiThread {
            val ids = try {
                gson.fromJson(idsJson, Array<String>::class.java).toList()
            } catch (_: Exception) {
                emptyList()
            }
            this@TvWebActivity.loadVersionsInfo(ids)
        }

        @JavascriptInterface
        fun loadAccount(): Unit = runOnUiThread { this@TvWebActivity.loadAccount() }

        @JavascriptInterface
        fun requestLoginCode(email: String): Unit = runOnUiThread { this@TvWebActivity.requestLoginCode(email) }

        @JavascriptInterface
        fun verifyLoginCode(requestId: String, code: String, name: String): Unit =
            runOnUiThread { this@TvWebActivity.verifyLoginCode(requestId, code, name) }

        /** Quitte l'app directement, sans remonter tout l'historique. */
        @JavascriptInterface
        fun exit(): Unit = runOnUiThread { finishAffinity() }

        @JavascriptInterface
        fun search(query: String): Unit = runOnUiThread { searchApps(query) }

        @JavascriptInterface
        fun showToast(message: String): Unit = runOnUiThread { toast(message) }

        @JavascriptInterface
        fun getVersion(): String = "1.1-tv"
    }

    // ------------------------------------------------- Appui long sur OK

    private var okDownAt = 0L
    private var okLongFired = false
    private val okLongPress = Runnable {
        okLongFired = true
        webView.evaluateJavascript("window.onTvLongPress && window.onTvLongPress();", null)
    }

    private fun isOkKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    /**
     * Android ne transmet pas l'appui long de la télécommande à la page web.
     * On garde donc la touche OK : appui court -> rejoué tel quel à la page,
     * appui long (0,6 s) -> window.onTvLongPress() (favori, déplacement de rangée).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::webView.isInitialized || !isOkKey(event.keyCode)) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    okDownAt = event.downTime
                    okLongFired = false
                    handler.removeCallbacks(okLongPress)
                    handler.postDelayed(okLongPress, LONG_PRESS_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(okLongPress)
                if (!okLongFired) {
                    // appui court : on rejoue OK (bas + haut) vers la page
                    val now = android.os.SystemClock.uptimeMillis()
                    webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, event.keyCode, 0))
                    webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, event.keyCode, 0))
                }
                okLongFired = false
                return true
            }
        }
        return true
    }

    private var lastBackAt = 0L

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // La page gère d'abord Retour (fermer la fiche, sortir de la saisie…)
        webView.evaluateJavascript(
            "(window.onTvBack && window.onTvBack()) ? 'yes' : 'no'"
        ) { result ->
            if (result?.contains("yes") != true) {
                // Gonzo 24/11 : sur l'accueil, il faut appuyer deux fois sur Retour pour quitter.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBackAt < EXIT_WINDOW_MS) {
                    finish()
                } else {
                    lastBackAt = now
                    toast(getString(R.string.tv_press_back_again))
                }
            }
        }
    }

    override fun onDestroy() {
        tvLink?.stop()
        remoteInstalls.dispose()
        handler.removeCallbacksAndMessages(null)
        stateSubscription?.dispose()
        aiPollSubscription?.dispose()
        searchSubscription?.dispose()
        pendingSubscriptions.clear()
        disposables.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TvWebActivity"
        private const val ACCOUNT_CALL_TIMEOUT_S = 30L
        private const val LONG_PRESS_MS = 600L
        private const val PREFS_NAME = "tv_web"
        private const val KEY_CATEGORIES_CACHE = "categories_cache"
        private const val SEARCH_PAGES = 4
        private const val MAX_VERSIONS_INFO = 40
        private const val MAX_PAGES = 5
        private const val GRAVITY_FALLBACK_MS = 1500L
        private const val AI_STATUS_PENDING = "pending"
        private const val AI_POLL_INTERVAL_MS = 4000L
        private const val AI_POLL_TIMEOUT_MS = 3 * 60 * 1000L
        private const val INSTALL_RETRY_COUNT = 5
        private const val EXIT_WINDOW_MS = 2000L
        private const val INSTALL_RETRY_DELAY_MS = 700L

        fun createIntent(context: Context): Intent {
            return Intent(context, TvWebActivity::class.java)
        }
    }
}
