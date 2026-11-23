#!/usr/bin/env bash
# Run from an extracted release on a dedicated Ubuntu VPS as root.
set -euo pipefail
umask 077
domain=${1:?usage: install.sh notes.example.org release-id}
revision=${2:?missing release id}
[[ "$domain" =~ ^([[:alnum:]]([[:alnum:]-]*[[:alnum:]])?\.)+[[:alpha:]]{2,63}$ ]]
[[ "$revision" =~ ^[0-9a-f]{7,40}$ ]]
[[ "$(id -u)" == 0 ]]
cd "$(dirname "$0")/.."
mkdir -p /opt/moroshka/releases /etc/moroshka
exec 9>/opt/moroshka/deploy.lock
flock -x 9
if ! docker compose version >/dev/null 2>&1; then
    . /etc/os-release
    [[ "$ID" == ubuntu && "$VERSION_ID" == 24.04 ]] || { echo 'Install Docker with Compose v2 or use Ubuntu 24.04.' >&2; exit 1; }
    apt-get update -q
    DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2 curl openssl
    systemctl enable --now docker
fi
if [[ ! -e /etc/moroshka/keys.env ]]; then
    tmp=$(mktemp /etc/moroshka/keys.XXXXXX)
    printf 'MOROSHKA_TOKEN=%s\n' "$(openssl rand -hex 32)" > "$tmp"
    mv "$tmp" /etc/moroshka/keys.env
fi
release=/opt/moroshka/releases/$revision
mkdir -p "$release"
cp deploy/Caddyfile deploy/compose.yaml "$release/"
printf 'DOMAIN=%s\nAPP_IMAGE=moroshka:%s\n' "$domain" "$revision" > "$release/.env"
docker build -t "moroshka:$revision" .
previous=$(readlink -f /opt/moroshka/current || true)
activate() {
    docker compose --env-file "$1/.env" -f "$1/compose.yaml" up -d --wait --wait-timeout 100
}
if activate "$release" && curl -fsS --retry 12 --retry-all-errors --retry-delay 3 --connect-timeout 4 --max-time 8 "https://$domain/healthz" >/dev/null; then
    ln -sfn "$release" /opt/moroshka/current.next
    mv -Tf /opt/moroshka/current.next /opt/moroshka/current
    printf 'Moroshka Notes: https://%s\n' "$domain"
else
    if [[ -f "$previous/.env" && "$previous" != "$release" ]]; then activate "$previous"; fi
    exit 1
fi
