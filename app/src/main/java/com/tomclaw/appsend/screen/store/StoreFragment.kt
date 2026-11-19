package com.tomclaw.appsend.screen.store

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tomclaw.appsend.BuildConfig
import com.tomclaw.appsend.screen.tv.DemoTv
import com.tomclaw.appsend.util.isTvDevice
import com.tomclaw.appsend.util.adapter.ItemBinder
import com.tomclaw.appsend.util.adapter.AdapterPresenter
import com.tomclaw.appsend.util.adapter.SimpleRecyclerAdapter
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.R
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import com.tomclaw.appsend.screen.home.HomeFragment
import com.tomclaw.appsend.screen.settings.createContentFilterIntent
import com.tomclaw.appsend.screen.store.di.StoreModule
import com.tomclaw.appsend.util.Analytics
import com.tomclaw.appsend.util.ZipParcelable
import com.tomclaw.appsend.util.getParcelableCompat
import javax.inject.Inject

class StoreFragment : Fragment(), StorePresenter.StoreRouter, HomeFragment {

    @Inject
    lateinit var presenter: StorePresenter

    @Inject
    lateinit var adapterPresenter: AdapterPresenter

    @Inject
    lateinit var binder: ItemBinder

    @Inject
    lateinit var preferences: StorePreferencesProvider

    @Inject
    lateinit var analytics: Analytics

    private var savedTvTarget = false

    override fun onCreate(savedInstanceState: Bundle?) {
        savedTvTarget = savedInstanceState?.getBoolean("debug_demo_tv_target") == true
        val presenterState = savedInstanceState
            ?.getParcelableCompat(KEY_PRESENTER_STATE, ZipParcelable::class.java)
            ?.restore<Bundle>()
        requireContext().appComponent
            .storeComponent(StoreModule(requireContext(), presenterState))
            .inject(fragment = this)

        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            analytics.trackEvent("open-store-fragment")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.store_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = SimpleRecyclerAdapter(adapterPresenter, binder)
        // The filter row is chrome, and chrome belongs to the host's app
        // bar — the same one that carries this tab's title and menu.
        val filters = requireActivity().findViewById<View>(R.id.store_filters)
        val topicsView = StoreViewImpl(view, filters, preferences, adapter)

        presenter.attachView(topicsView)
        if (BuildConfig.DEBUG && !requireContext().isTvDevice()) addDemoTvSwitch(view as FrameLayout)
    }

    private fun addDemoTvSwitch(root: FrameLayout) {
        val context = requireContext()
        val demo = DemoTv(context)
        val catalog = root.findViewById<View>(R.id.swipe_refresh)
        root.removeView(catalog)

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val target = Button(context)
        column.addView(target)
        val body = FrameLayout(context)
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(catalog, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(context)
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(panel)

        var showTv = savedTvTarget

        fun render() {
            target.text = if (showTv) "Cible : TV de démo  ·  Passer au téléphone" else "Cible : Téléphone  ·  Passer à la TV"
            catalog.visibility = if (showTv) View.GONE else View.VISIBLE
            scroll.visibility = if (showTv) View.VISIBLE else View.GONE
            panel.removeAllViews()
            if (!showTv) return
            panel.addView(TextView(context).apply { text = "TV de démo · simulation locale" })
            panel.addView(Button(context).apply {
                text = if (demo.connected) "Déconnecter" else "Connecter la TV de démo"
                setOnClickListener {
                    demo.connected = !demo.connected
                    render()
                }
            })
            if (!demo.connected) return
            DemoTv.apps.forEach { app ->
                val installed = demo.isInstalled(app)
                panel.addView(Button(context).apply {
                    text = if (installed) "${app.name} · Désinstaller" else "${app.name} · Installer"
                    setOnClickListener {
                        demo.setInstalled(app, !installed)
                        render()
                    }
                })
            }
        }

        target.setOnClickListener {
            showTv = !showTv
            savedTvTarget = showTv
            render()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        presenter.attachRouter(this)
    }

    override fun onStop() {
        presenter.detachRouter()
        super.onStop()
    }

    override fun onDestroy() {
        presenter.detachView()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (BuildConfig.DEBUG) outState.putBoolean("debug_demo_tv_target", savedTvTarget)
        outState.putParcelable(KEY_PRESENTER_STATE, ZipParcelable(presenter.saveState()))
    }

    override fun openContentFilterScreen() {
        startActivity(createContentFilterIntent(requireContext()))
    }

    override fun openAppScreen(appId: String, title: String) {
        val intent = createDetailsActivityIntent(
            context = requireContext(),
            appId = appId,
            label = title,
            moderation = false,
            finishOnly = true
        )
        startActivity(intent)
    }

    override fun handleEvent(data: Intent?) {
        presenter.invalidateApps()
    }

    override fun onReselect() {
        presenter.scrollToTop()
        presenter.invalidateApps()
    }

}

fun createStoreFragment(): StoreFragment = StoreFragment()

private const val KEY_PRESENTER_STATE = "presenter_state"
