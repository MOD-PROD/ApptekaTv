package com.tomclaw.appsend.screen.store

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import com.tomclaw.appsend.BuildConfig
import com.tomclaw.appsend.util.isTvDevice
import com.tomclaw.appsend.tvlink.TvLinkSender
import com.tomclaw.appsend.target.TvTarget
import com.tomclaw.appsend.target.TvReconnect
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
        if (!requireContext().isTvDevice()) bindTargetSwitch()
    }

    // ATV-03: the selected TV is shared with the native details screen (TvTarget).
    private val connection: TvLinkSender.Connection?
        get() = context?.let { TvTarget.current(it) }
    private fun pairingSender() = TvLinkSender(requireActivity(), onAssociated = { paired ->
        val context = context ?: return@TvLinkSender
        TvTarget.select(context, paired)
        renderTarget()
        android.widget.Toast.makeText(context, "Connecté à ${paired.name}", android.widget.Toast.LENGTH_SHORT).show()
    })

    private val pairingScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { pairingSender().pair(it) }
    }

    private fun renderTarget() {
        targetButton?.apply {
            setImageResource(if (connection == null) R.drawable.ic_target_mobile else R.drawable.ic_target_tv)
            contentDescription = connection?.let { "TV connectée : ${it.name}. Revenir au téléphone." }
                ?: getString(R.string.tv_pair_title)
        }
    }

    private fun bindTargetSwitch() {
        val target = requireActivity().findViewById<ImageButton>(R.id.demo_tv_target)
        targetButton = target
        target.visibility = View.VISIBLE
        target.setOnClickListener {
            if (connection != null) {
                TvTarget.select(requireContext(), null)
                renderTarget()
            } else {
                // ATV: back to the last TV without pairing again, when it still knows this phone.
                val last = TvTarget.last(requireContext())
                if (last != null) reconnect(last) else showPairDialog()
            }
        }
        renderTarget()
    }

    private fun reconnect(last: TvLinkSender.Connection) {
        val appContext = requireContext().applicationContext
        android.widget.Toast.makeText(appContext, getString(R.string.tv_reconnecting, last.name), android.widget.Toast.LENGTH_SHORT).show()
        kotlin.concurrent.thread(name = "tv-reconnect") {
            val result = TvReconnect.reconnect(appContext, last)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when (result) {
                    is TvReconnect.Result.Connected -> {
                        TvTarget.select(requireContext(), result.connection)
                        renderTarget()
                        android.widget.Toast.makeText(appContext, "Connecté à ${result.connection.name}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    TvReconnect.Result.Forgotten -> {
                        TvTarget.forget(requireContext())
                        android.widget.Toast.makeText(appContext, getString(R.string.tv_reconnect_forgotten, last.name), android.widget.Toast.LENGTH_LONG).show()
                        showPairDialog()
                    }
                    TvReconnect.Result.Unreachable -> showPairDialog(retry = last)
                }
            }
        }
    }

    private fun showPairDialog(retry: TvLinkSender.Connection? = null) {
        val items = listOfNotNull(
            retry?.let { getString(R.string.tv_reconnect_retry, it.name) },
            getString(R.string.tv_pair_scan),
            getString(R.string.tv_pair_enter),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tv_pair_title)
            .apply { if (retry != null) setMessage(getString(R.string.tv_reconnect_unreachable, retry.name)) }
            .setItems(items.toTypedArray()) { _, position ->
                val which = if (retry != null) position - 1 else position
                if (which == -1 && retry != null) reconnect(retry)
                else if (which == 0) {
                    pairingScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setOrientationLocked(true).setPrompt(getString(R.string.tv_pair_scan)))
                } else if (which == 1) pairingSender().showCodeEntry()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        presenter.attachRouter(this)
        renderTarget() // ATV-03: the TV may have revoked this phone meanwhile
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
        // ATV-03: in TV mode the phone behaves as on the TV: the native details
        // screen opens and its Install button installs on the TV.
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
