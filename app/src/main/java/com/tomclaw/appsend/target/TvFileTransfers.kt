package com.tomclaw.appsend.target

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tomclaw.appsend.R
import com.tomclaw.appsend.tvlink.TvFsPaths
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * ATV-09 (fichiers à distance, côté téléphone) : file d'attente des transferts entre le
 * téléphone et la TV, indépendante de l'écran (une notification suit la progression).
 * Les fichiers reçus vont dans Download/Appteka TV.
 */
object TvFileTransfers {

    sealed class Job(val name: String) {
        @Volatile
        var cancelled = false

        class Download(val client: TvAppsClient, val path: String, name: String, val size: Long) : Job(name)
        class Upload(val client: TvAppsClient, val uri: Uri, val dir: String, name: String, val size: Long) : Job(name)
    }

    data class State(val job: Job?, val done: Long, val total: Long, val waiting: Int)

    interface Listener {
        fun onProgress(state: State)
        fun onFinished(job: Job, error: Throwable?)
    }

    private const val CHANNEL = "tv_files"
    private const val NOTIFICATION_ID = 0x7f09
    private const val RELATIVE_DIR = "Appteka TV"

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-files").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val queue = ArrayList<Job>()

    @Volatile
    private var current: Job? = null

    @Volatile
    private var lastState = State(null, 0, 0, 0)

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onProgress(lastState)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun enqueue(context: Context, job: Job) {
        val app = context.applicationContext
        synchronized(queue) { queue += job }
        publish(app, State(current, 0, 0, waiting()))
        executor.execute { run(app, job) }
    }

    /** Annule le transfert en cours et ceux en attente. */
    fun cancelAll() {
        synchronized(queue) { queue.forEach { it.cancelled = true } }
        current?.cancelled = true
    }

    private fun waiting(): Int = synchronized(queue) { queue.count { it !== current } }

    private fun run(context: Context, job: Job) {
        current = job
        var error: Throwable? = null
        try {
            if (job.cancelled) throw CancellationException()
            val progress: (Long, Long) -> Unit = { done, total ->
                if (job.cancelled) throw CancellationException()
                publish(context, State(job, done, total, waiting()))
            }
            publish(context, State(job, 0, (job as? Job.Download)?.size ?: (job as Job.Upload).size, waiting()))
            when (job) {
                is Job.Download -> download(context, job, progress)
                is Job.Upload -> upload(context, job, progress)
            }
        } catch (e: Throwable) {
            error = if (job.cancelled) CancellationException() else e
        } finally {
            synchronized(queue) { queue.remove(job) }
            current = null
            val left = waiting()
            publish(context, State(null, 0, 0, left))
            notifyDone(context, job, error)
            main.post { listeners.toList().forEach { it.onFinished(job, error) } }
        }
    }

    // ------------------------------------------------------------- TV → téléphone

    private fun download(context: Context, job: Job.Download, progress: (Long, Long) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, job.name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + RELATIVE_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("storage")
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { sink -> job.client.fsPull(job.path, sink, progress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), RELATIVE_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("storage")
            val target = File(dir, TvFsPaths.freeName(dir, job.name))
            try {
                target.outputStream().use { sink -> job.client.fsPull(job.path, sink, progress) }
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        }
    }

    // ------------------------------------------------------------- téléphone → TV

    private fun upload(context: Context, job: Job.Upload, progress: (Long, Long) -> Unit) {
        val resolver = context.contentResolver
        if (job.size >= 0) {
            checkNotNull(resolver.openInputStream(job.uri)).use { source -> push(job, source, job.size, progress) }
            return
        }
        // Taille inconnue (certains fournisseurs) : copie locale d'abord.
        val temp = File(context.cacheDir, "tv-upload-" + System.nanoTime())
        try {
            checkNotNull(resolver.openInputStream(job.uri)).use { input -> temp.outputStream().use { input.copyTo(it) } }
            temp.inputStream().use { source -> push(job, source, temp.length(), progress) }
        } finally {
            temp.delete()
        }
    }

    private fun push(job: Job.Upload, source: InputStream, size: Long, progress: (Long, Long) -> Unit) {
        job.client.fsPush(job.dir, job.name, source, size, progress)
    }

    /** Nom et taille (-1 si inconnue) d'un document choisi dans le sélecteur Android. */
    fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val clean = name.orEmpty().replace('/', '_').replace('\\', '_').filter { it.code >= 0x20 }.trim().take(200)
        return (clean.takeIf { TvFsPaths.validName(it) } ?: ("file-" + System.currentTimeMillis())) to size
    }

    // ------------------------------------------------------------- notifications

    private var lastNotified = 0L

    private fun publish(context: Context, state: State) {
        lastState = state
        main.post { listeners.toList().forEach { it.onProgress(state) } }
        val job = state.job ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotified < 500 && state.done != 0L && state.done != state.total) return
        lastNotified = now
        val text = when (job) {
            is Job.Download -> context.getString(R.string.tv_files_downloading, job.name)
            is Job.Upload -> context.getString(R.string.tv_files_uploading, job.name, job.client.tvName)
        }
        val percent = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (job is Job.Download) R.drawable.ic_download else R.drawable.ic_cloud_upload)
                .setContentTitle(text)
                .setContentText(if (state.waiting > 0) context.getString(R.string.tv_files_queue, state.waiting) else null)
                .setProgress(100, percent, state.total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
        )
    }

    private fun notifyDone(context: Context, job: Job, error: Throwable?) {
        val text = when {
            error is CancellationException -> context.getString(R.string.tv_files_cancelled)
            error != null -> context.getString(R.string.tv_files_failed, job.name)
            job is Job.Download -> context.getString(R.string.tv_files_downloaded, job.name)
            else -> context.getString(R.string.tv_files_uploaded, job.name, (job as Job.Upload).client.tvName)
        }
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (job is Job.Download) R.drawable.ic_download else R.drawable.ic_cloud_upload)
                .setContentTitle(text)
                .setAutoCancel(true)
                .setSilent(true)
                .build()
        )
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() is checked first
    private fun notify(context: Context, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.tv_files_channel))
                    .build()
            )
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
