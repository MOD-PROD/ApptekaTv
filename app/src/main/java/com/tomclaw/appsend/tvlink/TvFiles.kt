package com.tomclaw.appsend.tvlink

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * ATV-09 (fichiers à distance) : opérations sur les fichiers de la TV, en java.io pur.
 * Tous les chemins reçus passent par [TvFsPaths.resolve] ; les réponses suivent le format
 * de la liaison (`ok` / `error`).
 */
object TvFiles {
    /** Taille maximale d'un fichier échangé (au-delà, refus avant tout transfert). */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 5000
    private const val MAX_DELETE = 20_000
    private const val MAX_COPY = 20_000
    private const val SPACE_MARGIN = 20L * 1024 * 1024
    const val PART_SUFFIX = ".atvpart"

    fun list(roots: List<File>, path: String, hidden: Boolean): JSONObject {
        val dir = TvFsPaths.resolve(roots, path) ?: return error("bad_path")
        if (!dir.isDirectory) return error("not_found")
        val children = dir.listFiles() ?: return error("unreadable")
        val visible = children.filter { (hidden || !it.name.startsWith(".")) && !it.name.endsWith(PART_SUFFIX) }
            // Android/data, Android/obb et les liens qui sortent des racines ne sont pas montrés.
            .filter { TvFsPaths.resolve(roots, it.path) != null }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val entries = JSONArray()
        visible.take(MAX_ENTRIES).forEach { entries.put(entry(it)) }
        val root = TvFsPaths.rootOf(roots, dir)
        return ok().put("path", dir.path)
            .put("parent", if (root != null && dir.path != root.path) dir.parentFile?.path else JSONObject.NULL)
            .put("writable", dir.canWrite())
            .put("entries", entries)
            .put("truncated", visible.size > MAX_ENTRIES)
    }

    fun entry(file: File): JSONObject = JSONObject()
        .put("name", file.name)
        .put("dir", file.isDirectory)
        .put("size", if (file.isDirectory) 0L else file.length())
        .put("modified", file.lastModified())
        .put("count", if (file.isDirectory) (file.list()?.size ?: -1) else -1)

    fun mkdir(roots: List<File>, parent: String, name: String): JSONObject {
        if (!TvFsPaths.validName(name)) return error("bad_name")
        val dir = TvFsPaths.resolve(roots, parent) ?: return error("bad_path")
        if (!dir.isDirectory) return error("not_found")
        val target = TvFsPaths.resolve(roots, File(dir, name).path) ?: return error("bad_path")
        if (target.exists()) return error("exists")
        return if (target.mkdir()) ok().put("path", target.path) else error("read_only")
    }

    fun rename(roots: List<File>, path: String, name: String): JSONObject {
        if (!TvFsPaths.validName(name)) return error("bad_name")
        val source = TvFsPaths.resolve(roots, path) ?: return error("bad_path")
        if (!source.exists()) return error("not_found")
        if (roots.any { runCatching { it.canonicalFile }.getOrNull() == source }) return error("bad_path")
        val target = TvFsPaths.resolve(roots, File(source.parentFile, name).path) ?: return error("bad_path")
        if (target.exists()) return error("exists")
        return if (source.renameTo(target)) ok().put("path", target.path) else error("read_only")
    }

    /** Supprime [paths] (dossiers compris, sans suivre les liens). Renvoie le nombre d'éléments supprimés. */
    fun delete(roots: List<File>, paths: List<String>): JSONObject {
        val canonicalRoots = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        val targets = paths.map { TvFsPaths.resolve(roots, it) ?: return error("bad_path") }
        if (targets.any { it in canonicalRoots }) return error("bad_path")
        val budget = intArrayOf(MAX_DELETE)
        var deleted = 0
        var failed = 0
        for (target in targets) {
            if (!target.exists()) continue
            if (deleteTree(target, budget)) deleted++ else failed++
        }
        return if (failed == 0) ok().put("deleted", deleted)
        else error(if (budget[0] <= 0) "too_many" else "read_only").put("deleted", deleted).put("failed", failed)
    }

    private fun deleteTree(file: File, budget: IntArray): Boolean {
        if (--budget[0] < 0) return false
        if (file.isDirectory && !isSymlink(file)) {
            file.listFiles()?.forEach { if (!deleteTree(it, budget)) return false }
        }
        return file.delete()
    }

