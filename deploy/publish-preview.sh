#!/usr/bin/env bash
# Never publish a same-package APK with an unrelated ephemeral debug key.
set -euo pipefail
umask 077
candidate=$1
: "${PREVIEW_KEYSTORE_BASE64:?Missing original preview signing keystore}"
: "${PREVIEW_KEY_ALIAS:?Missing key alias}"
: "${PREVIEW_STORE_PASSWORD:?Missing store password}"
: "${PREVIEW_KEY_PASSWORD:?Missing key password}"
tools="${ANDROID_HOME:?}/build-tools/35.0.0"
signer="$tools/apksigner"
aapt="$tools/aapt"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
field(){ sed -n "s/.*$1='\([^']*\)'.*/\1/p" <<< "$2"; }
package(){ "$aapt" dump badging "$1" | sed -n '/^package:/p'; }
certs(){ "$signer" verify --print-certs "$1" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' | sort; }
info=$(package "$candidate")
version=$(field versionName "$info")
code=$(field versionCode "$info")
[[ "$(field name "$info")" == 'app.moroshka.notes.preview' ]]
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+-preview$ && "$code" =~ ^[0-9]+$ ]]
previous=$(gh api --paginate "repos/$GH_REPO/releases?per_page=100" --jq '.[] | select(.draft|not) | .tag_name' |
  grep -E '^v[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+-preview$' | sort -V | tail -n1)
[[ -n "$previous" && "$previous" != "v$version" ]]
old="Moroshka-Notes-${previous#v}.apk"
gh release download "$previous" --pattern "$old" --dir "$tmp"
old_info=$(package "$tmp/$old")
[[ "$(field name "$old_info")" == 'app.moroshka.notes.preview' ]]
old_code=$(field versionCode "$old_info")
[[ "$old_code" =~ ^[0-9]+$ && "$code" -gt "$old_code" ]]
printf '%s' "$PREVIEW_KEYSTORE_BASE64" | base64 -d > "$tmp/preview.jks"
mkdir -p release
output="release/Moroshka-Notes-$version.apk"
"$signer" sign --ks "$tmp/preview.jks" --ks-key-alias "$PREVIEW_KEY_ALIAS" \
  --ks-pass env:PREVIEW_STORE_PASSWORD --key-pass env:PREVIEW_KEY_PASSWORD --out "$output" "$candidate"
old_cert=$(certs "$tmp/$old")
new_cert=$(certs "$output")
[[ -n "$old_cert" && "$old_cert" == "$new_cert" ]] || {
  echo 'Preview signing certificate mismatch; publication blocked to protect installed libraries.' >&2
  exit 1
}
"$signer" verify --verbose "$output"
(cd release && sha256sum "Moroshka-Notes-$version.apk" Moroshka-Server.pyz > SHA256SUMS)
gh release create "v$version" "$output" release/Moroshka-Server.pyz release/SHA256SUMS \
  --target "$GITHUB_SHA" --title "Moroshka Notes $version" --prerelease --latest=false --generate-notes
