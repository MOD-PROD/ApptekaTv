package com.tomclaw.appsend.tvlink

import java.util.UUID

/**
 * ATV-01 durcissement : registre des téléphones associés côté TV (logique pure, testée en JVM).
 *
 * Une entrée par installation du téléphone : une nouvelle association depuis la même
 * installation remplace l'ancienne, si bien que « Oublier » révoque vraiment le téléphone.
 * Seul le SHA-256 du jeton est conservé.
 */
internal object TvTokens {
    private val installPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val hashPattern = Regex("[0-9a-f]{64}")

    data class Entry(
        val id: String,
        val hash: String,
        /** Identifiant d'installation du téléphone ; vide pour une association antérieure. */
        val install: String,
        val created: Long,
        val device: String,
    ) {
        fun encode(): String = "$id:$hash:$install:$created:$device"
    }

    /** Format courant : id:hash:install:created:device (device peut contenir « : »). */
    fun decode(raw: String): Entry? {
        val parts = raw.split(':', limit = 5)
        if (parts.size != 5 || !parts[1].matches(hashPattern)) return null
        val created = parts[3].toLongOrNull() ?: return null
        if (parts[2].isNotEmpty() && !parts[2].matches(installPattern)) return null
        return Entry(parts[0], parts[1], parts[2], created, parts[4])
    }

    /** Ancien format v2 initial : id:hash:device. */
    fun decodeLegacy(raw: String): Entry? {
        val parts = raw.split(':', limit = 3)
        if (parts.size != 3 || !parts[1].matches(hashPattern)) return null
        return Entry(parts[0], parts[1], "", 0L, parts[2])
    }

    fun normalizeInstall(raw: String?): String =
        raw?.lowercase()?.takeIf { it.matches(installPattern) }.orEmpty()

    fun cleanDevice(raw: String?): String =
        raw.orEmpty().filter { !it.isISOControl() }.trim().take(40).ifBlank { "?" }

    /** Ajoute une association ; remplace celle de la même installation s'il y en a une. */
    fun add(entries: List<Entry>, hash: String, install: String, device: String, now: Long): List<Entry> {
        val kept = if (install.isEmpty()) entries else entries.filterNot { it.install == install }
        return kept + Entry(UUID.randomUUID().toString(), hash, install, now, device)
    }

    fun remove(entries: List<Entry>, id: String): List<Entry> = entries.filterNot { it.id == id }

    /** Compare tous les hachages en temps constant (pas d'arrêt au premier trouvé). */
    fun find(entries: List<Entry>, tokenHash: String?): Entry? {
        val supplied = tokenHash?.toByteArray() ?: return null
        var found: Entry? = null
        for (entry in entries) {
            if (TvFrames.matches(entry.hash.toByteArray(), supplied) && found == null) found = entry
        }
        return found
    }
}
