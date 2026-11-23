#!/usr/bin/env bash
# Send only tracked application sources. SSH verifies the pinned server identity.
set -euo pipefail
target=${1:?usage: publish.sh user@host domain}
domain=${2:?missing domain}
[[ "$target" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_-]*@[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]
[[ "$domain" =~ ^([[:alnum:]]([[:alnum:]-]*[[:alnum:]])?\.)+[[:alpha:]]{2,63}$ ]]
cd "$(git rev-parse --show-toplevel)"
revision=$(git rev-parse HEAD)
remote=$(cat <<'REMOTE'
set -eu
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
tar -xf - -C "$dir"
cd "$dir"
if [ "$(id -u)" = 0 ]; then runner=""; else runner="sudo -n"; fi
REMOTE
)
remote="$remote
\$runner bash deploy/install.sh '$domain' '$revision'"
git archive HEAD Dockerfile .dockerignore server web deploy |
    ssh -o StrictHostKeyChecking=yes -o ConnectTimeout=15 "$target" "$remote"

