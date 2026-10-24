# Compiler JellyfinServer — Android (Termux) et Windows

Deux scripts qui installent **tout** ce qui manque (JDK 17, Android SDK,
Python, `aapt2`) puis construisent l'APK `JellyfinServer-1.0.0.apk`.

| Script | Où | Ce qu'il installe |
| --- | --- | --- |
| [`build-termux.sh`](build-termux.sh) | Android, dans Termux | `openjdk-17`, `python`, `aapt2`, Android SDK (cmdline-tools, platform 34, build-tools 34) dans `$HOME/android-sdk` |
| [`build-windows.bat`](build-windows.bat) | Windows 10/11 | JDK 17 (Temurin), Android SDK, Python 3.12 dans `how-to-build-android\.tools` |

Les deux scripts :

1. installent les prérequis manquants (ils ne retéléchargent rien au 2ᵉ passage) ;
2. écrivent `local.properties` avec le chemin du SDK ;
3. lancent `gradlew assembleRelease`, qui déclenche au premier build
   `tools/build-assets.py` (téléchargement de ~400 Mo : image Jellyfin arm64,
   ffmpeg, proot depuis les dépôts officiels) ;
4. copient l'APK dans `dist/JellyfinServer-1.0.0.apk`.

> L'APK fait ~200 Mo, n'est pas versionnée (voir `.gitignore`) et est signée
> avec la clé de debug : elle s'installe en sideload, pas via le Play Store.

---

## 1. Sur Android, avec Termux

Termux doit venir de **F-Droid** (la version du Play Store est obsolète et
n'installe plus les paquets nécessaires).

```bash
pkg update
pkg install git          # si le projet n'est pas déjà sur le téléphone
git clone https://github.com/ClmentCch/JellyfinAndroid-Server.git
cd JellyfinAndroid-Server
bash how-to-build-android/build-termux.sh
```

Le script installe les paquets, télécharge le SDK Android (**~500 Mo**),
demande l'acceptation des licences, puis compile. Compte **3 Go libres** et
**10 à 30 minutes** sur un téléphone récent ; branche le chargeur, le script
active un wake lock Termux.

Points spécifiques à Termux :

* **`aapt2`** : le binaire livré par Google est un exécutable Linux x86-64,
  inutilisable sur un téléphone arm64. Le script installe le paquet Termux
  `aapt2` (build natif) et passe
  `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2` à Gradle.
* **Mémoire** : la compilation tourne avec `-Xmx2048m`. Sur un appareil à
  4 Go de RAM, utilise plutôt
  `./gradlew ... -Dorg.gradle.jvmargs="-Xmx1536m"` (modifie la valeur dans le
  script) et ferme les autres applications.
* **Espace** : le bundle serveur (~194 Mo), son cache de téléchargement
  (~400 Mo) et l'APK (~200 Mo) vivent dans Termux. Supprime
  `.cache-assets/` après un build réussi pour récupérer ~400 Mo.
* L'APK est copié dans `dist/` et, si `termux-setup-storage` a été lancé, dans
  `~/storage/downloads/JellyfinServer-1.0.0.apk` : ouvre ce fichier pour
  installer.

Options : `bash how-to-build-android/build-termux.sh assembleDebug` pour une
version debug, ou `clean assembleRelease`, etc.

## 2. Sur Windows

1. Récupère le projet (ZIP GitHub ou `git clone`).
2. Double-clique **`how-to-build-android\build-windows.bat`**.

Le script :

* utilise le Java 17+ déjà installé s'il y en a un, sinon télécharge
  **Temurin JDK 17** (~190 Mo) dans `how-to-build-android\.tools\jdk` ;
* télécharge les **command line tools** Android (~150 Mo), accepte les
  licences et installe `platform-tools`, `platforms;android-34`,
  `build-tools;34.0.0` ;
* installe **Python 3.12** en mode utilisateur si `python` n'est pas déjà
  présent (~26 Mo) — il sert uniquement à générer le bundle serveur ;
* compile avec `gradlew.bat` et copie
  `dist\JellyfinServer-1.0.0.apk`.

Prérequis : Windows 10 1803+ (pour `curl.exe`) et une connexion Internet.
Tout est installé dans `how-to-build-android\.tools` : supprimer ce dossier
remet la machine dans son état d'origine. Le premier build télécharge ~400 Mo
d'assets serveur et prend 5 à 15 minutes.

## 3. En cas de problème

| Symptôme | Solution |
| --- | --- |
| `aapt2` : `cannot execute binary file` | Le paquet Termux `aapt2` a été écrasé par la version Google : `pkg install --reinstall aapt2`, puis relance le script. |
| Termux : `pkg install` échoue | `pkg update && pkg upgrade`, puis relance. |
| Windows : `curl.exe introuvable` | Windows trop ancien, ou `C:\Windows\System32` absent du `PATH`. |
| `OutOfMemoryError` pendant le build | Baisse la mémoire Gradle (1536m sous Termux) et ferme les applications. |
| L'APK ne s'installe pas | `targetSdk 28` / appareil **arm64** obligatoires, et autorise l'installation de sources inconnues. |
| Le bundle serveur est corrompu | `python tools/build-assets.py --force` (Termux/Windows), puis relance le build. |

## 4. Ce qui est produit

```
app/build/outputs/apk/release/app-release.apk   APK intermédiaire
dist/JellyfinServer-1.0.0.apk                   APK finale (~200 Mo)
```

Le contenu de l'APK est décrit dans le [README principal](../README.md) :
rootfs Debian arm64, Jellyfin 12.3, `jellyfin-ffmpeg`, `proot`, et
l'application Java qui installe et pilote le serveur.
