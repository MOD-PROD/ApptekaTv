package com.tomclaw.appsend.screen.store

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import com.tomclaw.appsend.screen.tv.DemoTv
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private var targetButton: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
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
        DemoTv.onPhone(requireContext())?.let(::bindTargetSwitch)
    }

    private fun bindTargetSwitch(demo: DemoTv) {
        val target = requireActivity().findViewById<ImageButton>(R.id.demo_tv_target)
        targetButton = target
        target.visibility = View.VISIBLE

        fun render() {
            target.setImageResource(if (demo.selected) R.drawable.ic_target_tv else R.drawable.ic_target_mobile)
            target.contentDescription = if (demo.selected) "TV de démo sélectionnée. Passer au téléphone"
                else "Téléphone sélectionné. Passer à la TV de démo"
        }

        target.setOnClickListener {
            demo.selected = !demo.selected
            render()
            presenter.invalidateApps()
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

    override fun onDestroyView() {
        targetButton?.setOnClickListener(null)
        targetButton?.visibility = View.GONE
        targetButton = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        presenter.detachView()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_PRESENTER_STATE, ZipParcelable(presenter.saveState()))
    }

    override fun openContentFilterScreen() {
        startActivity(createContentFilterIntent(requireContext()))
    }

    override fun openAppScreen(appId: String, title: String) {
        val demo = DemoTv.onPhone(requireContext())
        if (demo?.selected == true) {
            val app = DemoTv.apps.firstOrNull { it.id == appId } ?: return
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(app.name)
            if (!demo.connected) {
                dialog.setMessage("Connecte la TV de démo depuis Profil.")
            } else {
                val installed = demo.isInstalled(app)
                dialog.setMessage(if (installed) "Installée sur la TV de démo" else "Disponible pour la TV de démo")
                dialog.setPositiveButton(if (installed) "Désinstaller" else "Installer") { _, _ ->
                    demo.setInstalled(app, !installed)
                    presenter.invalidateApps()
                }
            }
            dialog.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
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
