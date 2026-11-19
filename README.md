<p align="center">
  <img src="art/appteka-tv-banner.webp" alt="Appteka TV" width="100%">
</p>

<p align="center">
  <strong>Appteka pour Android TV et smartphone, avec une interface HTML</strong>
</p>

<p align="center">
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/Licence-GPL%20v3-blue.svg" alt="Licence : GPL v3"></a>
  <img src="https://img.shields.io/badge/API-23%2B-brightgreen.svg" alt="API 23+">
  <img src="https://img.shields.io/badge/Android%20TV-Leanback-orange.svg" alt="Android TV">
</p>

---

**Appteka TV** est une version modifiée d'[Appteka](https://github.com/solkin/appteka-android), le magasin d'applications Android libre. Elle ajoute une interface HTML (WebView) pensée pour la télécommande et utilisable aussi au doigt sur smartphone. Le catalogue, le téléchargement et l'installation restent ceux d'Appteka.

Projet ModProd — branche de travail : `tv-html`.

> Ce dépôt n'est pas le dépôt officiel d'Appteka. Pour l'application d'origine, voir [solkin/appteka-android](https://github.com/solkin/appteka-android).

## Ce qui change par rapport à Appteka

| | Appteka | Appteka TV |
|---|---|---|
| Accueil | Interface native Material | Interface HTML (TV et smartphone) |
| Lanceur Android TV | — | Oui (`LEANBACK_LAUNCHER`) |
| Navigation | Tactile | Télécommande (D-pad) et tactile |
| Contenu par défaut | Tout le catalogue | Catégories **TV** (37) et **Divertissement** (27), modifiables |
| Portable → TV | — | Envoi d'une appli du portable vers la TV par le Wi-Fi |
| Identifiant | `com.tomclaw.appsend` | `app.modprod.apptekatv` (installable à côté de l'original) |

Les écrans natifs d'origine (publication, discussions, avis, profils…) restent accessibles depuis l'interface HTML par des « connecteurs ». Ils seront refaits en HTML au fur et à mesure.

## Fonctions

- **Rangées de sources** : catégories, top, applis d'un auteur ; ordre modifiable (appui long sur une icône).
- **Fiche d'appli** : description, versions, résumé IA d'Appteka, compatibilité processeur (ABI) de l'appareil.
- **Téléchargement et installation** avec suivi et annulation ; lancement et désinstallation des applis installées.
- **Recherche** limitée aux catégories choisies.
- **Favoris** (touche jaune de la télécommande).
- **Compte Appteka** : connexion par code reçu par e-mail.
- **Apparence** : thèmes *Noir et or*, *Néumorphisme vert* et *Néumorphisme clair*, couleur d'accent, taille des cartes, marges.
- **Langues** : français, anglais, espagnol, allemand, italien, portugais, russe, turc et arabe, ou langue de l'appareil.
- **Sauvegarde des réglages** par export de la configuration.

## Liaison portable → TV (Wi-Fi)

Le portable trouve la TV sur le réseau local sans adresse IP, grâce à DNS-SD (`_apptekatv._tcp`, port 8336 par défaut).

1. Sur le portable, « Envoyer à la TV » depuis une fiche d'appli.
2. La première fois, la TV affiche un **code à 4 chiffres** à saisir sur le portable (appairage).
3. Ensuite, la TV ouvre la fiche ou lance directement l'installation.

Protocole : une connexion TCP par requête, une ligne JSON en question et une en réponse (`hello`, `pair_request`, `pair_confirm`, `open`, `install`). Les ordres `open` et `install` exigent le jeton obtenu à l'appairage. Détails dans `app/src/main/java/com/tomclaw/appsend/tvlink/`.

## Organisation du code

| Emplacement | Rôle |
|---|---|
| `app/src/main/assets/tv/` | Interface HTML : `index.html`, `app.js` (accueil, rangées, réglages), `details.js` (fiche), `nav.js` (navigation D-pad), `i18n.js` (traductions), feuilles CSS par thème, `mobile.css` |
| `app/src/main/java/com/tomclaw/appsend/screen/tv/TvWebActivity.kt` | Activité WebView et pont JavaScript `window.Android` (catalogue, installation, compte, préférences, liaison TV) |
| `app/src/main/java/com/tomclaw/appsend/screen/tv/TvDetailsMapper.kt` | Conversion des fiches Appteka pour l'interface HTML |
| `app/src/main/java/com/tomclaw/appsend/tvlink/` | Liaison Wi-Fi portable ↔ TV |
| `screen/home/HomeActivity.kt` | Redirige vers l'interface HTML ; l'accueil classique ne s'ouvre plus que par son connecteur |

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
2. Le copier sur la TV (clé USB, gestionnaire de fichiers, `adb install`) ou l'envoyer depuis Appteka sur le portable.
3. Autoriser les « sources inconnues » pour l'application qui installe.
4. L'appli apparaît dans le lanceur Android TV sous le nom Appteka.

## Sécurité

Comme Appteka, le catalogue est alimenté par les utilisateurs. Les applis publiées sont analysées par l'antivirus d'Appteka, mais vérifiez la source, les avis et les notes avant d'installer. Conditions d'Appteka : [appteka.store/legal](https://appteka.store/legal).

## Licence et origine

Appteka TV est dérivé d'**Appteka** © 2016-2026 l’équipe Appteka, publié sous licence **GNU GPL v3**. Ce dépôt est distribué sous la même licence ; les modifications (interface HTML, lanceur TV, liaison Wi-Fi) sont signalées dans l'historique Git de la branche `tv-html`.

Voir [LICENSE.txt](LICENSE.txt) pour le texte complet.
