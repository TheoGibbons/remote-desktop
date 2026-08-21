#!/usr/bin/env bash
# Local, standalone. Publishes loopback ports; no shared proxy, no DNS.
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ ! -f .env ]]; then
  echo "Missing $repo_dir/.env. Copy .env.example to .env first." >&2
  exit 1
fi

compose=(
  docker compose
  --env-file .env
  -f docker-compose.yml
  -f docker-compose.override.yml
)

"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --remove-orphans --wait
"${compose[@]}" ps
