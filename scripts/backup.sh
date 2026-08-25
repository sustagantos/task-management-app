#!/usr/bin/env bash
#
# Nightly logical backup of the task database.
#
# Runs on CT 217 via tasks-backup.timer. Produces a pg_dump custom-format
# archive, which pg_restore can restore selectively and which survives a
# Postgres major-version upgrade - unlike a copy of the data directory.
#
# Every dump is verified by reading its table of contents back before it is
# kept. A file that exists is not a backup; a file pg_restore can parse is.

set -euo pipefail

COMPOSE_DIR="${COMPOSE_DIR:-/opt/tasks}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/tasks}"
RETAIN_DAYS="${RETAIN_DAYS:-14}"
DB_NAME="${DB_NAME:-tasks}"
DB_USER="${DB_USER:-tasks}"

log() { echo "[tasks-backup] $*"; }
die() { echo "[tasks-backup] ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not found"
[ -d "$COMPOSE_DIR" ] || die "compose directory $COMPOSE_DIR does not exist"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/tasks-$STAMP.dump"
TMP="$OUT.partial"

cd "$COMPOSE_DIR"

log "dumping $DB_NAME"
if ! docker compose exec -T db pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$TMP"; then
    rm -f "$TMP"
    die "pg_dump failed"
fi

# Verify before trusting. A truncated or empty dump exits 0 from the pipeline
# above often enough to matter, and it is only discovered at restore time.
log "verifying archive"
if ! docker compose exec -T db pg_restore --list < "$TMP" > /dev/null 2>&1; then
    rm -f "$TMP"
    die "dump did not verify - refusing to keep it"
fi

SIZE="$(stat -c %s "$TMP")"
[ "$SIZE" -gt 1024 ] || { rm -f "$TMP"; die "dump is only $SIZE bytes - refusing to keep it"; }

mv "$TMP" "$OUT"
chmod 600 "$OUT"
log "wrote $OUT ($SIZE bytes)"

# Retention. -mtime is whole days, so RETAIN_DAYS=14 keeps roughly two weeks.
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -name 'tasks-*.dump' -mtime "+$RETAIN_DAYS" -print -delete | wc -l)"
[ "$DELETED" -gt 0 ] && log "pruned $DELETED archive(s) older than $RETAIN_DAYS days"

# Clean up any partial file left by a previous crash.
find "$BACKUP_DIR" -maxdepth 1 -name 'tasks-*.dump.partial' -mtime +1 -delete

log "done"
