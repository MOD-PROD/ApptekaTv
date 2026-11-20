package com.tomclaw.appsend.screen.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tomclaw.appsend.BaseActivity
import com.tomclaw.appsend.R
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.screen.auth.request.RequestCodeActivity
import com.tomclaw.appsend.screen.auth.verify.VerifyCodeActivity
import com.tomclaw.appsend.screen.chat.ChatActivity
import com.tomclaw.appsend.screen.distro.DistroActivity
import com.tomclaw.appsend.screen.downloads.createDownloadsActivityIntent
import com.tomclaw.appsend.screen.favorite.createFavoriteActivityIntent
import com.tomclaw.appsend.screen.feed.createFeedActivityIntent
import com.tomclaw.appsend.screen.installed.createInstalledActivityIntent
import com.tomclaw.appsend.screen.moderation.createModerationActivityIntent
import com.tomclaw.appsend.screen.permissions.PermissionsActivity
import com.tomclaw.appsend.screen.profile.createProfileActivityIntent
import com.tomclaw.appsend.screen.reviews.createReviewsActivityIntent
import com.tomclaw.appsend.screen.settings.createSettingsActivityIntent
import com.tomclaw.appsend.screen.store.createStoreFragment
import com.tomclaw.appsend.screen.topics.createTopicsFragment
import com.tomclaw.appsend.screen.tv.TvWebActivity
import com.tomclaw.appsend.util.isTvDevice
import com.tomclaw.appsend.screen.upload.createUploadActivityIntent
import com.tomclaw.appsend.screen.uploads.createUploadsActivityIntent
import com.tomclaw.appsend.util.Analytics
import javax.inject.Inject

class HomeActivity : BaseActivity(), HomePresenter.HomeRouter {

    @Inject
    lateinit var presenter: HomePresenter

    @Inject
    lateinit var analytics: Analytics

    private var redirectedToTv = false

    private val updateController by lazy { com.tomclaw.appsend.update.UpdateController(this) }

    private val backCallback = object : OnBackPressedCallback(true) {
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
        // HTML TV shell is only the home screen on real Android TV devices.
        // Smartphones keep the native portrait home (Store, pairing selector).
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
        }

        Handler(Looper.getMainLooper()).post {
            if (!isFinishing && !isDestroyed) {
                updateController.check()
            }
        }
    }

    private fun invalidateFragment(data: Intent?) {
        supportFragmentManager.fragments.filterIsInstance<HomeFragment>().forEach {
            it.handleEvent(data)
        }
    }

    override fun onStart() {
        super.onStart()
        if (redirectedToTv) return
        presenter.attachRouter(this)
    }

    override fun onStop() {
        if (!redirectedToTv) presenter.detachRouter()
        super.onStop()
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

    override fun showStoreFragment() {
        replaceFragment(createStoreFragment())
    }

    override fun showTopicsFragment() {
        replaceFragment(createTopicsFragment())
    }

    private fun replaceFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.frame, fragment)
            .commitAllowingStateLoss()
    }

    override fun openUploadScreen() {
        startActivity(createUploadActivityIntent(this, null, null, null))
    }

    override fun openInstalledScreen() {
        startActivity(createInstalledActivityIntent(this))
    }

    override fun openDistroScreen() {
        startActivity(Intent(this, DistroActivity::class.java))
    }

    override fun openModerationScreen() {
        startActivity(createModerationActivityIntent(this))
    }

    override fun openSettingsScreen() {
        startActivity(createSettingsActivityIntent(this))
    }

    override fun openProfileScreen(userId: Int) {
        startActivity(createProfileActivityIntent(this, userId))
    }

    override fun openDownloadsScreen(userId: Int) {
        startActivity(createDownloadsActivityIntent(this, userId))
    }

    override fun openUploadsScreen(userId: Int) {
        startActivity(createUploadsActivityIntent(this, userId))
    }

    override fun openFavoriteScreen(userId: Int) {
        startActivity(createFavoriteActivityIntent(this, userId))
    }

    override fun openReviewsScreen(userId: Int) {
        startActivity(createReviewsActivityIntent(this, userId))
    }

    override fun openFeedScreen(userId: Int) {
        startActivity(createFeedActivityIntent(this, userId))
    }

    override fun openChatScreen(topicId: Int) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra("topic_id", topicId))
    }

    override fun openAuthScreen() {
        startActivity(Intent(this, RequestCodeActivity::class.java))
    }

    override fun leaveScreen() {
        finish()
    }

    companion object {
        private const val KEY_PRESENTER_STATE = "presenter_state"
    }
}

fun createClassicHomeIntent(context: Context): Intent =
    Intent(context, HomeActivity::class.java).putExtra(EXTRA_CLASSIC, true)

private const val EXTRA_CLASSIC = "classic_ui"
