#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { echo "STAGING BACKUP RESTORE REHEARSAL FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 1 ]] || fail "Expected one absolute PostgreSQL custom-format backup path"
BACKUP_FILE="$1"
[[ "$BACKUP_FILE" == /* && -f "$BACKUP_FILE" && ! -L "$BACKUP_FILE" && -r "$BACKUP_FILE" && -s "$BACKUP_FILE" ]] || fail "Backup must be a readable, non-empty regular file"
command -v docker >/dev/null 2>&1 || fail "Docker is required"
command -v od >/dev/null 2>&1 || fail "od is required to create an isolated probe credential"
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable"

CONTAINER="cricpulse-staging-restore-probe-$$"
PROBE_PASSWORD="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
[[ "$PROBE_PASSWORD" =~ ^[0-9a-f]{64}$ ]] || fail "Could not generate temporary restore-probe credential"
cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting disposable PostgreSQL 16 restore-probe container"
docker run -d --name "$CONTAINER" --label cricpulse.staging.restore-probe=true \
  -e POSTGRES_PASSWORD="$PROBE_PASSWORD" -e POSTGRES_DB=restore_probe postgres:16-alpine >/dev/null || fail "Could not start isolated PostgreSQL restore-probe container"

ready=false
for attempt in {1..60}; do
  if docker exec "$CONTAINER" pg_isready -U postgres -d restore_probe >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
[[ "$ready" == true ]] || fail "Disposable PostgreSQL did not become ready within 120 seconds"

docker cp "$BACKUP_FILE" "$CONTAINER:/tmp/staging-backup.dump" >/dev/null || fail "Could not copy backup into isolated restore-probe container"
docker exec "$CONTAINER" pg_restore --exit-on-error --no-owner --no-privileges \
  -U postgres -d restore_probe /tmp/staging-backup.dump >/dev/null || fail "PostgreSQL rejected the backup during a real isolated restore"

OBJECT_COUNT="$(docker exec "$CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d restore_probe -Atqc \
  "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND c.relkind IN ('r','p','v','m','S')")" || fail "Could not inspect restored database objects"
[[ "$OBJECT_COUNT" =~ ^[0-9]+$ ]] && (( OBJECT_COUNT > 0 )) || fail "Restore completed but no user tables, views, materialized views, or sequences were found"

echo "PASS: staging backup restored in disposable PostgreSQL 16; verified $OBJECT_COUNT user database objects"
