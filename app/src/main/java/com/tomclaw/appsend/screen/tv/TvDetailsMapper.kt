package com.tomclaw.appsend.screen.tv

import com.tomclaw.appsend.dto.AppEntity
import com.tomclaw.appsend.screen.details.api.Details
import java.util.Locale

/**
 * Convertit les réponses de l'API Appteka en objets simples pour la page HTML TV.
 */
object TvDetailsMapper {

    fun card(app: AppEntity): Map<String, Any?> = mapOf(
        "id" to app.appId,
        "name" to app.title,
        "icon" to (app.icon ?: ""),
        "downloads" to formatDownloads(app.downloads),
        "size" to formatSize(app.size),
        "package" to app.packageName,
        "version" to app.verName,
        "rating" to app.rating,
        "abi" to app.abi.orEmpty(),
        "category" to app.category?.id,
        "time" to app.time,
        "versionCode" to app.verCode,
        // Gonzo 25/11 : filtres « Open source » et « Exclusive » de la recherche TV.
        "exclusive" to app.exclusive,
        "openSource" to !app.sourceUrl.isNullOrBlank(),
    )

    fun details(d: Details, locale: String): Map<String, Any?> {
        val info = d.info
        val meta = d.meta
        val translation = d.translation
        return mapOf(
            "id" to info.appId,
            "name" to (info.labels?.get(locale) ?: info.label ?: info.packageName),
            "icon" to (info.icon ?: ""),
            "package" to info.packageName,
            "version" to info.version,
            "versionCode" to info.versionCode,
            "size" to formatSize(info.size),
            "downloads" to (info.downloads ?: 0),
            "downloadsText" to formatDownloads(info.downloads ?: 0),
            "favorites" to (info.favorites ?: 0),
            "time" to info.time,
            "sdkVersion" to info.sdkVersion,
            "android" to info.androidVersion,
            "abi" to info.abi.orEmpty(),
            "permissions" to info.permissions.orEmpty(),
            "languages" to info.languages.orEmpty().map { it.name },
            "sha1" to info.sha1,
            "author" to info.author.name,
            "authorId" to info.author.id,
            "fileStatus" to info.fileStatus,
            "category" to meta?.category?.name?.let { names ->
                names[locale] ?: names["en"] ?: names.values.firstOrNull()
            },
            "description" to meta?.description,
            "whatsNew" to meta?.whatsNew,
            "aiStatus" to meta?.aiStatus,
            "aiNote" to (translation?.aiNote ?: meta?.aiNote),
            "aiShort" to meta?.aiShortDescription,
            "aiTags" to meta?.aiTags.orEmpty(),
            "rating" to meta?.rating,
            "rateCount" to (meta?.rateCount ?: 0),
            "scores" to meta?.scores?.let {
                listOf(it.five, it.four, it.three, it.two, it.one)
            },
            "openSource" to (meta?.openSource == true),
            "sourceUrl" to meta?.sourceUrl,
            "exclusive" to (meta?.exclusive == true),
            "screenshots" to meta?.screenshots.orEmpty().map {
                mapOf("preview" to it.preview, "original" to it.original)
            },
            "security" to d.security?.let {
                mapOf("status" to it.status, "verdict" to it.verdict, "score" to it.score)
            },
            "official" to (d.developer?.isOfficial == true),
            "versions" to d.versions.orEmpty().map {
                mapOf(
                    "id" to it.appId,
                    "name" to it.verName,
                    "code" to it.verCode,
                    "sdk" to it.sdkVersion,
                    "downloads" to (it.downloads ?: 0),
                    "current" to (it.appId == info.appId),
                )
            },
            "reviews" to d.ratingsList.orEmpty().take(10).map {
                mapOf(
                    "score" to it.score,
                    "text" to it.text,
                    "author" to it.user.name,
                )
            },
            "similar" to d.similar.orEmpty().map { card(it) },
            "link" to d.link,
        )
    }

    fun formatDownloads(count: Int): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format(Locale.US, "%.1fk", count / 1_000f)
        else -> count.toString()
    }

    fun formatSize(bytes: Long): String {
        val mb = bytes / (1024f * 1024f)
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", bytes / 1024f)
    }
}
