#!/usr/bin/env bash
# LIVE deployment. Reads .env, which USE_TRAEFIK in that file switches between
# the standalone path and the shared hobby-traefik path.
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ ! -f .env ]]; then
  echo "Missing $repo_dir/.env. Copy .env.production.example to .env and fill it in." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Tracked files have local changes; refusing to overwrite the EC2 checkout." >&2
  git status --short
  exit 1
fi

git pull --ff-only

use_traefik="$(sed -n 's/^[[:space:]]*USE_TRAEFIK[[:space:]]*=[[:space:]]*\([^[:space:]#]*\).*/\1/p' .env | tail -n 1)"
use_traefik="${use_traefik//\"/}"
use_traefik="${use_traefik//\'/}"

compose=(docker compose --env-file .env -f docker-compose.yml)

case "$use_traefik" in
  1)
    if ! docker network inspect traefik-public >/dev/null 2>&1; then
      echo "USE_TRAEFIK=1 but the traefik-public network does not exist. Deploy hobby-traefik first." >&2
      exit 1
    fi
    compose+=(-f docker-compose.traefik.yml -f docker-compose.prod.yml)
    ;;
  0)
    compose+=(-f docker-compose.override.yml)
    ;;
  *)
    echo "Set USE_TRAEFIK to 0 (standalone) or 1 (behind hobby-traefik) in $repo_dir/.env." >&2
    exit 1
    ;;
esac

"${compose[@]}" config --quiet
"${compose[@]}" build --pull
"${compose[@]}" up -d --remove-orphans --wait
"${compose[@]}" ps
