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
import com.tomclaw.appsend.target.DeviceKind
import com.tomclaw.appsend.target.DeviceKinds
import com.tomclaw.appsend.tvlink.TvLink
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
        // Gonzo 25/11 : l'icône choisie dans la liste avant la connexion.
        pendingKind?.let { DeviceKinds.choose(context, paired, it) }
        pendingKind = null
        renderTarget()
        android.widget.Toast.makeText(context, context.getString(R.string.tv_connected_to, paired.name), android.widget.Toast.LENGTH_SHORT).show()
    })

    private val pairingScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { pairingSender().pair(it) }
    }

    private fun renderTarget() {
        presenter.setTvMode(connection != null) // ATV: TV category by default in 📺 mode
        val context = context ?: return
        val shown = connection
        // ATV : une seule icône, celle du type de l'appareil piloté (cet appareil ou la TV).
        val kind = DeviceKinds.current(context, shown)
        targetButton?.apply {
            setImageResource(kind.icon)
            // Gonzo 25/11 : un appareil connecté actif s'affiche en vert.
            imageTintList = if (shown != null) android.content.res.ColorStateList.valueOf(CONNECTED_GREEN)
            else android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurface))
            contentDescription = (shown?.let { getString(R.string.tv_connected_back, DeviceKinds.displayName(context, it)) }
                ?: getString(R.string.tv_pair_title)) + ". " + getString(R.string.device_kind_hint)
        }
        // Gonzo 25/11 : en-tête « App Store - <appareil actif> » ou « TV App Store - <appareil actif> ».
        activity?.findViewById<android.widget.TextView>(R.id.store_header)?.text = getString(
            if (kind == DeviceKind.TV || kind == DeviceKind.BOX) R.string.store_header_tv else R.string.store_header,
            shown?.let { DeviceKinds.displayName(context, it) } ?: com.tomclaw.appsend.tvlink.TvLink.deviceName(),
        )
        if (shown != null) refreshRemoteKind(shown)
        reloadIfAbisChanged()
    }

    /** Gonzo 25/11 : autre appareil actif (ou réglage modifié) → la liste filtrée change. */
    private var abiState: String? = null

    private fun reloadIfAbisChanged() {
        val state = com.tomclaw.appsend.target.DeviceAbis.stateKey(context ?: return)
        val before = abiState
        abiState = state
        if (before != null && before != state) presenter.invalidateApps()
    }

    /** ATV : la TV annonce son type (TV ou box) dans « hello » ; mis en cache par TV. */
    private fun refreshRemoteKind(shown: TvLinkSender.Connection) {
        if (!kindAsked.add(shown.address.tv)) return
        val appContext = requireContext().applicationContext
        kotlin.concurrent.thread(name = "tv-kind") {
            val announced = runCatching {
                TvLink.request(shown.address, org.json.JSONObject().put("type", "hello"))
            }.getOrNull()
            val kindChanged = DeviceKinds.remember(appContext, shown, announced?.optString("kind"))
            val abisChanged = com.tomclaw.appsend.target.DeviceAbis.remember(appContext, shown.address.tv,
                announced?.optJSONArray("abis"))
            if (kindChanged || abisChanged) {
                activity?.runOnUiThread { if (isAdded) renderTarget() }
            }
        }
    }

    private val kindAsked = mutableSetOf<String>()

    /**
     * ATV (Gonzo 25/11) : un clic sur l'icône ouvre la liste des appareils.
     * - Cet appareil (téléphone par défaut) ;
     * - les appareils connectés, icône verte et leur nom : clic = devient la source,
     *   appui long = réglages (icône parmi les 4, Renommer, Supprimer) ;
     * - « À proximité » (appareils vus sur le Wi-Fi) ;
     * - tant qu'il y a moins de 4 appareils connectés, « Ajouter un appareil » : 2e liste des 4 types.
     */
    private fun showDeviceMenu(anchor: View, addMode: Boolean = false) {
        val context = requireContext()
        val shown = connection
        data class Row(val label: String, val icon: Int, val green: Boolean, val checked: Boolean, val plus: Boolean,
                       val divider: Boolean, val action: () -> Unit, val longAction: (() -> Unit)? = null,
                       val header: Boolean = false)
        val local = DeviceKinds.current(context, null)
        val known = TvTarget.known(context)
        val rows = mutableListOf<Row>()
        // Place de la section « À proximité » : après « Cet appareil » et les appareils associés.
        val nearbyAt = if (!addMode && known.size < MAX_DEVICES) 1 + known.size else -1
        // Gonzo 25/11 : « Ajouter » ouvre une 2e liste avec les 4 types à associer.
        if (addMode) {
            DeviceKind.values().forEach { kind ->
                rows += Row(getString(kind.label), kind.icon, false, false, true, false,
                    action = { pendingKind = kind; showPairDialog() })
            }
        } else {
        rows += Row(getString(R.string.local_target_this_device, getString(local.label)), local.icon, false, shown == null,
            false, false, action = { if (connection != null) { TvTarget.select(context, null); renderTarget() } },
            longAction = { showIconChooser(null) })
        known.forEach { device ->
            val current = shown?.address?.tv == device.address.tv
            rows += Row(DeviceKinds.displayName(context, device), DeviceKinds.current(context, device).icon, true, current,
                false, false, action = { if (!current) reconnect(device) }, longAction = { showDeviceSettings(device) })
        }
        // ATV proximité (Gonzo 25/11, « 1 et 2 ») : appareils Appteka vus sur le Wi-Fi, sans QR.
        if (nearbyAt >= 0) {
            rows += Row(getString(R.string.tv_nearby_searching), android.R.drawable.ic_menu_search, false, false,
                false, true, action = {}, header = true)
        }
        if (known.size < MAX_DEVICES) {
            rows += Row(getString(R.string.device_add), android.R.drawable.ic_input_add, false, false, true, true,
                action = { anchor.post { showDeviceMenu(anchor, addMode = true) } })
        }
        }
        val density = resources.displayMetrics.density
        val tint = com.google.android.material.color.MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface)
        val popup = androidx.appcompat.widget.ListPopupWindow(context)
        popup.anchorView = anchor
        popup.width = (280 * density).toInt()
        popup.isModal = true
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                val pad = (16 * density).toInt()
                val color = if (row.green) CONNECTED_GREEN else tint
                val line = android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
                    addView(android.widget.ImageView(parent.context).apply {
                        setImageResource(row.icon)
                        imageTintList = android.content.res.ColorStateList.valueOf(color)
                    }, android.widget.LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()))
                    addView(android.widget.TextView(parent.context).apply {
                        text = row.label
                        setTextColor(color)
                        textSize = 16f
                        setPadding(pad, 0, 0, 0)
                        if (row.checked) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val mark = when {
                        row.checked -> "✓"
                        row.plus -> "+"
                        else -> null
                    }
                    if (mark != null) addView(android.widget.TextView(parent.context).apply {
                        text = mark
                        setTextColor(color)
                        textSize = 18f
                    })
                }
                if (!row.divider) return line
                return android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(View(parent.context).apply {
                        setBackgroundColor(tint)
                        alpha = 0.2f
                    }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, density.toInt())))
                    addView(line)
                }
            }
        }
        popup.setAdapter(adapter)
        if (nearbyAt >= 0) {
            val app = context.applicationContext
            val self = TvLink.localIpv4()
            val taken = known.map { it.address.host to it.address.port }.toSet()
            kotlin.concurrent.thread(name = "nearby", isDaemon = true) {
                val found = runCatching { com.tomclaw.appsend.target.TvReconnect.discoverNearby(app) }
                    .getOrDefault(emptyList())
                    .filter { it.host != self && (it.host to it.port) !in taken }
                activity?.runOnUiThread {
                    if (!popup.isShowing || !isAdded) return@runOnUiThread
                    val nearby = found.map { device ->
                        Row(device.name, android.R.drawable.ic_menu_share, false, false, true, false,
                            action = { pairNearby(device.host, device.port) })
                    }
                    rows.removeAt(nearbyAt)
                    if (nearby.isNotEmpty()) {
                        rows.add(nearbyAt, Row(getString(R.string.tv_nearby_section), android.R.drawable.ic_menu_search,
                            false, false, false, true, action = {}, header = true))
                        rows.addAll(nearbyAt + 1, nearby)
                    }
                    adapter.notifyDataSetChanged()
                }
            }
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            if (rows[position].header) return@setOnItemClickListener
            popup.dismiss()
            rows[position].action()
        }
        popup.show()
        popup.listView?.setOnItemLongClickListener { _, _, position, _ ->
            val longAction = rows[position].longAction ?: return@setOnItemLongClickListener false
            popup.dismiss()
            longAction()
            true
        }
    }

    /**
     * Gonzo 26/11 « Recherche + OK sur la TV » : appui sur l'appareil, OK sur la TV, sans code.
     * Si l'autre appareil ne peut pas poser la question (écran fermé, ancienne version),
     * on revient à l'invitation + code.
     */
    private fun pairNearby(host: String, port: Int) {
        pendingKind = null
        pairingSender().pairNearby(host, port) { if (isAdded) inviteNearby(host, port) }
    }

    /**
     * ATV proximité : l'autre appareil ouvre son écran de code, on saisit ce code ici.
     * « busy » = une invitation vient déjà d'ouvrir l'écran : la saisie reste utile.
     */
    private fun inviteNearby(host: String, port: Int) {
        kotlin.concurrent.thread(name = "pair-invite", isDaemon = true) {
            val result = runCatching { TvLink.pairInvite(host, port) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (result.isSuccess || result.exceptionOrNull()?.message == "busy") {
                    pairingSender().showCodeEntry(host, port)
                } else {
                    android.widget.Toast.makeText(requireContext(), R.string.pair_invite_failed,
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Type choisi dans la liste avant la connexion : appliqué à l'appareil qui vient d'être associé. */
    private var pendingKind: DeviceKind? = null

    /** Appui long sur un appareil connecté : Icône, Renommer, Supprimer. */
    private fun showDeviceSettings(device: TvLinkSender.Connection) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(DeviceKinds.displayName(context, device))
            .setItems(arrayOf(getString(R.string.device_settings_icon), getString(R.string.device_settings_rename),
                getString(R.string.device_settings_delete))) { _, which ->
                when (which) {
                    0 -> showIconChooser(device)
                    1 -> showRename(device)
                    else -> confirmDelete(device)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Icône parmi les 4 ([device] null = cet appareil). */
    private fun showIconChooser(device: TvLinkSender.Connection?) {
        val context = requireContext()
        val kinds = DeviceKind.values()
        val selected = kinds.indexOf(DeviceKinds.current(context, device))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.device_settings_icon)
            .setSingleChoiceItems(kinds.map { getString(it.label) }.toTypedArray(), selected) { dialog, which ->
                DeviceKinds.choose(context, device, kinds[which])
                dialog.dismiss()
                renderTarget()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRename(device: TvLinkSender.Connection) {
        val context = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(context).apply {
            setText(DeviceKinds.displayName(context, device))
            setSelection(text.length)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        val box = android.widget.FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.device_settings_rename)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DeviceKinds.rename(context, device, input.text.toString())
                renderTarget()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(device: TvLinkSender.Connection) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setMessage(getString(R.string.device_delete_confirm, DeviceKinds.displayName(context, device)))
            .setPositiveButton(R.string.device_settings_delete) { _, _ ->
                TvTarget.forget(context, device)
                DeviceKinds.forget(context, device)
                renderTarget()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindTargetSwitch() {
        val target = requireActivity().findViewById<ImageButton>(R.id.demo_tv_target)
        targetButton = target
        target.visibility = View.VISIBLE
        // Gonzo 25/11 : un clic ouvre la liste des appareils (l'appui long fait de même).
        target.setOnClickListener { showDeviceMenu(it) }
        target.setOnLongClickListener {
            showDeviceMenu(it)
            true
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
                        android.widget.Toast.makeText(appContext, appContext.getString(R.string.tv_connected_to, result.connection.name), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    TvReconnect.Result.Forgotten -> {
                        TvTarget.forget(requireContext(), last)
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
        // Gonzo 25/11 : une fenêtre avec message n'affiche pas la liste (limite d'AlertDialog) ;
        // l'appareil injoignable est signalé par un toast et l'invitation de connexion s'ouvre directement.
        if (retry != null) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.tv_reconnect_unreachable, retry.name),
                android.widget.Toast.LENGTH_LONG).show()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tv_pair_title)
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
        targetButton?.setOnLongClickListener(null)
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

/** Gonzo 25/11 : au plus 4 appareils connectés dans la liste ; vert = connecté. */
private const val MAX_DEVICES = 4
private const val CONNECTED_GREEN = 0xFF3DDC84.toInt()
