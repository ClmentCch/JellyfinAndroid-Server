# JellyfinServer — le serveur Jellyfin dans une application Android

Cette application Android (APK) fait tourner **le serveur Jellyfin officiel**
directement sur un téléphone ou une tablette Android arm64, sans root, sans PC
et sans Termux. L'application s'appelle **JellyfinServer** et embarque :

| Élément | Version / origine |
| --- | --- |
| Serveur Jellyfin | 12.3.0 (build `linux-arm64` officiel, image `jellyfin/jellyfin`) |
| Transcodage | `jellyfin-ffmpeg` 8.1.3 + `ffprobe` |
| Système invité | Debian 13 (trixie) arm64, allégé (487 Mo extraits) |
| Exécution sans root | `proot` 5.1.107 (Termux) + `libtalloc` + `libandroid-shmem` |
| Application | Java, `minSdk 26`, `targetSdk 28` |

> **Pourquoi pas le serveur Windows ?** Jellyfin Server est une application
> .NET/ASP.NET Core : le binaire Windows ne peut pas s'exécuter sur Android.
> L'application embarque donc le serveur Jellyfin **arm64 pour Linux**, exécuté
> dans un mini-système Debian via `proot` (comme le fait Termux), et le rend
> accessible dans une WebView et sur le réseau local. C'est le même serveur,
> avec la même interface et la même base de données, pas une réécriture.

---

## 1. Fonctionnement

```
┌────────────────────────── APK JellyfinServer ──────────────────────────┐
│                                                                        │
│  MainActivity (WebView + état + journal)                               │
│        │                                                               │
│  ServerService (service de premier plan, notification, wake lock)      │
│        │                                                               │
│  ServerManager ──► proot -r rootfs -0 -b /config -b /cache -b /media   │
│                       │                                                │
│                       └──► /jellyfin/jellyfin (Kestrel 0.0.0.0:8096)   │
│                                                                        │
│  assets/rootfs.bin   (Debian + Jellyfin + ffmpeg, 194 Mo)              │
│  assets/proot/*      (proot, loader, libtalloc, libandroid-shmem)      │
└────────────────────────────────────────────────────────────────────────┘
```

* Au premier lancement, l'application extrait `rootfs.bin` dans son stockage
  privé (`files/server/rootfs`) après vérification SHA-256. L'extraction dure
  quelques minutes et ne se fait qu'une fois (~490 Mo occupés).
* Le serveur est lancé par `proot` avec des points de montage :

  | Hôte (Android) | Invité (Debian) | Rôle |
  | --- | --- | --- |
  | `files/server/data/config` | `/config` | base de données, comptes, réglages |
  | `files/server/data/cache` | `/cache` | cache, transcodage |
  | `/storage/emulated/0` | `/media` | films, séries, musique |

* L'application attend que `http://127.0.0.1:8096/web/index.html` réponde, puis
  affiche l'interface d'administration Jellyfin. L'adresse est aussi accessible
  depuis les autres appareils du réseau local (`http://<ip-du-téléphone>:8096`).
* Un service de premier plan et un wake lock gardent le serveur actif écran
  éteint ; la notification propose « Arrêter » et « Ouvrir ».

## 2. Installation sur le téléphone

1. Récupérer l'APK : `dist/JellyfinServer-1.0.0.apk` (ou
   `app/build/outputs/apk/release/app-release.apk` après un build).
2. Autoriser l'installation depuis une source inconnue, puis installer.
3. Ouvrir l'application, toucher **Installer le serveur** et attendre la fin de
   l'extraction (laissez l'application au premier plan).
4. Toucher **Démarrer**, puis créer le compte administrateur dans l'assistant
   Jellyfin.
5. Dans l'assistant, ajouter une bibliothèque en choisissant le dossier
   `/media` (qui correspond au stockage partagé du téléphone).

Prérequis : Android 8.0 ou plus récent, **arm64** (téléphone ou tablette
64 bits), ~1,2 Go d'espace libre. Les appareils x86 et armv7 ne sont pas
compatibles avec les binaires embarqués.

Conseils pour un usage « serveur » :

* Autoriser JellyfinServer à ignorer l'optimisation de la batterie (proposé au
  premier démarrage) et laisser le téléphone branché.
* Accorder l'accès au stockage, sinon `/media` n'est pas monté.
* Pour regarder les vidéos sur un autre appareil, utiliser l'application
  cliente Jellyfin avec l'adresse affichée dans l'écran principal.

## 3. Compiler l'APK

Deux scripts installent automatiquement les prérequis et compilent :

| Plateforme | Commande |
| --- | --- |
| **Android / Termux** | `bash how-to-build-android/build-termux.sh` |
| **Windows** | `how-to-build-android\build-windows.bat` (double-clic) |