    /**
     * Copie native sur la TV de [paths] vers le dossier [destDir].
     * Gère fichiers et dossiers (récursif), doublons via freeName, budget d'éléments.
     * Refuse de copier une racine ou un chemin qui sortirait des racines autorisées.
     */
    fun copy(roots: List<File>, paths: List<String>, destDir: String): JSONObject {
        if (paths.isEmpty() || paths.size > 500) return error("bad_request")
        val dest = TvFsPaths.resolve(roots, destDir) ?: return error("bad_path")
        if (!dest.isDirectory) return error("not_found")
        if (!dest.canWrite()) return error("read_only")

        val canonicalRoots = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        val sources = paths.map { TvFsPaths.resolve(roots, it) ?: return error("bad_path") }
        if (sources.any { it in canonicalRoots }) return error("bad_path")

        // Empêcher de copier un dossier dans lui-même ou un de ses descendants.
        val destCanon = runCatching { dest.canonicalFile }.getOrNull() ?: return error("bad_path")
        for (src in sources) {
            val srcCanon = runCatching { src.canonicalFile }.getOrNull() ?: return error("bad_path")
            if (srcCanon == destCanon || destCanon.path.startsWith(srcCanon.path + "/")) {
                return error("bad_path")
            }
        }

        val budget = intArrayOf(MAX_COPY)
        var copied = 0
        var failed = 0
        val results = JSONArray()

        for (source in sources) {
            if (!source.exists()) {
                failed++
                continue
            }
            val name = TvFsPaths.freeName(dest, source.name)
            val target = TvFsPaths.resolve(roots, File(dest, name).path) ?: run {
                failed++
                return@for
            }
            if (copyTree(source, target, roots, budget)) {
                copied++
                results.put(JSONObject().put("from", source.path).put("to", target.path).put("name", name))
            } else {
                failed++
            }
        }

        return if (failed == 0) ok().put("copied", copied).put("items", results)
        else error(if (budget[0] <= 0) "too_many" else "read_only")
            .put("copied", copied).put("failed", failed).put("items", results)
    }

    private fun copyTree(source: File, target: File, roots: List<File>, budget: IntArray): Boolean {
        if (--budget[0] < 0) return false
        // Sécurité : le chemin cible doit rester sous une racine.
        if (TvFsPaths.resolve(roots, target.path) == null) return false

        if (source.isDirectory) {
            if (isSymlink(source)) return false
            if (!target.exists() && !target.mkdir()) return false
            if (!target.isDirectory) return false
            val children = source.listFiles() ?: return false
            for (child in children) {
                if (child.name.endsWith(PART_SUFFIX)) continue
                val childTarget = File(target, child.name)
                if (!copyTree(child, childTarget, roots, budget)) return false
            }
            return true
        }

        // Fichier
        if (!source.isFile || !source.canRead()) return false
        if (source.length() > MAX_FILE_BYTES) return false
        if (target.parentFile?.usableSpace ?: 0L < source.length() + SPACE_MARGIN) return false

        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            true
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    /** Lien symbolique : son chemin canonique diffère de celui de son nom dans le dossier parent. */
    fun isSymlink(file: File): Boolean = runCatching {
        val parent = file.parentFile?.canonicalFile ?: return false
        File(parent, file.name).canonicalPath != File(parent, file.name).absolutePath
    }.getOrDefault(false)

    /** Fichier à envoyer au téléphone (`fs_pull`), ou une erreur. */
    fun readable(roots: List<File>, path: String): Pair<File?, JSONObject?> {
        val file = TvFsPaths.resolve(roots, path) ?: return null to error("bad_path")
        if (!file.isFile) return null to error("not_found")
        if (!file.canRead()) return null to error("unreadable")
        if (file.length() !in 0..MAX_FILE_BYTES) return null to error("too_big")
        return file to null
    }

    /**
     * Prépare la réception d'un fichier de [size] octets nommé [name] dans le dossier [dirPath] :
     * renvoie (fichier temporaire, fichier final) ou une erreur.
     */
    fun incoming(roots: List<File>, dirPath: String, name: String, size: Long): Pair<Pair<File, File>?, JSONObject?> {
        if (!TvFsPaths.validName(name) || name.endsWith(PART_SUFFIX)) return null to error("bad_name")
        if (size !in 0..MAX_FILE_BYTES) return null to error("too_big")
        val dir = TvFsPaths.resolve(roots, dirPath) ?: return null to error("bad_path")
        if (!dir.isDirectory) return null to error("not_found")
        if (!dir.canWrite()) return null to error("read_only")
        if (dir.usableSpace < size + SPACE_MARGIN) return null to error("storage")
        val finalName = TvFsPaths.freeName(dir, name)
        val target = TvFsPaths.resolve(roots, File(dir, finalName).path) ?: return null to error("bad_path")
        val part = File(dir, ".$finalName.${System.nanoTime()}$PART_SUFFIX")
        return (part to target) to null
    }

    /** Termine une réception : le fichier temporaire prend son nom final (sans écraser). */
    fun complete(part: File, target: File): JSONObject {
        val name = if (target.exists()) TvFsPaths.freeName(target.parentFile!!, target.name) else target.name
        val final = File(target.parentFile, name)
        return if (part.renameTo(final)) ok().put("name", final.name).put("path", final.path)
        else {
            part.delete()
            error("read_only")
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun error(reason: String) = JSONObject().put("ok", false).put("error", reason)
}
