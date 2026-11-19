<p align="center">
  <img src="art/appteka-tv-banner.webp" alt="Appteka TV" width="100%">
</p>

<p align="center">
  <strong>Appteka pour Android TV, pilotable depuis l'Appteka du téléphone</strong>
</p>

<p align="center">
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/Licence-GPL%20v3-blue.svg" alt="Licence : GPL v3"></a>
  <img src="https://img.shields.io/badge/API-23%2B-brightgreen.svg" alt="API 23+">
  <img src="https://img.shields.io/badge/Android%20TV-Leanback-orange.svg" alt="Android TV">
</p>

---

**Appteka TV** est une version modifiée d'[Appteka](https://github.com/solkin/appteka-android), le magasin d'applications Android libre. Un seul APK, deux comportements :

- **sur la TV**, une interface HTML (WebView) pensée pour la télécommande, utilisable seule ;
- **sur le téléphone**, l'Appteka d'origine (écrans natifs, **sans HTML**), qui peut basculer sur la TV : la même interface native, mais comme si on était sur la TV (catalogue TV, applis de la TV, installation sur la TV).

Le catalogue, le téléchargement et l'installation restent ceux d'Appteka.

Projet ModProd — branche de travail : `tv-html`.

> Ce dépôt n'est pas le dépôt officiel d'Appteka. Pour l'application d'origine, voir [solkin/appteka-android](https://github.com/solkin/appteka-android).

## Ce qui change par rapport à Appteka

| | Appteka | Appteka TV |
|---|---|---|
| Accueil sur TV | — | Interface HTML pour la télécommande |
| Accueil sur téléphone | Interface native Material | La même, avec un sélecteur **📱 Ce téléphone / 📺 TV** (en cours) |
| Lanceur Android TV | — | Oui (`LEANBACK_LAUNCHER`) |
| Navigation | Tactile | Télécommande (D-pad) et tactile |
| Contenu par défaut | Tout le catalogue | Catégories **TV** (37) et **Divertissement** (27), modifiables |
| Téléphone → TV | — | Le téléphone pilote la TV par le Wi-Fi : installer, lancer, désinstaller sur la TV |
| Identifiant | `com.tomclaw.appsend` | `app.modprod.apptekatv` (installable à côté de l'original) |

Sur la TV, les écrans natifs d'origine (publication, discussions, avis, profils…) restent accessibles depuis l'interface HTML par des « connecteurs ».

## Direction du projet

Décision du fondateur du 19/11/2026 (mission `MISSION-ATV-001`, 8 tickets ATV-01 à ATV-08) : **un duo téléphone + TV**.

### Sur le téléphone : l'Appteka d'origine, qui peut se mettre « à la place » de la TV

Le téléphone garde **l'interface d'origine d'Appteka** (écrans natifs). Un sélecteur de cible **📱 Ce téléphone / 📺 \<nom de la TV\>** change ce que cette interface montre et fait :

| | Cible 📱 Ce téléphone | Cible 📺 TV |
|---|---|---|
| Catalogue | Tout Appteka | Ce qui convient à la TV : catégories TV et Divertissement, processeur (ABI) de la TV |
| Installer | Sur le téléphone | **Sur la TV** : la TV télécharge et installe elle-même, avec suivi sur le téléphone |
| Mes applis | Applis du téléphone | **Applis installées sur la TV** : lancer, mettre à jour, désinstaller |
| Compte | Compte du téléphone | Le même compte, partagé avec la TV |

**Où se trouve le sélecteur :** une icône dans la barre du haut de l'écran Store, **juste après le titre « Store »**. Elle montre la cible active : **📱 smartphone** ou **📺 TV**. **Au premier appui**, s'il n'y a encore aucune TV associée, le téléphone crée la connexion : il propose de **scanner le QR code affiché par la TV** ou de **taper le code affiché sur la TV**. Ensuite, un appui bascule entre téléphone et TV.

Autrement dit : en mode TV, on navigue dans l'Appteka habituelle **comme si on était sur la TV**, avec les écrans natifs d'origine. **Aucune interface HTML sur le téléphone**, dans aucun mode : le HTML est réservé à la TV. Pas non plus de télécommande générique.

### Sur la TV : une Appteka TV minimale, utilisable seule

Interface HTML à la télécommande : applis installées, rangées de catégories, recherche, téléchargements en cours, fiche d'appli, réglages (QR code d'association, appareils associés, langue, compte). Elle exécute aussi les ordres du téléphone (la confirmation Android d'installation ou de désinstallation s'affiche sur la TV).

### Étapes

| Ticket | Contenu | État |
|---|---|---|
| ATV-01 | Association par QR code **ou code affiché sur la TV**, et liaison chiffrée (remplace l'ancien code à 4 chiffres) | écrit, compilé — à tester ([PR #1](https://github.com/MOD-PROD/ApptekaTv/pull/1)) |
| ATV-02 | Téléphone : retour à l'accueil d'origine, sélecteur de cible, scan du QR | à faire |
| ATV-03 | « Installer sur la TV » depuis les écrans d'origine | à faire |
| ATV-04 | « Mes applis » de la TV sur le téléphone : liste, lancement, mise à jour, désinstallation | à faire |
| ATV-05 | Catalogue adapté à la TV en mode TV | à faire |
| ATV-06 | Compte partagé téléphone → TV (uniquement par la liaison chiffrée) | à faire |
| ATV-07 | Interface TV réduite à l'essentiel (liste des suppressions validée par le fondateur avant) | à faire |
| ATV-08 | Documentation et numéro de version | à faire |

Règles : une branche `atv/<n°>-<sujet>` par ticket et une PR brouillon vers `tv-html` ; build **Build TV APK** vert comme preuve ; aucune fusion ni publication sans accord du fondateur ; modifications minimales du code d'origine (nouveau code dans des paquets dédiés, points d'accroche commentés `// ATV:`) pour pouvoir toujours fusionner les mises à jour d'Appteka.

### État actuel (avant ATV-02)

En attendant ATV-02, l'APK ouvre encore **l'interface HTML aussi sur le téléphone** (vue adaptée au tactile), avec un bouton « Envoyer à la TV » dans les fiches. Cette vue téléphone est provisoire : elle disparaîtra entièrement au profit de l'interface d'origine, et le téléphone n'affichera plus aucun écran HTML.

## Fonctions (interface TV)

- **Rangées de sources** : catégories, top, applis d'un auteur ; ordre modifiable (appui long sur une icône).
- **Fiche d'appli** : description, versions, résumé IA d'Appteka, compatibilité processeur (ABI) de l'appareil.
- **Téléchargement et installation** avec suivi et annulation ; lancement et désinstallation des applis installées.
- **Recherche** limitée aux catégories choisies.
- **Favoris** (touche jaune de la télécommande).
- **Compte Appteka** : connexion par code reçu par e-mail.
- **Apparence** : thèmes *Noir et or*, *Néumorphisme vert* et *Néumorphisme clair*, couleur d'accent, taille des cartes, marges.
- **Langues** : français, anglais, espagnol, allemand, italien, portugais, russe, turc et arabe, ou langue de l'appareil.
- **Sauvegarde des réglages** par export de la configuration.

## Liaison téléphone → TV (Wi-Fi)

Le téléphone trouve la TV sur le réseau local sans adresse IP, grâce à DNS-SD (`_apptekatv._tcp`, port 8336 par défaut).

1. Sur le téléphone, « Envoyer à la TV » depuis une fiche d'appli.
2. La première fois, la TV affiche un **code à 4 chiffres** à saisir sur le téléphone (appairage).
3. Ensuite, la TV ouvre la fiche ou lance directement l'installation.

Protocole actuel (v1) : une connexion TCP par requête, une ligne JSON en question et une en réponse (`hello`, `pair_request`, `pair_confirm`, `open`, `install`). Les ordres `open` et `install` exigent le jeton obtenu à l'appairage. Détails dans `app/src/main/java/com/tomclaw/appsend/tvlink/`.

Protocole v2 (ATV-01, [PR #1](https://github.com/MOD-PROD/ApptekaTv/pull/1), pas encore fusionné) : association par **QR code** (adresse, secret à usage unique de 5 minutes, empreinte du certificat de la TV), **liaison chiffrée**, jeton par téléphone et révocation depuis la TV. Il remplacera la v1.

## Organisation du code

| Emplacement | Rôle |
|---|---|
| `app/src/main/assets/tv/` | Interface HTML : `index.html`, `app.js` (accueil, rangées, réglages), `details.js` (fiche), `nav.js` (navigation D-pad), `i18n.js` (traductions), feuilles CSS par thème, `mobile.css` |
| `app/src/main/java/com/tomclaw/appsend/screen/tv/TvWebActivity.kt` | Activité WebView et pont JavaScript `window.Android` (catalogue, installation, compte, préférences, liaison TV) |
| `app/src/main/java/com/tomclaw/appsend/screen/tv/TvDetailsMapper.kt` | Conversion des fiches Appteka pour l'interface HTML |
| `app/src/main/java/com/tomclaw/appsend/tvlink/` | Liaison Wi-Fi téléphone ↔ TV |
| `screen/home/HomeActivity.kt` | Aujourd'hui : redirige vers l'interface HTML sur tous les appareils. Après ATV-02 : interface HTML sur TV seulement, accueil d'origine sur téléphone |

Pour ajouter une fonction côté HTML : déclarer la méthode `@JavascriptInterface` dans `TvWebActivity`, l'appeler depuis le JavaScript avec `window.Android.<méthode>()`, puis renvoyer le résultat au JavaScript par un rappel.

## Compilation

### Avec GitHub Actions (méthode habituelle)

Le workflow **Build TV APK** (`.github/workflows/build-tv-apk.yml`) se lance à chaque push sur `tv-html`, ou à la main (*Run workflow*). Il produit :

- `appteka-tv-debug` : APK de débogage (conservé 14 jours) ;
- `appteka-tv-release` : APK de production compacté (R8) et signé avec la clé ModProd (conservé 30 jours), seulement si les secrets de signature sont présents.

Secrets du dépôt pour la version de production :

| Secret | Contenu |
|---|---|
| `ANDROID_KEYSTORE_B64` | Fichier de clés (.p12 ou .keystore) encodé en base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Mot de passe du fichier |
| `ANDROID_KEY_ALIAS` | Alias de la clé |
| `ANDROID_KEY_PASSWORD` | Facultatif : seulement si la clé a un mot de passe différent du fichier |

Les APK se téléchargent dans l'onglet **Actions**, sur le run concerné, rubrique *Artifacts*.

### En local

Prérequis : JDK 17, SDK Android avec la plateforme 37.

```bash
git clone -b tv-html https://github.com/MOD-PROD/ApptekaTv.git
cd ApptekaTv
./gradlew assembleDebug          # APK dans app/build/outputs/apk/debug/
```

Version de production signée :

```bash
./gradlew assembleRelease -PstoreFile=<fichier> -PstorePassword=<mdp> -PkeyAlias=<alias> -PkeyPassword=<mdp>
```

Ne jamais ajouter de fichier de clés au dépôt : `*.keystore` et `*.jks` sont ignorés par `.gitignore`.

## Installation sur une TV

1. Récupérer l'APK (Actions → Artifacts) et le dézipper.
2. Le copier sur la TV (clé USB, gestionnaire de fichiers, `adb install`) ou l'envoyer depuis Appteka sur le téléphone.
3. Autoriser les « sources inconnues » pour l'application qui installe.
4. L'appli apparaît dans le lanceur Android TV sous le nom Appteka.

## Sécurité

Comme Appteka, le catalogue est alimenté par les utilisateurs. Les applis publiées sont analysées par l'antivirus d'Appteka, mais vérifiez la source, les avis et les notes avant d'installer. Conditions d'Appteka : [appteka.store/legal](https://appteka.store/legal).

## Licence et origine

Appteka TV est dérivé d'**Appteka** © 2016-2026 l’équipe Appteka, publié sous licence **GNU GPL v3**. Ce dépôt est distribué sous la même licence ; les modifications (interface HTML TV, lanceur TV, liaison téléphone ↔ TV) sont signalées dans l'historique Git de la branche `tv-html`.

Voir [LICENSE.txt](LICENSE.txt) pour le texte complet.
