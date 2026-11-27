package com.tomclaw.appsend.screen.tvfiles

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tomclaw.appsend.R
import com.tomclaw.appsend.target.TvAppsClient
import com.tomclaw.appsend.target.TvFileTransfers
import com.tomclaw.appsend.target.TvRemoteException
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ATV-09 (fichiers à distance, côté téléphone) : écran natif « Fichiers TV ».
 * Parcourir le stockage de la TV, télécharger, envoyer, renommer, supprimer, créer un dossier
 * et installer un APK. Toutes les opérations passent par la liaison chiffrée ATV-01 ;
 * la TV vérifie le droit « Fichiers » du téléphone et les chemins.
 */
class TvFilesActivity : AppCompatActivity(), TvFileTransfers.Listener {

    data class Root(val path: String, val label: String, val writable: Boolean, val free: Long, val total: Long)

    data class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long, val count: Int)

    private lateinit var backend: FilesBackend
    /** Liaison TV (transferts) : null quand on explore cet appareil. */
    private var client: TvAppsClient? = null
    private val localBackend: LocalFilesBackend? get() = backend as? LocalFilesBackend
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-files-ui").apply { isDaemon = true } }

    private lateinit var toolbar: Toolbar
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var messageBox: LinearLayout
    private lateinit var message: TextView
    private lateinit var retry: MaterialButton
    private lateinit var upload: ExtendedFloatingActionButton
    private lateinit var transfer: LinearLayout
    private lateinit var transferText: TextView
    private lateinit var transferProgress: LinearProgressIndicator

    private var roots: List<Root> = emptyList()
    private var path: String? = null
    private var parentPath: String? = null
    private var writable = false
    private var entries: List<Entry> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var clipboard: List<String> = emptyList()
    private var copying = false
    private var showHidden = false
    private var sort = SORT_NAME
    private val adapter = FilesAdapter()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dir = path ?: return@registerForActivityResult
        val local = localBackend
        if (local != null) importLocal(local, uris, dir) else uris.forEach { uri -> enqueueUpload(uri, dir) }
    }

    private var pendingDownloads: List<Entry> = emptyList()
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val waiting = pendingDownloads
        pendingDownloads = emptyList()
        if (granted) waiting.forEach(::enqueueDownload) else toast(getString(R.string.tv_files_download_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ATV (Gonzo 24/11) : la TV en mode TV, sinon les fichiers de cet appareil.
        val tv = TvAppsClient.current(this)
        client = tv
        backend = if (tv != null) RemoteFilesBackend(tv) else LocalFilesBackend(this)
        setContentView(R.layout.tv_files_activity)
        toolbar = findViewById(R.id.toolbar)
        refresh = findViewById(R.id.tv_files_refresh)
        list = findViewById(R.id.tv_files_list)
        messageBox = findViewById(R.id.tv_files_message_box)
        message = findViewById(R.id.tv_files_message)
        retry = findViewById(R.id.tv_files_retry)
        upload = findViewById(R.id.tv_files_upload)
        transfer = findViewById(R.id.tv_files_transfer)
        transferText = findViewById(R.id.tv_files_transfer_text)
        transferProgress = findViewById(R.id.tv_files_transfer_progress)

        clipboard = savedInstanceState?.getStringArrayList("clipboard") ?: emptyList()
        path = savedInstanceState?.getString(STATE_PATH)
        showHidden = savedInstanceState?.getBoolean(STATE_HIDDEN) ?: false
        sort = savedInstanceState?.getInt(STATE_SORT) ?: SORT_NAME

        toolbar.title = getString(R.string.tv_files_title, backend.name)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onMenu)
        showMenu()

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        refresh.setOnRefreshListener { reload() }
        retry.setOnClickListener { if (localStorageAsked) requestLocalStorage() else connect() }
        upload.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        if (backend.local) upload.text = getString(R.string.files_import)
        findViewById<MaterialButton>(R.id.tv_files_transfer_cancel).setOnClickListener { TvFileTransfers.cancelAll() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) upload.shrink() else if (dy < -8) upload.extend()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selected.isNotEmpty() -> clearSelection()
                    parentPath != null -> open(parentPath!!)
                    else -> finish()
                }
            }
        })
        connect()
    }

    override fun onStart() {
        super.onStart()
        if (::backend.isInitialized && !backend.local) TvFileTransfers.addListener(this)
    }

    override fun onStop() {
        TvFileTransfers.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("clipboard", ArrayList(clipboard))
        outState.putString(STATE_PATH, path)
        outState.putBoolean(STATE_HIDDEN, showHidden)
        outState.putInt(STATE_SORT, sort)
    }

    // ------------------------------------------------------------------ chargement

    /** Ouvre l'accès (accord sur la TV la première fois), puis le dossier courant ou la première racine. */
    private fun connect() {
        if (!backend.local) showMessage(getString(R.string.tv_files_waiting_tv, backend.name), retry = false)
        refresh.isRefreshing = true
        background({ backend.access() }) { response ->
            if (response.optString("access") != "full") {
                refresh.isRefreshing = false
                if (backend.local) askLocalStorage()
                else showMessage(getString(R.string.tv_files_storage_needed, backend.name), retry = true)
                return@background
            }
            val array = response.optJSONArray("roots")
            roots = (0 until (array?.length() ?: 0)).map { i ->
                val root = array!!.getJSONObject(i)
                Root(root.getString("path"), root.optString("label"), root.optBoolean("writable"), root.optLong("free"), root.optLong("total"))
            }
            if (roots.isEmpty()) {
                refresh.isRefreshing = false
                showMessage(getString(R.string.tv_files_storage_needed, backend.name), retry = true)
                return@background
            }
            val current = path?.takeIf { p -> roots.any { p == it.path || p.startsWith(it.path.trimEnd('/') + "/") } }
            open(current ?: roots.first().path)
        }
    }

    private fun reload() {
        val current = path
        if (current == null) connect() else open(current)
    }

    private fun open(target: String) {
        refresh.isRefreshing = true
        background({ backend.list(target, showHidden) }) { response ->
            refresh.isRefreshing = false
            path = response.optString("path", target)
            parentPath = response.optString("parent").takeIf { !response.isNull("parent") && it.isNotEmpty() }
            writable = response.optBoolean("writable")
            val array = response.optJSONArray("entries")
            entries = (0 until (array?.length() ?: 0)).map { i ->
                val item = array!!.getJSONObject(i)
                val name = item.getString("name")
                Entry(name, path!!.trimEnd('/') + "/" + name, item.optBoolean("dir"), item.optLong("size"),
                    item.optLong("modified"), item.optInt("count", -1))
            }
            selected.clear()
            showList()
        }
    }

    private fun showList() {
        val sorted = when (sort) {
            SORT_DATE -> entries.sortedWith(compareBy<Entry>({ !it.dir }, { -it.modified }))
            SORT_SIZE -> entries.sortedWith(compareBy<Entry>({ !it.dir }, { -it.size }, { it.name.lowercase() }))
            else -> entries.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() }))
        }
        adapter.items = sorted
        adapter.notifyDataSetChanged()
        messageBox.isVisible = sorted.isEmpty()
        retry.isVisible = false
        message.text = getString(R.string.tv_files_empty)
        upload.isVisible = writable
        showMenu()
        updateTitle()
    }

    private fun updateTitle() {
        if (selected.isNotEmpty()) {
            toolbar.title = getString(R.string.tv_files_selected, selected.size)
            toolbar.subtitle = null
            return
        }
        toolbar.title = getString(R.string.tv_files_title, backend.name)
        val current = path ?: return
        val root = roots.filter { current == it.path || current.startsWith(it.path.trimEnd('/') + "/") }.maxByOrNull { it.path.length }
        toolbar.subtitle = if (root == null) current else root.label + current.removePrefix(root.path.trimEnd('/')).ifEmpty { "" }
    }

    private fun showMessage(text: String, retry: Boolean) {
        adapter.items = emptyList()
        adapter.notifyDataSetChanged()
        messageBox.isVisible = true
        message.text = text
        this.retry.isVisible = retry
        upload.isVisible = false
    }

    // ------------------------------------------------------------------ menus

    private fun showMenu() {
        toolbar.menu.clear()
        if (selected.isNotEmpty()) {
            toolbar.inflateMenu(R.menu.tv_files_selection_menu)
            toolbar.menu.add(0, MENU_COPY, 0, R.string.tv_files_copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            toolbar.menu.findItem(R.id.tv_files_menu_download)?.isVisible = !backend.local
            toolbar.setNavigationOnClickListener { clearSelection() }
        } else {
            toolbar.inflateMenu(R.menu.tv_files_menu)
            if (clipboard.isNotEmpty()) {
                toolbar.menu.add(0, MENU_PASTE, 0, R.string.tv_files_paste).apply {
                    isEnabled = writable && !copying
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                toolbar.menu.add(0, MENU_CLEAR_COPY, 1, android.R.string.cancel).isEnabled = !copying
            }
            toolbar.menu.findItem(R.id.tv_files_menu_hidden)?.isChecked = showHidden
            toolbar.menu.findItem(
                when (sort) {
                    SORT_DATE -> R.id.tv_files_sort_date
                    SORT_SIZE -> R.id.tv_files_sort_size
                    else -> R.id.tv_files_sort_name
                }
            )?.isChecked = true
            toolbar.setNavigationOnClickListener { finish() }
        }
    }

    private fun onMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_COPY -> { clipboard = selected.toList(); clearSelection() }
            MENU_PASTE -> paste()
            MENU_CLEAR_COPY -> { clipboard = emptyList(); showMenu() }
            R.id.tv_files_menu_volumes -> chooseRoot()
            R.id.tv_files_menu_mkdir -> askName(getString(R.string.tv_files_new_folder), "") { name ->
                val dir = path ?: return@askName
                action({ backend.mkdir(dir, name) }) { reload() }
            }
            R.id.tv_files_menu_hidden -> {
                showHidden = !showHidden
                item.isChecked = showHidden
                reload()
            }
            R.id.tv_files_sort_name, R.id.tv_files_sort_date, R.id.tv_files_sort_size -> {
                sort = when (item.itemId) {
                    R.id.tv_files_sort_date -> SORT_DATE
                    R.id.tv_files_sort_size -> SORT_SIZE
                    else -> SORT_NAME
                }
                item.isChecked = true
                showList()
            }
            R.id.tv_files_menu_refresh -> reload()
            R.id.tv_files_menu_download -> download(entries.filter { it.path in selected && !it.dir })
            R.id.tv_files_menu_delete -> confirmDelete(entries.filter { it.path in selected })
            else -> return false
        }
        return true
    }

    private fun paste() {
        val dest = path ?: return
        if (!writable || copying || clipboard.isEmpty()) return
        val sources = clipboard.toList()
        copying = true
        showMenu()
        refresh.isRefreshing = true
        background({
            try { backend.copy(sources, dest) }
            finally { runOnUiThread { copying = false; showMenu() } }
        }) { response ->
            refresh.isRefreshing = false
            if (response.optBoolean("ok")) clipboard = emptyList()
            reload()
        }
    }

    private fun chooseRoot() {
        if (roots.isEmpty()) return
        val labels = roots.map { root ->
            if (root.total > 0) root.label + " · " + getString(
                R.string.tv_storage_summary,
                Formatter.formatShortFileSize(this, root.free),
                Formatter.formatShortFileSize(this, root.total),
            ) else root.label
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_files_volumes)
            .setItems(labels) { _, which -> open(roots[which].path) }
            .show()
    }

    // ------------------------------------------------------------------ actions

    private fun onClick(entry: Entry) {
        if (selected.isNotEmpty()) return toggle(entry)
        if (entry.dir) return open(entry.path)
        val isApk = entry.name.endsWith(".apk", ignoreCase = true)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val local = localBackend
        if (local != null) {
            actions += getString(R.string.files_open) to { openLocal(local, entry, install = false) }
            if (isApk) actions += getString(R.string.files_install_here) to { install(entry) }
        } else {
            actions += getString(R.string.tv_files_download) to { download(listOf(entry)) }
            if (isApk) actions += getString(R.string.tv_files_install) to { install(entry) }
        }
        if (writable) {
            actions += getString(R.string.tv_files_rename) to { rename(entry) }
            actions += getString(R.string.tv_files_delete) to { confirmDelete(listOf(entry)) }
        }
        actions += getString(R.string.tv_files_select) to { toggle(entry) }
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun onLongClick(entry: Entry) {
        if (selected.isEmpty() && entry.dir && writable) {
            // Dossier : renommer, supprimer ou sélectionner.
            val actions = listOf(
                getString(R.string.tv_files_rename) to { rename(entry) },
                getString(R.string.tv_files_delete) to { confirmDelete(listOf(entry)) },
                getString(R.string.tv_files_select) to { toggle(entry) },
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(entry.name)
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .show()
            return
        }
        toggle(entry)
    }

    private fun toggle(entry: Entry) {
        val wasEmpty = selected.isEmpty()
        if (!selected.remove(entry.path)) selected += entry.path
        if (wasEmpty != selected.isEmpty()) showMenu()
        adapter.notifyDataSetChanged()
        upload.isVisible = selected.isEmpty() && writable
        updateTitle()
    }

    private fun clearSelection() {
        selected.clear()
        showMenu()
        adapter.notifyDataSetChanged()
        upload.isVisible = writable
        updateTitle()
    }

    private fun rename(entry: Entry) {
        askName(getString(R.string.tv_files_rename), entry.name) { name ->
            if (name != entry.name) action({ backend.rename(entry.path, name) }) { reload() }
        }
    }

    private fun confirmDelete(items: List<Entry>) {
        if (items.isEmpty()) return
        val what = if (items.size == 1) items.first().name else getString(R.string.tv_files_delete_many, items.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_files_delete)
            .setMessage(getString(R.string.tv_files_delete_confirm, what, backend.name))
            .setPositiveButton(R.string.tv_files_delete) { _, _ ->
                val folders = items.filter { it.dir && it.count != 0 }
                if (folders.isEmpty()) delete(items) else MaterialAlertDialogBuilder(this)
                    // Gonzo 24/11 : un dossier non vide demande une seconde confirmation.
                    .setTitle(R.string.tv_files_delete)
                    .setMessage(getString(R.string.tv_files_delete_folder_confirm, folders.joinToString { it.name }))
                    .setPositiveButton(R.string.tv_files_delete) { _, _ -> delete(items) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete(items: List<Entry>) {
        action({ backend.delete(items.map { it.path }) }) {
            toast(getString(R.string.tv_files_deleted))
            reload()
        }
    }

    private fun install(entry: Entry) {
        val local = localBackend
        if (local != null) return openLocal(local, entry, install = true)
        action({ backend.install(entry.path) }) { toast(getString(R.string.tv_files_install_sent, backend.name)) }
    }

    private fun download(items: List<Entry>) {
        if (items.isEmpty()) return
        clearSelection()
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingDownloads = items
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            items.forEach(::enqueueDownload)
        }
    }

    private fun enqueueDownload(entry: Entry) {
        val tv = client ?: return
        TvFileTransfers.enqueue(this, TvFileTransfers.Job.Download(tv, entry.path, entry.name, entry.size))
    }

    private fun enqueueUpload(uri: Uri, dir: String) {
        val (name, size) = TvFileTransfers.describe(this, uri)
        val tv = client ?: return
        TvFileTransfers.enqueue(this, TvFileTransfers.Job.Upload(tv, uri, dir, name, size))
    }

    // ------------------------------------------------------------------ cet appareil

    private var localStorageAsked = false
    private var awaitingSettings = false

    private val localStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { connect() }

    /** Fichiers de cet appareil : l'accès à tous les fichiers est demandé avant de parcourir. */
    private fun askLocalStorage() {
        localStorageAsked = true
        showMessage(getString(R.string.files_local_storage_needed), retry = true)
        retry.text = getString(R.string.tv_files_storage_allow)
    }

    private fun requestLocalStorage() {
        val local = localBackend ?: return
        localStorageAsked = false
        retry.text = getString(R.string.tv_files_retry)
        if (local.needsSettingsScreen) {
            awaitingSettings = local.openAllFilesSettings()
            if (!awaitingSettings) toast(getString(R.string.files_local_storage_needed))
            showMessage(getString(R.string.files_local_storage_return), retry = true)
        } else {
            localStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Retour de l'écran système « Accès à tous les fichiers ».
        if (awaitingSettings) {
            awaitingSettings = false
            connect()
        }
    }

    private fun openLocal(local: LocalFilesBackend, entry: Entry, install: Boolean) {
        val file = runCatching { local.file(entry.path) }.getOrElse { return showError(it) }
        val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file) }
            .getOrElse { return toast(getString(R.string.files_cannot_open, entry.name)) }
        val type = if (install || entry.name.endsWith(".apk", ignoreCase = true)) "application/vnd.android.package-archive"
            else android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(if (install) intent else Intent.createChooser(intent, entry.name))
        } catch (_: android.content.ActivityNotFoundException) {
            toast(getString(R.string.files_cannot_open, entry.name))
        }
    }

    private fun importLocal(local: LocalFilesBackend, uris: List<Uri>, dir: String) {
        if (uris.isEmpty()) return
        action({
            var last = ""
            uris.forEach { uri ->
                val (name, size) = TvFileTransfers.describe(this, uri)
                last = local.import(uri, dir, name, size)
            }
            JSONObject().put("ok", true).put("name", last)
        }) {
            toast(getString(R.string.files_imported, uris.size))
            if (dir == path) reload()
        }
    }

    private fun askName(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            setText(initial)
            hint = getString(R.string.tv_files_name)
            val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
            setSelection(0, dot)
        }
        val box = FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) done(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // ------------------------------------------------------------------ transferts

    override fun onProgress(state: TvFileTransfers.State) {
        val job = state.job
        transfer.isVisible = job != null || state.waiting > 0
        if (job == null) return
        val label = when (job) {
            is TvFileTransfers.Job.Download -> getString(R.string.tv_files_downloading, job.name)
            is TvFileTransfers.Job.Upload -> getString(R.string.tv_files_uploading, job.name, backend.name)
        }
        transferText.text = if (state.waiting > 0) label + " · " + getString(R.string.tv_files_queue, state.waiting) else label
        transferProgress.progress = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
    }

    override fun onFinished(job: TvFileTransfers.Job, error: Throwable?) {
        when {
            error is CancellationException -> toast(getString(R.string.tv_files_cancelled))
            error != null -> toast(getString(R.string.tv_files_failed, job.name) + " (" + describe(error) + ")")
            job is TvFileTransfers.Job.Download -> toast(getString(R.string.tv_files_downloaded, job.name))
            job is TvFileTransfers.Job.Upload -> {
                toast(getString(R.string.tv_files_uploaded, job.name, backend.name))
                if (job.dir == path) reload()
            }
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private fun action(call: () -> JSONObject, done: (JSONObject) -> Unit) {
        refresh.isRefreshing = true
        background(call) { response ->
            refresh.isRefreshing = false
            done(response)
        }
    }

    /** Appel bloquant sur le fil d'E/S, réponse sur le fil principal ; les erreurs sont affichées. */
    private fun background(call: () -> JSONObject, done: (JSONObject) -> Unit) {
        try {
            io.execute {
                val result = runCatching(call)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.onSuccess(done).onFailure(::showError)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun showError(error: Throwable) {
        refresh.isRefreshing = false
        val code = (error as? TvRemoteException)?.code
        if (backend.local && code == null) return toast(error.message ?: error.javaClass.simpleName)
        when (code) {
            "fs_refused" -> showMessage(getString(R.string.tv_files_refused, backend.name), retry = true)
            "fs_storage" -> showMessage(getString(R.string.tv_files_storage_needed, backend.name), retry = true)
            "fs_forbidden" -> connect()
            "unknown", "version" -> showMessage(getString(R.string.tv_files_needs_update, backend.name), retry = true)
            "unauthorized" -> {
                toast(getString(R.string.tv_unauthorized))
                finish()
            }
            null -> if (entries.isEmpty()) showMessage(getString(R.string.tv_files_offline, backend.name), retry = true)
                else toast(getString(R.string.tv_files_offline, backend.name))
            else -> toast(describe(error))
        }
    }

    private fun describe(error: Throwable): String = when ((error as? TvRemoteException)?.code) {
        "read_only" -> getString(R.string.tv_files_read_only)
        "exists" -> getString(R.string.tv_files_exists)
        "storage" -> getString(R.string.tv_no_space, backend.name)
        "not_apk" -> getString(R.string.tv_not_apk, backend.name)
        null -> getString(R.string.tv_files_offline, backend.name)
        else -> getString(R.string.tv_files_error, backend.name, (error as TvRemoteException).code)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private inner class FilesAdapter : RecyclerView.Adapter<FileHolder>() {
        var items: List<Entry> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FileHolder(LayoutInflater.from(parent.context).inflate(R.layout.tv_files_item, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val entry = items[position]
            holder.icon.setImageResource(
                when {
                    entry.dir -> R.drawable.ic_tv_folder
                    entry.name.endsWith(".apk", ignoreCase = true) -> R.drawable.ic_file_apk_box
                    else -> R.drawable.ic_tv_file
                }
            )
            holder.name.text = entry.name
            val date = if (entry.modified > 0) DateFormat.getDateFormat(this@TvFilesActivity).format(Date(entry.modified)) else ""
            holder.info.text = when {
                entry.dir && entry.count >= 0 -> listOf(getString(R.string.tv_files_items, entry.count), date)
                entry.dir -> listOf(date)
                else -> listOf(Formatter.formatShortFileSize(this@TvFilesActivity, entry.size), date)
            }.filter { it.isNotEmpty() }.joinToString(" · ")
            holder.check.isVisible = entry.path in selected
            holder.itemView.isActivated = entry.path in selected
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }
    }

    private class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tv_file_icon)
        val name: TextView = view.findViewById(R.id.tv_file_name)
        val info: TextView = view.findViewById(R.id.tv_file_info)
        val check: ImageView = view.findViewById(R.id.tv_file_check)
    }

    companion object {
        private const val MENU_COPY = 0x7f0f7a01
        private const val MENU_PASTE = 0x7f0f7a02
        private const val MENU_CLEAR_COPY = 0x7f0f7a03
        private const val STATE_PATH = "path"
        private const val STATE_HIDDEN = "hidden"
        private const val STATE_SORT = "sort"
        private const val SORT_NAME = 0
        private const val SORT_DATE = 1
        private const val SORT_SIZE = 2
    }
}

fun createTvFilesActivityIntent(context: Context): Intent = Intent(context, TvFilesActivity::class.java)
