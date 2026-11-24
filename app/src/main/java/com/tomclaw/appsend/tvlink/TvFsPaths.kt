package com.tomclaw.appsend.tvlink

import java.io.File

/**
 * ATV-09 (fichiers à distance) : règles de chemins côté TV, logique pure testée en JVM.
 *
 * Le téléphone ne peut viser que des fichiers situés sous une racine annoncée par la TV
 * (stockage interne, clés USB). Chaque chemin est canonisé avant contrôle, si bien que « .. »
 * et les liens symboliques qui sortent d'une racine sont refusés. `Android/data` et
 * `Android/obb` (données privées des autres applis) restent toujours interdits.
 */
object TvFsPaths {
    private const val MAX_PATH = 4096
    private val forbidden = listOf("android/data", "android/obb")

    /** Nom d'un élément (création, renommage, réception) : pas de chemin, longueur bornée. */
    fun validName(name: String): Boolean =
        name.length in 1..255 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }

    /**
     * Renvoie le fichier canonique désigné par [path] s'il se trouve sous l'une des [roots]
     * (racine comprise) et hors des dossiers interdits, sinon null.
     */
    fun resolve(roots: List<File>, path: String): File? {
        if (path.isEmpty() || path.length > MAX_PATH || !path.startsWith("/") || path.contains('\u0000')) return null
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val root = rootOf(roots, target) ?: return null
        return target.takeIf { allowedUnder(root, it) }
    }

    /** Racine (canonique) qui contient [target], ou null. */
    fun rootOf(roots: List<File>, target: File): File? = roots
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter { inside(it, target) }
        .maxByOrNull { it.path.length }

    /** [target] est [root] ou se trouve dessous (chemins canoniques). */
    fun inside(root: File, target: File): Boolean {
        val base = root.path.trimEnd('/')
        return target.path == base || target.path.startsWith("$base/") || base.isEmpty()
    }

    private fun allowedUnder(root: File, target: File): Boolean {
        val relative = target.path.removePrefix(root.path.trimEnd('/')).trimStart('/').lowercase()
        return forbidden.none { relative == it || relative.startsWith("$it/") }
    }

    /** Nom libre dans [dir] : « nom.ext », sinon « nom (1).ext », « nom (2).ext »… */
    fun freeName(dir: File, name: String, exists: (File) -> Boolean = File::exists): String {
        if (!exists(File(dir, name))) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        for (i in 1..999) {
            val candidate = "$stem ($i)$ext"
            if (candidate.length <= 255 && !exists(File(dir, candidate))) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }
}
