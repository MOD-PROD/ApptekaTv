package com.tomclaw.appsend.screen.linktarget

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tomclaw.appsend.R
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.install.InstallRequest
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import com.tomclaw.appsend.screen.tv.TvContent
import com.tomclaw.appsend.screen.tv.TvRemoteApps
import com.tomclaw.appsend.screen.tv.TvRemoteDownloads
import com.tomclaw.appsend.screen.tv.TvRemoteFiles
import com.tomclaw.appsend.screen.tv.TvRemoteInstalls
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.util.isTvDevice
import io.reactivex.rxjava3.schedulers.Schedulers
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * ATV (Gonzo 25/11) : un téléphone ou une tablette (interface native, sans HTML) peut être
 * piloté par un autre appareil, comme la TV : même serveur [TvLink.Server] (TLS, invitation
 * à usage unique, accord explicite sur l'appareil piloté), mêmes ordres (installer, Fichiers,
 * Installées, Downloaded, Send to, compte).
 *
 * Désactivé par défaut (Settings → « Piloter cet appareil à distance »). Le serveur vit avec
 * le processus de l'appli ; les fenêtres d'accord ont besoin qu'Appteka soit ouvert : sinon
 * la demande est refusée. Jamais démarré sur une TV/box : là, c'est TvWebActivity.
 */
object LocalLinkTarget : Application.ActivityLifecycleCallbacks {

