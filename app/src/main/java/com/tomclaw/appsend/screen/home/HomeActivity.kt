package com.tomclaw.appsend.screen.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.R
import com.tomclaw.appsend.screen.about.createAboutActivityIntent
import com.tomclaw.appsend.screen.bdui.createBduiScreenActivityIntent
import com.tomclaw.appsend.screen.create_chat.createCreateChatActivityIntent
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import com.tomclaw.appsend.screen.distro.createDistroActivityIntent
import com.tomclaw.appsend.screen.feed.createFeedFragment
import com.tomclaw.appsend.screen.home.di.HomeModule
import com.tomclaw.appsend.screen.installed.createInstalledActivityIntent
import com.tomclaw.appsend.screen.post.createPostActivityIntent
import com.tomclaw.appsend.screen.profile.createProfileFragment
import com.tomclaw.appsend.screen.search.createSearchActivityIntent
import com.tomclaw.appsend.screen.settings.createSettingsActivityIntent
import com.tomclaw.appsend.screen.store.createStoreFragment
import com.tomclaw.appsend.screen.topics.createTopicsFragment
import com.tomclaw.appsend.screen.upload.createUploadActivityIntent
import com.tomclaw.appsend.screen.tv.TvWebActivity
import com.tomclaw.appsend.util.isTvDevice
import com.tomclaw.appsend.util.Analytics
import javax.inject.Inject
import kotlin.system.exitProcess

class HomeActivity : AppCompatActivity(), HomePresenter.HomeRouter {

    @Inject
    lateinit var presenter: HomePresenter

    private var redirectedToTv = false
    private val updateController by lazy { com.tomclaw.appsend.update.UpdateController(this) }

