package com.tomclaw.appsend.core

const val HOST_URL: String = "https://appteka.store"
const val STAND_BY_HOST_URL: String = "https://tomclaw.com"
const val BANANALYTICS_URL: String = "https://bnn.appteka.store"
const val BANANALYTICS_API_KEY: String = "bnn_y2zEuodSTELhSoKeh_4ldiLFuJilThQ9"

/**
 * ATV : numéro de version annoncé au serveur Appteka (User-Agent, en-têtes API, statut).
 * Jusqu'au 20/11 l'appli annonçait 1195, le numéro d'Appteka d'origine, et les
 * installations marchaient sur la MIBOX4. Depuis, la CI met 2000 + n° du run (21xx) pour
 * l'OTA ModProd, et le serveur traite ces versions « futures » autrement (réponse sans
 * taille puis fichier différent). Le serveur continue donc de voir 1195 ; le vrai
 * numéro ne sert qu'aux mises à jour ModProd.
 */
const val STORE_CLIENT_VERSION_CODE: Int = 1195