    private const val TAG = "LocalLinkTarget"
    private const val PREFS = "local_link_target_v1"
    private const val KEY_ENABLED = "enabled"
    private const val ACCOUNT_CALL_TIMEOUT_S = 30L

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private var resumed: WeakReference<Activity>? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var server: TvLink.Server? = null
        private set

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(this)
        if (isAvailable(application) && isEnabled(application)) {
            start()
            // ATV arrière-plan : reste joignable appli fermée (refusé par Android si lancé hors premier plan).
            com.tomclaw.appsend.tvlink.LinkTargetService.start(application)
        }
    }

    /** Pas sur une TV/box : leur serveur est celui de l'interface TV. */
    fun isAvailable(context: Context): Boolean = !context.isTvDevice()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled && isAvailable(context)) {
            start()
            com.tomclaw.appsend.tvlink.LinkTargetService.start(context)
        } else {
            stop()
            com.tomclaw.appsend.tvlink.LinkTargetService.stop(context)
        }
        notifyChanged()
    }

    /** ATV arrière-plan : redémarre le serveur local s'il est activé. */
    fun ensureRunning(context: Context) {
        if (isEnabled(context) && isAvailable(context)) start()
    }

    /** Écran « Piloter cet appareil » : redessiner après une association. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    private fun start() {
        if (server != null) return
        val s = TvLink.Server(app, NativeHost(app))
        s.confirmFiles = { device, answer -> confirm(R.string.tv_files_confirm_title,
            app.getString(R.string.local_target_files_confirm_message, device), answer) }
        // ATV proximité : un appareil du Wi-Fi demande à s'associer → écran du code.
        s.onPairInvite = { device -> PairInvites.show(app, device, LocalTargetActivity::class.java) }
        try {
            s.start()
            server = s
            if (currentActivity() != null) s.confirmPairRequest = ::confirmPairRequest
        } catch (e: Exception) {
            Log.w(TAG, "local link unavailable", e)
            runCatching { s.stop() }
        }
    }

    private fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    fun currentActivity(): Activity? = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    /** Accord explicite sur l'appareil piloté ; refus si Appteka n'est pas au premier plan. */
    private fun confirm(title: Int, message: String, answer: (Boolean) -> Unit) {
        val activity = currentActivity() ?: return answer(false)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.tv_pair_confirm_accept) { _, _ -> answer(true) }
            .setNegativeButton(R.string.tv_pair_confirm_refuse) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) } // une 2e réponse est ignorée par le serveur
            .show()
        main.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 30_000L)
    }

    // ------------------------------------------------ ActivityLifecycleCallbacks

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        // Association sans code : la question ne peut s'afficher que si Appteka est à l'écran.
        server?.confirmPairRequest = ::confirmPairRequest
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) {
            resumed = null
            server?.confirmPairRequest = null // le téléphone revient alors au code
        }
    }

    private fun confirmPairRequest(device: String, answer: (Boolean) -> Unit) =
        confirm(R.string.local_target_confirm_title, app.getString(R.string.local_target_confirm_message, device), answer)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    // ------------------------------------------------------------ Host natif

    /** Aussi utilisé sur la TV quand l'écran Appteka TV est fermé (voir TvLinkKeeper). */
    internal open class NativeHost(protected val context: Application) : TvLink.Host {
        private val component = context.appComponent
        private val appInstaller = component.appInstaller()
        private val remoteInstalls by lazy { TvRemoteInstalls(context, component.downloadManager(), appInstaller) }
        private val remoteApps by lazy { TvRemoteApps(context) }
        private val remoteDownloads by lazy { TvRemoteDownloads(context, component.apkStorage()) }
        private val remoteFiles by lazy { TvRemoteFiles(context) }
        private val api get() = component.storeApi()

        override fun onPaired(device: String) {
            LocalLinkTarget.notifyChanged()
            // Comme sur la TV : une association ne doit jamais passer inaperçue.
            LocalLinkTarget.toast(context.getString(R.string.local_target_paired_notice, device))
        }

        /** Ordre « ouvrir / installer » : fiche native, téléchargement lancé par la fiche. */
        override fun onOpen(appId: String, install: Boolean, device: String) {
            if (install) {
                remoteInstalls.track(appId) // ordre `status` : progression vue par le téléphone
                attachInfo(appId)
            }
            val intent = createDetailsActivityIntent(context = context, appId = appId, label = "", finishOnly = true)
                .putExtra(com.tomclaw.appsend.screen.details.EXTRA_REMOTE_INSTALL, install)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val starter: Context = LocalLinkTarget.currentActivity() ?: context
            runCatching { starter.startActivity(intent) }
            LocalLinkTarget.toast(context.getString(
                if (install) R.string.local_target_install_from else R.string.local_target_open_from, device))
        }

        private fun attachInfo(appId: String) {
            val locale = Locale.getDefault().language.ifBlank { "en" }
            api.getInfo(appId = appId, packageName = null, locale = locale)
                .subscribeOn(Schedulers.io())
                .subscribe({ remoteInstalls.attach(it.result.info) }, {
                    remoteInstalls.fail(appId, com.tomclaw.appsend.target.TvRemoteState.REASON_INFO)
                })
        }

        override fun status(appId: String): JSONObject = remoteInstalls.status(appId).toJson()

        override fun content(): JSONObject = TvContent.build(context)

        override fun content(system: Boolean): JSONObject = TvContent.build(context, system)

        override fun appAction(type: String, packageName: String): JSONObject = remoteApps.action(type, packageName)

        override fun icon(packageName: String): ByteArray? = remoteApps.icon(packageName)

        override fun apkFile(packageName: String): java.io.File? = remoteApps.apkFile(packageName)

        override fun incomingApk(size: Long): java.io.File? = remoteApps.incomingApk(size)

        override fun downloads(): JSONObject = remoteDownloads.list()

        override fun downloadAction(type: String, fileName: String): JSONObject =
            remoteDownloads.action(type, fileName) { uri, label ->
                appInstaller.install(InstallRequest(key = "download:$fileName", label = label, uri = uri))
            }

        override fun downloadIcon(fileName: String): ByteArray? = remoteDownloads.icon(fileName)

        override fun downloadFile(fileName: String): java.io.File? = remoteDownloads.file(fileName)

        override fun accountStatus(): JSONObject = try {
            val p = api.getProfile(userId = null).timeout(ACCOUNT_CALL_TIMEOUT_S, TimeUnit.SECONDS).blockingGet().result.profile
            JSONObject().put("ok", true).put("profile", JSONObject()
                .put("userId", p.userId)
                .put("name", p.name ?: "")
                .put("email", p.email ?: "")
                .put("registered", p.isRegistered)
                .put("verified", p.isVerified))
        } catch (e: Exception) {
            accountFailure(e)
        }

        override fun accountRequestCode(email: String): JSONObject = try {
            val r = api.requestCode(email = email).timeout(ACCOUNT_CALL_TIMEOUT_S, TimeUnit.SECONDS).blockingGet().result
            JSONObject().put("ok", true).put("requestId", r.requestId).put("registered", r.registered)
        } catch (e: Exception) {
            accountFailure(e)
        }

        override fun accountVerify(requestId: String, code: String, name: String, device: String): JSONObject = try {
            api.verifyCode(requestId = requestId, code = code, name = name.ifBlank { null })
                .timeout(ACCOUNT_CALL_TIMEOUT_S, TimeUnit.SECONDS).blockingGet()
            LocalLinkTarget.toast(context.getString(R.string.local_target_account_notice, device))
            accountStatus()
        } catch (e: Exception) {
            accountFailure(e)
        }

        private fun accountFailure(error: Throwable): JSONObject {
            val cause = (error as? RuntimeException)?.cause ?: error
            val code = when {
                cause is retrofit2.HttpException && cause.code() == 429 -> "rate_limit"
                cause is retrofit2.HttpException -> "refused"
                else -> "network"
            }
            return JSONObject().put("ok", false).put("error", code)
        }

        override fun fsAccessMode(): String = remoteFiles.accessMode()

        override fun fsRoots(): List<TvLink.FsRoot> = remoteFiles.roots()

        override fun fsRequestStorage() {
            LocalLinkTarget.main.post {
                val activity = LocalLinkTarget.currentActivity() ?: return@post
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.tv_files_storage_title)
                    .setMessage(R.string.local_target_storage_message)
                    .setPositiveButton(R.string.tv_files_storage_allow) { _, _ ->
                        if (!remoteFiles.needsSettingsScreen) {
                            androidx.core.app.ActivityCompat.requestPermissions(activity,
                                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                        } else if (!remoteFiles.openAllFilesSettings()) {
                            LocalLinkTarget.toast(context.getString(R.string.tv_files_storage_adb, context.packageName))
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        override fun onFilesOpened(device: String) {
            LocalLinkTarget.toast(context.getString(R.string.local_target_files_opened, device))
        }

        override fun fsInstall(file: java.io.File, device: String): JSONObject {
            val copy = remoteApps.incomingApk(file.length()) ?: return JSONObject().put("ok", false).put("error", "storage")
            return try {
                file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
                remoteApps.received(copy) { uri, label ->
                    LocalLinkTarget.toast(context.getString(R.string.tv_apk_received, label, device))
                    appInstaller.install(InstallRequest(key = "fs:" + copy.name, label = label, uri = uri))
                }
            } catch (e: Exception) {
                copy.delete()
                JSONObject().put("ok", false).put("error", "unreadable")
            }
        }

        override fun onApkReceived(file: java.io.File, device: String): JSONObject =
            remoteApps.received(file) { uri, label ->
                LocalLinkTarget.toast(context.getString(R.string.tv_apk_received, label, device))
                appInstaller.install(InstallRequest(key = "push:" + file.name, label = label, uri = uri))
            }
    }
}
