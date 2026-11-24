#!/usr/bin/env bash
# An alpha release must update the previous debug application without replacing its identity.
set -euo pipefail
umask 077
: "${ALPHA_KEYSTORE_BASE64:?Set the original alpha signing keystore in the android-release environment}"
: "${ALPHA_KEY_ALIAS:?Set the original key alias}"
: "${ALPHA_STORE_PASSWORD:?Set the original store password}"
: "${ALPHA_KEY_PASSWORD:?Set the original key password}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
tools="$ANDROID_HOME/build-tools/35.0.0"
signer="$tools/apksigner"
aapt="$tools/aapt"
previous="$tmp/previous.apk"
curl -fLsS --retry 2 'https://raw.githubusercontent.com/zazzool/moroshka-notes/024176901f7699e3b170a04ec9b35b061254fac4/v0.1.0-alpha.7/Moroshka-Notes-0.1.0-alpha.7.apk' -o "$previous"
printf '%s  %s\n' '3d2bf2f05380e95fcc975c60fddcc3752914d0a4d6dd4a4c2e471951d39f5e64' "$previous" | sha256sum -c -
printf '%s' "$ALPHA_KEYSTORE_BASE64" | base64 -d > "$tmp/alpha.jks"
candidate=candidate/app-debug.apk
old_info=$("$aapt" dump badging "$previous" | sed -n '/^package:/p')
new_info=$("$aapt" dump badging "$candidate" | sed -n '/^package:/p')
field() { sed -n "s/.*$1='\([^']*\)'.*/\1/p" <<< "$2"; }
[[ "$(field name "$old_info")" == "app.moroshka.notes.debug" ]]
[[ "$(field name "$new_info")" == "$(field name "$old_info")" ]]
old_code=$(field versionCode "$old_info")
new_code=$(field versionCode "$new_info")
version=$(field versionName "$new_info")
[[ "$old_code" =~ ^[0-9]+$ && "$new_code" =~ ^[0-9]+$ && "$new_code" -gt "$old_code" ]]
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+$ ]]
mkdir -p release
output="release/Moroshka-Notes-$version.apk"
"$signer" sign --ks "$tmp/alpha.jks" --ks-key-alias "$ALPHA_KEY_ALIAS" \
  --ks-pass env:ALPHA_STORE_PASSWORD --key-pass env:ALPHA_KEY_PASSWORD --out "$output" "$candidate"
certs() { "$signer" verify --print-certs "$1" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' | sort; }
old_cert=$(certs "$previous")
new_cert=$(certs "$output")
[[ -n "$old_cert" && "$old_cert" == "$new_cert" ]] || { echo 'Signing certificate does not match alpha 7. Publication blocked.' >&2; exit 1; }
"$signer" verify --verbose "$output"
printf '%s\n' "$version" > release/VERSION
(cd release && sha256sum "Moroshka-Notes-$version.apk" Moroshka-Server.pyz > SHA256SUMS)
