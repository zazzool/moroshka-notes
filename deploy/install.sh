#!/usr/bin/env bash
# Run from an extracted release on a dedicated Ubuntu VPS as root.
set -euo pipefail
umask 077
domain=${1:?usage: install.sh notes.example.org release-id}
revision=${2:?missing release id}
is_ip=false
if [[ "$domain" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    IFS=. read -r a b c d <<< "$domain"
    for part in "$a" "$b" "$c" "$d"; do
        [[ "$part" == 0 || "$part" != 0* ]]
        (( 10#$part <= 255 ))
    done
    is_ip=true
else
    [[ "$domain" =~ ^([[:alnum:]]([[:alnum:]-]*[[:alnum:]])?\.)+[[:alpha:]]{2,63}$ ]]
fi
[[ "$revision" =~ ^[0-9a-f]{7,40}$ ]]
# Keep each address in its own immutable release directory so a failed
# address change can safely roll back to the prior configuration.
revision=$(printf '%s\0%s' "$revision" "$domain" | sha256sum | cut -c1-40)
[[ "$(id -u)" == 0 ]]
cd "$(dirname "$0")/.."
mkdir -p /opt/moroshka/releases /etc/moroshka
exec 9>/opt/moroshka/deploy.lock
flock -x 9
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1 ||
   ! command -v curl >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1; then
    command -v apt-get >/dev/null && command -v systemctl >/dev/null || {
        echo 'Требуются Docker с Compose v2, curl и openssl, либо Debian/Ubuntu с systemd.' >&2
        exit 1
    }
    . /etc/os-release
    case "$ID:$VERSION_ID" in
        ubuntu:22.04|ubuntu:24.04|debian:12|debian:13) ;;
        *) echo 'Установите Docker с Compose v2, curl и openssl для этой системы.' >&2; exit 1 ;;
    esac
    apt-get update -q
    packages=(curl openssl ca-certificates)
    if ! command -v docker >/dev/null 2>&1; then
        packages+=(docker.io)
        install_docker=true
    else
        install_docker=false
    fi
    if ! docker compose version >/dev/null 2>&1; then
        case "$ID:$VERSION_ID" in
            ubuntu:*) packages+=(docker-compose-v2) ;;
            debian:13) packages+=(docker-compose) ;;
        esac
    fi
    DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"
    if "$install_docker"; then systemctl enable --now docker; fi
    if ! docker compose version >/dev/null 2>&1 && [[ "$ID:$VERSION_ID" == debian:12 ]]; then
        case "$(uname -m)" in
            x86_64) compose_arch=x86_64; compose_sha=dba9d98e1ba5bfe11d88c99b9bd32fc4a0624a30fafe68eea34d61a3e42fd372 ;;
            aarch64) compose_arch=aarch64; compose_sha=d26373b19e89160546d15407516cc59f453030d9bc5b43ba7faf16f7b4980137 ;;
            *) echo 'Архитектура процессора не поддерживается автоматической установкой Compose.' >&2; exit 1 ;;
        esac
        install -d -m 755 /usr/local/lib/docker/cli-plugins
        plugin=$(mktemp /usr/local/lib/docker/cli-plugins/.compose.XXXXXX)
        curl -fsSL --retry 2 "https://github.com/docker/compose/releases/download/v2.40.3/docker-compose-linux-$compose_arch" -o "$plugin"
        printf '%s  %s\n' "$compose_sha" "$plugin" | sha256sum -c -
        chmod 755 "$plugin"
        mv "$plugin" /usr/local/lib/docker/cli-plugins/docker-compose
    fi
    docker compose version >/dev/null
fi
if ! docker info >/dev/null 2>&1; then
    if command -v systemctl >/dev/null 2>&1; then systemctl start docker; fi
    docker info >/dev/null 2>&1 || { echo 'Docker не запущен или недоступен.' >&2; exit 1; }
fi
if [[ ! -e /etc/moroshka/keys.env ]]; then
    tmp=$(mktemp /etc/moroshka/keys.XXXXXX)
    printf 'MOROSHKA_TOKEN=%s\n' "$(openssl rand -hex 32)" > "$tmp"
    mv "$tmp" /etc/moroshka/keys.env
fi
release=/opt/moroshka/releases/$revision
mkdir -p "$release"
cp deploy/Caddyfile deploy/compose.yaml "$release/"
if "$is_ip"; then
    cat > "$release/Caddyfile" <<'CADDY'
{
    default_sni {$DOMAIN}
}
{$DOMAIN} {
    tls {
        issuer acme {
            dir https://acme-v02.api.letsencrypt.org/directory
            profile shortlived
        }
    }
    encode zstd gzip
    reverse_proxy app:8090
}
CADDY
fi
printf 'DOMAIN=%s\nAPP_IMAGE=moroshka:%s\n' "$domain" "$revision" > "$release/.env"
if ! docker image inspect "moroshka:$revision" >/dev/null 2>&1; then
    if docker buildx version >/dev/null 2>&1; then
        docker build -t "moroshka:$revision" .
    else
        DOCKER_BUILDKIT=0 docker build -t "moroshka:$revision" .
    fi
fi
previous=$(readlink -f /opt/moroshka/current || true)
activate() {
    docker compose --env-file "$1/.env" -f "$1/compose.yaml" up -d --wait --wait-timeout 100
}
if activate "$release" && curl --noproxy "$domain" --connect-to "$domain:443:127.0.0.1:443" -fsS --retry 60 --retry-all-errors --retry-delay 3 --connect-timeout 4 --max-time 8 "https://$domain/healthz" >/dev/null; then
    ln -sfn "$release" /opt/moroshka/current.next
    mv -Tf /opt/moroshka/current.next /opt/moroshka/current
    printf 'Moroshka Notes: https://%s\n' "$domain"
else
    if [[ -f "$previous/.env" && "$previous" != "$release" ]]; then activate "$previous"; fi
    exit 1
fi
