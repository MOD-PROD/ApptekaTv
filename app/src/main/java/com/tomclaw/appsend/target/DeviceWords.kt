package com.tomclaw.appsend.target

import android.content.Context
import com.tomclaw.appsend.tvlink.TvLinkSender
import java.util.Locale

/**
 * Gonzo 25/11 : les textes d'installation à distance disent « la TV » même quand l'appareil
 * piloté est une tablette ou un téléphone. On adapte le nom selon le type choisi pour
 * l'appareil (icône) : « Installer sur la tablette », « La tablette prépare le téléchargement… ».
 * Français et anglais ; les autres langues gardent « TV » en attendant leurs traductions.
 */
object DeviceWords {

    fun adapt(context: Context, text: String, connection: TvLinkSender.Connection? = TvTarget.current(context)): String {
        val kind = connection?.let { DeviceKinds.current(context, it) } ?: return text
        return adapt(text, kind, Locale.getDefault().language)
    }

    fun adapt(text: String, kind: DeviceKind, language: String): String = when (kind) {
        DeviceKind.TV, DeviceKind.BOX -> text
        DeviceKind.TABLET -> when (language) {
            "fr" -> text.replace(FR_FEMININE, "$1tablette")
            "en" -> text.replace(EN_ARTICLE, "$1tablet")
            else -> text
        }
        DeviceKind.PHONE -> when (language) {
            "fr" -> FR_PHONE.fold(text) { acc, (from, to) -> acc.replace(from, to) }
            "en" -> text.replace(EN_ARTICLE, "$1phone")
            else -> text
        }
    }

    /** « la TV », « cette TV », « une TV » → même article, autre nom (féminin). */
    private val FR_FEMININE = Regex("\\b((?:[Ll]a|[Cc]ette|[Uu]ne) )TV\\b")

    /** Smartphone est masculin : « de la TV » → « du smartphone », etc. */
    private val FR_PHONE = listOf(
        Regex("\\bde la TV\\b") to "du smartphone",
        Regex("\\bà la TV\\b") to "au smartphone",
        Regex("\\bla TV\\b") to "le smartphone",
        Regex("\\bLa TV\\b") to "Le smartphone",
        Regex("\\b([Cc])ette TV\\b") to "$1e smartphone",
        Regex("\\bune TV\\b") to "un smartphone",
        Regex("\\bUne TV\\b") to "Un smartphone",
        Regex("\\bAssociez-la\\b") to "Associez-le",
    )

    private val EN_ARTICLE = Regex("\\b((?:[Tt]he|[Tt]his|[Aa]) )TV\\b")
}
