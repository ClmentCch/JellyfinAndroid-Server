#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
#  JellyfinServer - compilation de l'APK directement sur Android (Termux)
#
#  Installe tout ce qui manque (JDK 17, Android SDK, Python, aapt2 arm64),
#  construit le bundle serveur puis l'APK, et copie l'APK dans dist/.
#
#  Utilisation, depuis le dossier du projet :
#      bash how-to-build-android/build-termux.sh
#      bash how-to-build-android/build-termux.sh assembleDebug
#
#  Tout est installe dans $HOME/android-sdk et $HOME/.gradle :
#  rien n'est ecrit en dehors du dossier personnel Termux.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
PLATFORM="34"
BUILD_TOOLS="34.0.0"
APK_NAME="JellyfinServer-1.0.0.apk"

step() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[x] %s\033[0m\n' "$*" >&2; exit 1; }

if [ ! -d /data/data/com.termux ]; then
    die "Ce script doit etre lance dans Termux, sur le telephone."
fi
[ -f "$ROOT/settings.gradle" ] || die "Projet introuvable dans $ROOT"
[ $# -eq 0 ] && set -- assembleRelease

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
trap 'command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true' EXIT

# --- 0. Verification de l'espace disque ------------------------------------
available_kb="$(df -Pk "$HOME" | awk 'NR==2 {print $4}')"
available_gb=$(( ${available_kb:-0} / 1024 / 1024 ))
if [ "$available_gb" -lt 3 ]; then
    warn "Seulement ${available_gb} Go libres dans Termux : il en faut ~3 Go"
    warn "(bundle serveur 400 Mo + rootfs 490 Mo + APK 200 Mo)."
    printf 'Continuer quand meme ? [o/N] '
    read -r answer
    [ "$answer" = "o" ] || exit 1
fi

# --- 1. Paquets Termux ------------------------------------------------------
step "1/4 Paquets Termux (openjdk-17, python, curl, unzip, aapt2)"
pkg install -y openjdk-17 python curl unzip aapt2 >/dev/null || {
    warn "pkg install a echoue, nouvelle tentative apres pkg update"
    pkg update -y >/dev/null
    pkg install -y openjdk-17 python curl unzip aapt2 >/dev/null
}

if [ -d "$PREFIX/lib/jvm/java-17-openjdk" ]; then
    export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
fi
PYTHON_BIN="$(command -v python3 || command -v python || true)"
[ -n "$PYTHON_BIN" ] || die "Python est introuvable apres l'installation"
export PYTHON="$PYTHON_BIN"
AAPT2="$(command -v aapt2 || true)"
[ -n "$AAPT2" ] || die "aapt2 est introuvable : le paquet Termux aapt2 est necessaire"

# --- 2. Android SDK ---------------------------------------------------------
step "2/4 Android SDK (platforms;android-$PLATFORM, build-tools;$BUILD_TOOLS)"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    tools_tmp="$(mktemp -d)"
    echo "Telechargement des command line tools (~150 Mo)..."
    curl -fL --retry 3 --retry-delay 2 -o "$tools_tmp/tools.zip" "$CMDLINE_URL"
    unzip -q "$tools_tmp/tools.zip" -d "$tools_tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tools_tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tools_tmp"
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
chmod +x "$SDKMANAGER" || true

echo "Acceptation des licences Android..."
set +o pipefail
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
set -o pipefail

"$SDKMANAGER" --sdk_root="$SDK" --install \
    "platform-tools" "platforms;android-$PLATFORM" "build-tools;$BUILD_TOOLS"

printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"

# --- 3. Verification de l'environnement ------------------------------------
step "3/4 Verification"
"$PYTHON_BIN" -c 'import sys; print("Python", sys.version.split()[0])'
java -version 2>&1 | head -1
echo "aapt2 : $AAPT2"
echo "SDK   : $SDK"

# Le bundle serveur (rootfs Debian + Jellyfin, ~194 Mo) n'est pas versionne :
# Gradle le construit avec tools/build-assets.py au premier build.
if [ ! -f "$ROOT/app/src/main/assets/rootfs.bin" ]; then
    warn "Le bundle serveur va etre telecharge (~400 Mo, 5 a 15 min)."
fi

# --- 4. Compilation ---------------------------------------------------------
step "4/4 Compilation : ${*}"
cd "$ROOT"
./gradlew --no-daemon \
    -Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m" \
    "-Pandroid.aapt2FromMavenOverride=$AAPT2" \
    "$@"

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "L'APK n'a pas ete produite"

mkdir -p "$ROOT/dist"
cp -f "$APK" "$ROOT/dist/$APK_NAME"
size="$(du -h "$ROOT/dist/$APK_NAME" | cut -f1)"

step "Termine : dist/$APK_NAME ($size)"
if [ -d "$HOME/storage/downloads" ]; then
    cp -f "$ROOT/dist/$APK_NAME" "$HOME/storage/downloads/$APK_NAME"
    echo "Copie dans le dossier Telechargements du telephone :"
    echo "  $HOME/storage/downloads/$APK_NAME"
    echo "Ouvre ce fichier pour installer JellyfinServer."
else
    echo "Pour installer l'APK :"
    echo "  termux-setup-storage && cp dist/$APK_NAME ~/storage/downloads/"
    echo "puis ouvre le fichier depuis l'application Fichiers."
fi
