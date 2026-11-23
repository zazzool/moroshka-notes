#!/usr/bin/env bash
# Reproducible Linux x86_64 build, including on a fresh Codex cloud computer.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cache=${MOROSHKA_TOOL_DIR:-$root/.cloud-tools}
mkdir -p "$cache"
cache=$(cd "$cache" && pwd)
export JAVA_HOME="$cache/jdk" ANDROID_HOME="$cache/sdk"
export ANDROID_USER_HOME="$cache/android-user" GRADLE_USER_HOME="$cache/gradle"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
temp=$(mktemp -d "$cache/setup.XXXXXX")
trap 'rm -rf "$temp"' EXIT
download() {
    curl -fsSL --retry 2 "$1" -o "$temp/archive"
    printf '%s  %s\n' "$2" "$temp/archive" | sha256sum -c -
}

# Provision one cache at a time even if two builds start together.
exec 9>"$cache/setup.lock"
flock 9
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    download 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21.1%2B2/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21.1_2.tar.gz' \
        0b0ac5eab6db6abaec68c9913bcddb31321e5da411c45073d520d9c125e6f7b4
    mkdir "$temp/jdk"
    tar -xzf "$temp/archive" --strip-components=1 -C "$temp/jdk"
    mv "$temp/jdk" "$JAVA_HOME"
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    download 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' \
        2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
    unzip -q "$temp/archive" -d "$temp"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    mv "$temp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
export PATH="$JAVA_HOME/bin:$PATH"
# Java tools use JVM proxy properties. Only copy the host and port.
proxy_options=$(python3 - <<'PY'
from urllib.parse import urlsplit
import os
p = urlsplit(os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or "")
if p.hostname:
    for scheme in ("https", "http"):
        print(f"-D{scheme}.proxyHost={p.hostname} -D{scheme}.proxyPort={p.port or 80}", end=" ")
PY
)
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $proxy_options -Duser.home=$ANDROID_USER_HOME"
if [[ -r /etc/ssl/certs/java/cacerts ]]; then
    JAVA_TOOL_OPTIONS+=" -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
manager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -s "$ANDROID_HOME/platforms/android-35/android.jar" || ! -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" ]]; then
    "$manager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0'
fi
flock -u 9
cd "$root/android"
./gradlew --max-workers=2 :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
mkdir -p "$root/release"
cp app/build/outputs/apk/debug/app-debug.apk "$root/release/Moroshka-Notes-debug.apk"
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
"$signer" verify "$root/release/Moroshka-Notes-debug.apk"
if [[ -n "${MOROSHKA_KEYSTORE:-}" ]]; then
    : "${ANDROID_KEY_ALIAS:?}" "${ANDROID_STORE_PASSWORD:?}" "${ANDROID_KEY_PASSWORD:?}"
    "$signer" sign --ks "$MOROSHKA_KEYSTORE" --ks-key-alias "$ANDROID_KEY_ALIAS" \
        --ks-pass env:ANDROID_STORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
        --out "$root/release/Moroshka-Notes-release.apk" app/build/outputs/apk/release/app-release-unsigned.apk
    "$signer" verify "$root/release/Moroshka-Notes-release.apk"
fi

