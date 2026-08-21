#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ ! -f .env.production ]]; then
  echo "Missing $repo_dir/.env.production. Copy .env.production.example and set the production hostname." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Tracked files have local changes; refusing to overwrite the EC2 checkout." >&2
  git status --short
  exit 1
fi

git pull --ff-only

if ! docker network inspect traefik-public >/dev/null 2>&1; then
  echo "The traefik-public network does not exist. Deploy hobby-traefik first." >&2
  exit 1
fi

compose=(
  docker compose
  --env-file .env.production
  -f docker-compose.yml
  -f docker-compose.traefik.yml
  -f docker-compose.prod.yml
)

"${compose[@]}" config --quiet
"${compose[@]}" build --pull
"${compose[@]}" up -d --remove-orphans

wait_for_health() {
  local service="$1"
  local container_id status

  container_id="$("${compose[@]}" ps -q "$service")"
  if [[ -z "$container_id" ]]; then
    echo "No container was created for $service." >&2
    return 1
  fi

  for _ in {1..40}; do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
    case "$status" in
      healthy|running)
        echo "$service is $status"
        return 0
        ;;
      unhealthy|exited|dead)
        echo "$service became $status" >&2
        "${compose[@]}" logs --tail=100 "$service" >&2
        return 1
        ;;
    esac
    sleep 3
  done

  echo "Timed out waiting for $service to become healthy." >&2
  "${compose[@]}" logs --tail=100 "$service" >&2
  return 1
}

wait_for_health relay
wait_for_health site
"${compose[@]}" ps