Ils téléchargent le JDK 17, le SDK Android, Python et `aapt2` si nécessaire,
puis produisent `dist/JellyfinServer-1.0.0.apk`. Détails et dépannage :
[`how-to-build-android/README.md`](how-to-build-android/README.md).

Pour compiler à la main, prérequis : JDK 17, Android SDK (plateforme 34,
build-tools 34), Python 3 et un accès réseau.

```bash
# 1. SDK Android : pointer local.properties vers votre installation
echo "sdk.dir=$HOME/android-sdk" > local.properties

# 2. Build de l'APK (le bundle serveur est généré automatiquement au premier build)
export JAVA_HOME=/chemin/vers/jdk17
./gradlew assembleRelease

# APK : app/build/outputs/apk/release/app-release.apk (~195 Mo)
```

Le bundle serveur (194 Mo) n'est pas versionné : la tâche Gradle
`serverAssets` appelle `tools/build-assets.py` s'il manque. Le script :

1. télécharge l'image officielle `jellyfin/jellyfin:latest` (linux/arm64) et
   extrait son système de fichiers ;
2. retire ce qui est inutile sur Android (polices de bureau, pilotes GPU AMD,
   documentation, apt) ;
3. empaquette le tout dans `app/src/main/assets/rootfs.bin` + empreinte
   SHA-256 ;
4. récupère `proot`, son loader et ses bibliothèques depuis les paquets Termux.

Pour reconstruire le bundle manuellement :

```bash
python3 tools/build-assets.py --force
```

## 4. Structure du projet

```
app/src/main/java/com/jellyfinserver/app/
  MainActivity.java     écran principal (installation, état, WebView, journal)
  ServerService.java    service de premier plan + notification
  ServerManager.java    cycle de vie du serveur (proot, logs, arrêt propre)
  RuntimeInstaller.java extraction du rootfs et des binaires proot
  TarExtractor.java     extraction tar.gz en streaming (liens, permissions)
  AppConfig.java        chemins, port, URLs
  Prefs.java            réglages persistants
  NetUtils.java         adresses IP locales
  BootReceiver.java     démarrage automatique après redémarrage
tools/build-assets.py   construction reproductible du bundle serveur
how-to-build-android/   scripts de compilation Android/Termux et Windows
```

Le bundle généré se trouve dans `app/src/main/assets/` (ignoré par git).

## 5. Limites connues

* **Installation hors Play Store** : `targetSdk 28` est nécessaire car Android
  10+ interdit l'exécution de binaires depuis le stockage de l'application
  pour les applications ciblant l'API 29+, et le stockage « legacy » est requis
  pour `/media`. C'est le compromis utilisé par Termux.
* **arm64 uniquement** : le rootfs et proot sont des binaires aarch64.
* **Pas de transcodage matériel** : ffmpeg fonctionne en logiciel. Un téléphone
  récent transcode correctement un flux 1080p, mais pas plusieurs à la fois.
* **Un seul serveur, port 8096** : le port est fixe, l'application ne gère
  qu'une instance.
* **Écran éteint** : Android peut tuer l'application sur les ROM très
  agressives (Xiaomi, Huawei…) ; autoriser l'application en arrière-plan et
  désactiver l'optimisation de la batterie.
* L'application n'est pas signée avec une clé de production : le build
  `release` utilise la clé de debug pour rester installable en sideload.

## 6. Vérifications effectuées

* Le rootfs embarqué a été démarré sous émulation arm64 (qemu) : Jellyfin 12.3
  écoute sur `0.0.0.0:8096`, `/web/index.html` répond `200`, `ffmpeg` et
  `ffprobe` fonctionnent, aucune erreur au démarrage.
* Le même rootfs a été extrait avec le code de l'application
  (`TarExtractor`) puis redémarré : liens symboliques, permissions et binaires
  exécutables conservés.
* L'APK a été compilé et contrôlé : `package com.jellyfinserver.app`,
  `minSdk 26`, `targetSdk 28`, assets `rootfs.bin` (SHA-256 identique) et
  binaires proot présents, signature valide.

Non testé ici (pas d'appareil Android à disposition) : lancement réel de
`proot` sur un téléphone, extraction de 490 Mo sur stockage interne, WebView et
cycle de vie du service. En cas de problème, le bouton **Journal** de
l'application affiche la sortie complète de proot/Jellyfin.

## 7. Licences

Jellyfin Server et Jellyfin Web sont distribués sous **GPL-2.0** ; ffmpeg sous
LGPL/GPL selon les composants ; proot sous GPL-2.0 ; Debian et ses paquets sous
leurs licences respectives. L'application embarque ces composants **non
modifiés**, extraits des dépôts officiels (image `jellyfin/jellyfin`, dépôt
Termux, archives Debian). Les sources sont disponibles sur
<https://github.com/jellyfin/jellyfin>, <https://github.com/termux/proot> et
<https://www.debian.org/>. Toute redistribution de l'APK doit respecter ces
licences (fourniture des sources correspondantes).
