package com.tomclaw.appsend.screen.tv

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.tomclaw.appsend.tvlink.TvLink
import java.io.File

/**
 * ATV-09 (fichiers à distance), côté TV : accès au stockage selon la version d'Android et
 * racines proposées au téléphone (stockage interne, clés USB, cartes SD).
 */
class TvRemoteFiles(private val context: Context) {

    /** `full` quand l'appli peut lire et écrire le stockage partagé, sinon `none`. */
    fun accessMode(): String {
        val granted = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                Environment.isExternalStorageLegacy() && hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return if (granted) "full" else "none"
    }

    /** Android 11+ : la permission passe par un écran système (et non une boîte de dialogue). */
    val needsSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 11+ : ouvre l'écran « Accès à tous les fichiers » de l'appli, sinon la liste
     * générale. Renvoie false si la TV n'a aucun de ces écrans (firmware incomplet).
     */
    fun openAllFilesSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    /** Racines visibles par le téléphone ; le stockage interne d'abord. */
    fun roots(): List<TvLink.FsRoot> {
        val found = LinkedHashMap<String, TvLink.FsRoot>()
        fun add(dir: File?, removableHint: Boolean?) {
            val canonical = dir?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return
            if (!canonical.isDirectory || found.containsKey(canonical.path)) return
            val removable = removableHint ?: runCatching { Environment.isExternalStorageRemovable(canonical) }.getOrDefault(true)
            found[canonical.path] = TvLink.FsRoot(canonical, label(canonical, removable), removable)
        }
        @Suppress("DEPRECATION")
        add(Environment.getExternalStorageDirectory(), false)
        // Clés USB et cartes SD montées : le dossier privé de l'appli sur chaque volume
        // (…/Android/data/<paquet>/files) donne la racine du volume.
        context.getExternalFilesDirs(null).drop(1).forEach { dir ->
            val path = dir?.path ?: return@forEach
            val index = path.indexOf("/Android/data/")
            if (index > 0) add(File(path.substring(0, index)), null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.forEach { volume ->
                if (volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    add(volume.directory, volume.isRemovable)
                }
            }
        }
        return found.values.toList()
    }

    private fun label(dir: File, removable: Boolean): String {
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) runCatching {
            context.getSystemService(StorageManager::class.java)?.getStorageVolume(dir)?.getDescription(context)
        }.getOrNull() else null
        return description?.takeIf { it.isNotBlank() }
            ?: if (removable) "USB · ${dir.name}" else context.getString(com.tomclaw.appsend.R.string.atv_internal_storage)
    }
}
