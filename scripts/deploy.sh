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

use_traefik="$(sed -n 's/^[[:space:]]*USE_TRAEFIK[[:space:]]*=[[:space:]]*\([^[:space:]#]*\).*/\1/p' .env | tail -n 1)"
use_traefik="${use_traefik//\"/}"
use_traefik="${use_traefik//\'/}"

# The Traefik path's prerequisites are checked before pulling, so a new server
# fails here, naming the fix, while nothing has changed.
case "$use_traefik" in
  1)
    if ! docker network inspect traefik-public >/dev/null 2>&1; then
      echo "USE_TRAEFIK=1 but the traefik-public network does not exist. Deploy hobby-traefik first." >&2
      exit 1
    fi
    # Releases are swapped with the docker-rollout CLI plugin, which belongs to
    # the host rather than to this repository. Match the output: when the plugin
    # is missing, Docker answers `docker rollout --version` with its own version
    # and exits 0.
    if [[ "$(docker rollout --version 2>/dev/null || true)" != "docker-rollout version "* ]]; then
      echo "The docker-rollout plugin is not installed for $(id -un) on this host." >&2
      echo "Deploying hobby-traefik installs it, or run:" >&2
      echo "  bash ~/projects/hobby-traefik/scripts/install-docker-rollout.sh" >&2
      exit 1
    fi
    ;;
  0)
    ;;
  *)
    echo "Set USE_TRAEFIK to 0 (standalone) or 1 (behind hobby-traefik) in $repo_dir/.env." >&2
    exit 1
    ;;
esac

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Tracked files have local changes; refusing to overwrite the EC2 checkout." >&2
  git status --short
  exit 1
fi

git pull --ff-only

# BuildKit's default provenance attestation gives every build a new image ID,
# even from unchanged sources, so Compose would replace the relay and site on
# every deploy and end every device's relay connection. Without it, an unchanged
# service builds to the same image and its container is left running.
export BUILDX_NO_DEFAULT_ATTESTATIONS=1

# Standalone publishes host ports, which two containers of one service cannot
# share, so its releases are swapped with a plain `up` and briefly go down.
if [[ "$use_traefik" == 0 ]]; then
  compose=(docker compose --env-file .env -f docker-compose.yml -f docker-compose.override.yml)
  "${compose[@]}" config --quiet
  "${compose[@]}" build --pull
  "${compose[@]}" up -d --remove-orphans --wait
  "${compose[@]}" ps
  exit 0
fi

compose_args=(--env-file .env -f docker-compose.yml -f docker-compose.traefik.yml -f docker-compose.prod.yml)
compose=(docker compose "${compose_args[@]}")

# Every service must be in exactly one list; see "Decide which services to roll"
# in hobby-traefik's playbook-zero-downtime-deploys.md.
#   before_rollout  what the rolled services need running first (databases)
#   rolled          Traefik-routed services, swapped without downtime
#   after_rollout   everything else, updated with a plain `up`
before_rollout=()
rolled=(relay site)
after_rollout=()

"${compose[@]}" config --quiet

# A service missing from the lists would be started once and never updated, so a
# service added to Compose later has to be classified before the next deploy.
listed=" ${before_rollout[*]} ${rolled[*]} ${after_rollout[*]} "
for service in $("${compose[@]}" config --services); do
  if [[ "$listed" != *" $service "* ]]; then
    echo "Service '$service' is not in before_rollout, rolled or after_rollout in scripts/deploy.sh." >&2
    exit 1
  fi
done

"${compose[@]}" build --pull

if (( ${#before_rollout[@]} )); then
  "${compose[@]}" up -d --wait --wait-timeout 90 "${before_rollout[@]}"
fi

# `up` would stop the running release before starting the new one, and the site
# would be down until the new one passed its health check. docker rollout starts
# the new container beside the old, waits for it to turn healthy, then removes
# the old. A release that never turns healthy is removed instead, the old one
# keeps serving, and the deploy fails.
#
# The pre-stop hook drains the outgoing container first: /tmp/drain fails its
# health check, Traefik stops routing to it, and in-flight requests finish. 20
# seconds covers three failed probes 5 seconds apart plus Traefik noticing.
#
# Unless the routing labels changed. Traefik refuses a service that two
# containers describe differently and serves 404 while both exist, so draining
# would stretch that into a 20-second outage. Stop the old one immediately
# instead and accept a sub-second blip.
#
# A service that did not change is not rolled at all. A rollout always replaces
# the container, and replacing the relay ends every device's connection, while
# most pushes change only the apps. Compose's own test decides: the running
# containers carry the current config hash and were created from the image just
# built.
routing_labels() { { grep -o '"traefik\.[^"]*": *"[^"]*"' || true; } | sed 's/": *"/":"/' | sort; }
up_to_date() {
  local service="$1" hash image id
  hash="$("${compose[@]}" config --hash "$service" | awk '{print $2}')"
  image="$(docker image inspect --format '{{.Id}}' "$("${compose[@]}" config --images "$service")")"
  for id in $2; do
    [[ "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.config-hash"}} {{.Image}}' "$id")" == "$hash $image" ]] || return 1
  done
}
for service in "${rolled[@]}"; do
  rollout=(docker rollout "${compose_args[@]}" --timeout 90)
  running_ids="$("${compose[@]}" ps --quiet "$service")"
  if [[ -n "$running_ids" ]] && up_to_date "$service" "$running_ids"; then
    echo "$service is unchanged; leaving its running container in place."
    continue
  fi
  if [[ -n "$running_ids" ]]; then
    live_labels="$(docker inspect --format '{{json .Config.Labels}}' "${running_ids%%$'\n'*}" | routing_labels)"
    new_labels="$("${compose[@]}" config --format json "$service" | routing_labels)"
    if [[ "$live_labels" == "$new_labels" ]]; then
      rollout+=(--pre-stop-hook 'touch /tmp/drain && sleep 20')
    else
      echo "Routing labels of $service changed; replacing its old container without draining it."
    fi
  fi
  "${rollout[@]}" "$service"
done

# --no-deps, so a worker that depends_on a rolled service cannot recreate it.
if (( ${#after_rollout[@]} )); then
  "${compose[@]}" up -d --no-deps --wait --wait-timeout 90 "${after_rollout[@]}"
fi

# Removes containers of services no longer in the Compose files and confirms
# everything is healthy. --no-recreate stops it replacing what was just rolled.
"${compose[@]}" up -d --no-recreate --remove-orphans --wait --wait-timeout 90
"${compose[@]}" ps
