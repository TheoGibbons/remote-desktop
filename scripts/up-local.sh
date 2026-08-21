#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ ! -f .env.traefik ]]; then
  echo "Missing .env.traefik. Copy .env.traefik.example and adjust it first." >&2
  exit 1
fi

if ! docker network inspect traefik-public >/dev/null 2>&1; then
  echo "The traefik-public network does not exist. Start hobby-traefik first." >&2
  exit 1
fi

compose=(
  docker compose
  --env-file .env.traefik
  -f docker-compose.yml
  -f docker-compose.traefik.yml
)

"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --remove-orphans
"${compose[@]}" ps