    @Inject
    lateinit var analytics: Analytics

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var pendingFragmentRunnable: Runnable? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            presenter.onBackPressed()
        }
    }

    private val postLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                invalidateFragment(result.data)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // HTML TV shell only on real Android TV; smartphone keeps native portrait home.
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_CLASSIC, false) &&
            (intent.action == null || intent.action == Intent.ACTION_MAIN) &&
            isTvDevice()
        ) {
            redirectedToTv = true
            super.onCreate(savedInstanceState)
            startActivity(TvWebActivity.createIntent(this))
            finish()
            return
        }
        // Fin du splash : retour au thème normal de l'appli
        setTheme(R.style.AppTheme)
        val presenterState = savedInstanceState?.getBundle(KEY_PRESENTER_STATE)
        appComponent
            .homeComponent(HomeModule(context = this, startAction = intent.action, presenterState))
            .inject(activity = this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.home_activity)

        onBackPressedDispatcher.addCallback(backCallback)

        val view = HomeViewImpl(window.decorView)

        presenter.attachView(view)

        if (savedInstanceState == null) {
            analytics.trackEvent("open-home-screen")
            updateController.check(channel = com.tomclaw.appsend.update.UpdateChannel.get(this)) // ATV: canal mémorisé
        }
    }

    override fun showStoreFragment() {
        val fragment = getOrCreateFragment(INDEX_STORE) { createStoreFragment() }
        replaceFragment(fragment, INDEX_STORE)
    }

    override fun showFeedFragment() {
        val fragment = getOrCreateFragment(INDEX_FEED) { createFeedFragment() }
        replaceFragment(fragment, INDEX_FEED)
    }

    override fun showTopicsFragment() {
        val fragment = getOrCreateFragment(INDEX_DISCUSS) { createTopicsFragment() }
        replaceFragment(fragment, INDEX_DISCUSS)
    }

    override fun showProfileFragment() {
        val fragment = getOrCreateFragment(INDEX_PROFILE) { createProfileFragment() }
        replaceFragment(fragment, INDEX_PROFILE)
    }

    fun invalidateFragment(data: Intent?) {
        val pendingRunnable = Runnable {
            val fragment = supportFragmentManager.findFragmentById(R.id.frame) as? HomeFragment
            fragment?.handleEvent(data)
        }
        handler.post(pendingRunnable)
    }

    override fun openUploadScreen() {
        startActivity(createUploadActivityIntent(this, null, null, null))
    }

    override fun openPostScreen() {
        postLauncher.launch(createPostActivityIntent(this))
    }

    override fun openCreateChatScreen() {
        startActivity(createCreateChatActivityIntent(this))
    }

    override fun openSearchScreen() {
        startActivity(createSearchActivityIntent(this))
    }

    override fun openInstalledScreen() {
        // ATV: in 📺 mode the phone shows the TV's apps and memory, as on the TV.
        if (com.tomclaw.appsend.target.TvTarget.current(this) != null) {
            startActivity(com.tomclaw.appsend.target.createTvContentActivityIntent(this))
            return
        }
        startActivity(createInstalledActivityIntent(this))
    }

    override fun openDistroScreen() {
        startActivity(createDistroActivityIntent(this))
    }

    override fun openSettingsScreen() {
        startActivity(createSettingsActivityIntent(this))
    }

    override fun openAboutScreen() {
        startActivity(createAboutActivityIntent(this))
    }

    private fun getOrCreateFragment(index: Int, creator: () -> Fragment): Fragment {
        return supportFragmentManager.findFragmentByTag("fragment$index") ?: let {
            creator.invoke()
        }
    }

    private fun replaceFragment(fragment: Fragment, index: Int) {
        val tag = "fragment$index"
        // Returning from another screen re-binds the current tab, and so
        // does every status load. Replacing the fragment with itself
        // would throw away its list position and blink the app bar
        // through a lift reset each time.
        if (supportFragmentManager.findFragmentById(R.id.frame) === fragment) {
            return
        }
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.frame, fragment, tag)
            .commitAllowingStateLoss()
        resetAppBarLift()
    }

    private fun resetAppBarLift() {
        findViewById<AppBarLayout>(R.id.app_bar_layout)?.setExpanded(true, true)
    }

    override fun onStart() {
        super.onStart()
        if (redirectedToTv) return
        presenter.attachRouter(this)
    }

    override fun onStop() {
        pendingFragmentRunnable?.let { handler.removeCallbacks(it) }
        pendingFragmentRunnable = null
        if (!redirectedToTv) presenter.detachRouter()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        if (!redirectedToTv) presenter.detachView()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (redirectedToTv) return
        outState.putBundle(KEY_PRESENTER_STATE, presenter.saveState())
    }

    override fun openAppScreen(appId: String, title: String) {
        val intent = createDetailsActivityIntent(
            context = this,
            appId = appId,
            label = title,
            moderation = false,
            finishOnly = true
        )
        startActivity(intent)
    }

    override fun openShareUrlDialog(text: String) {
        val intent = Intent().apply {
            setAction(Intent.ACTION_SEND)
            putExtra(Intent.EXTRA_TEXT, text)
            setType("text/plain")
        }
        val chooser = Intent.createChooser(intent, resources.getText(R.string.send_url_to))
        startActivity(chooser)
    }

    override fun openBduiScreen(url: String, title: String?) {
        val intent = createBduiScreenActivityIntent(this, url, title)
        startActivity(intent)
    }

    override fun setBackCallbackEnabled(enabled: Boolean) {
        backCallback.isEnabled = enabled
    }

    override fun leaveScreen() {
        finish()
    }

    override fun exitApp() {
        exitProcess(0)
    }

    override fun onTabReselected() {
        val fragment = supportFragmentManager.findFragmentById(R.id.frame) as? HomeFragment
        fragment?.onReselect()
    }

}

fun createHomeActivityIntent(
    context: Context,
): Intent = Intent(context, HomeActivity::class.java)

/** Accueil de l'interface classique (connecteur depuis l'interface HTML). */
fun createClassicHomeIntent(
    context: Context,
): Intent = Intent(context, HomeActivity::class.java).putExtra(EXTRA_CLASSIC, true)

private const val EXTRA_CLASSIC = "classic_ui"

private const val KEY_PRESENTER_STATE = "presenter_state"
private const val INDEX_STORE = 0
private const val INDEX_FEED = 1
private const val INDEX_DISCUSS = 2
private const val INDEX_PROFILE = 3
