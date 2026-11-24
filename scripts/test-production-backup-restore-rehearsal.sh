#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
printf 'mock-custom-format-backup' > "$TMP/backup.dump"

cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
case "$1" in
  info|cp|rm) exit 0 ;;
  run)
    [[ "$*" == *"--network none"* ]] || { echo "restore probe must use an isolated network" >&2; exit 1; }
    [[ "$*" != *" -p "* && "$*" != *" -v "* && "$*" != *"--network host"* ]] || { echo "restore probe must not publish ports or mount volumes" >&2; exit 1; }
    [[ "${MOCK_START_FAIL:-0}" != 1 ]]
    ;;
  exec)
    case "$3" in
      pg_isready) [[ "${MOCK_NOT_READY:-0}" != 1 ]] ;;
      pg_restore) [[ "${MOCK_RESTORE_FAIL:-0}" != 1 ]] ;;
      psql) [[ "${MOCK_EMPTY_OBJECTS:-0}" != 1 ]] && printf '5\n' ;;
      *) echo "Unexpected docker exec: $*" >&2; exit 1 ;;
    esac
    ;;
  *) echo "Unexpected docker invocation: $*" >&2; exit 1 ;;
esac
MOCK
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH" MOCK_DOCKER_LOG="$TMP/docker.log"

echo "[1/5] Successful isolated PostgreSQL restore"
bash "$ROOT/scripts/production-backup-restore-rehearsal.sh" "$TMP/backup.dump"
grep -Fq -- "--network none" "$MOCK_DOCKER_LOG"
grep -Fq "pg_restore --exit-on-error --no-owner --no-privileges" "$MOCK_DOCKER_LOG"
grep -Fq "rm -fv cricpulse-production-restore-probe-" "$MOCK_DOCKER_LOG"

echo "[2/5] Restore failure blocks and cleans up"
: > "$MOCK_DOCKER_LOG"
if MOCK_RESTORE_FAIL=1 bash "$ROOT/scripts/production-backup-restore-rehearsal.sh" "$TMP/backup.dump"; then
  echo "Expected an invalid restore to fail closed" >&2
  exit 1
fi
grep -Fq "rm -fv cricpulse-production-restore-probe-" "$MOCK_DOCKER_LOG"

echo "[3/5] Empty restored schema is rejected"
: > "$MOCK_DOCKER_LOG"
if MOCK_EMPTY_OBJECTS=1 bash "$ROOT/scripts/production-backup-restore-rehearsal.sh" "$TMP/backup.dump"; then
  echo "Expected an empty restored database to be rejected" >&2
  exit 1
fi
grep -Fq "rm -fv cricpulse-production-restore-probe-" "$MOCK_DOCKER_LOG"

echo "[4/5] Container startup failure is rejected"
: > "$MOCK_DOCKER_LOG"
if MOCK_START_FAIL=1 bash "$ROOT/scripts/production-backup-restore-rehearsal.sh" "$TMP/backup.dump"; then
  echo "Expected a failed PostgreSQL container startup to be rejected" >&2
  exit 1
fi
grep -Fq "rm -fv cricpulse-production-restore-probe-" "$MOCK_DOCKER_LOG"

echo "[5/5] Missing backup is rejected before Docker is called"
: > "$MOCK_DOCKER_LOG"
if bash "$ROOT/scripts/production-backup-restore-rehearsal.sh" "$TMP/missing.dump"; then
  echo "Expected a missing backup to be rejected" >&2
  exit 1
fi
[[ ! -s "$MOCK_DOCKER_LOG" ]]

echo "PASS: production isolated backup restore, failure cleanup, empty-restore, startup, and missing-backup guards"
