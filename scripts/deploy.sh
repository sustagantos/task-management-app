#!/usr/bin/env bash
#
# Pull-based deploy. Runs on CT 217 under tasks-deploy.timer.
#
# The container reaches out to GHCR; nothing reaches in. That is the whole point
# of the design: no inbound path, no SSH key for the hypervisor sitting in a CI
# secret, no change to the access baseline. The cost is polling latency.
#
# Safe to run when nothing has changed - docker compose up -d only recreates a
# container whose image actually moved, so the common case is a no-op.

set -euo pipefail

COMPOSE_DIR="${COMPOSE_DIR:-/opt/tasks}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/actuator/health/readiness}"
HEALTH_TRIES="${HEALTH_TRIES:-45}"

log() { echo "[tasks-deploy] $*"; }
die() { echo "[tasks-deploy] ERROR: $*" >&2; exit 1; }

cd "$COMPOSE_DIR" || die "no $COMPOSE_DIR"

running_image() {
    local cid
    cid="$(docker compose ps -q app 2>/dev/null || true)"
    [ -n "$cid" ] || { echo "none"; return; }
    docker inspect -f '{{.Image}}' "$cid" 2>/dev/null || echo "none"
}

BEFORE="$(running_image)"

# compose.yaml and these scripts live in the repo, so they track the image.
git pull --ff-only --quiet || log "git pull failed, continuing with the checkout as-is"

docker compose pull --quiet
docker compose up -d --remove-orphans

AFTER="$(running_image)"

if [ "$BEFORE" = "$AFTER" ]; then
    # Nothing changed. Stay silent so the journal is a record of deploys rather
    # than a record of the timer having fired.
    exit 0
fi

log "image changed: ${BEFORE:0:19} -> ${AFTER:0:19}"

# A deploy that starts a broken container and says nothing is worse than no
# deploy at all. Flyway plus JVM start takes a while, hence the generous budget.
log "waiting for readiness"
for _ in $(seq 1 "$HEALTH_TRIES"); do
    if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then
        log "healthy"
        docker image prune -f >/dev/null 2>&1 || true
        log "done"
        exit 0
    fi
    sleep 2
done

log "container did not become ready within $((HEALTH_TRIES * 2))s - recent logs follow"
docker compose logs --tail 40 app || true
die "deploy unhealthy"
