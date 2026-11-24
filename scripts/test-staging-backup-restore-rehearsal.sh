#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
printf 'mock-custom-format-backup' > "$TMP/backup.dump"
cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
case "$1" in
  info|run|cp|rm) exit 0 ;;
  exec)
    case "$3" in
      pg_isready) exit 0 ;;
      pg_restore) [[ "${MOCK_RESTORE_FAIL:-0}" != 1 ]] ;;
      psql) printf '4\n' ;;
      *) echo "Unexpected docker exec: $*" >&2; exit 1 ;;
    esac
    ;;
  *) echo "Unexpected docker invocation: $*" >&2; exit 1 ;;
esac
MOCK
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH" MOCK_DOCKER_LOG="$TMP/docker.log"
bash "$ROOT/scripts/staging-backup-restore-rehearsal.sh" "$TMP/backup.dump"
grep -Fq 'pg_restore --exit-on-error --no-owner --no-privileges' "$MOCK_DOCKER_LOG"
grep -Fq 'rm -fv cricpulse-staging-restore-probe-' "$MOCK_DOCKER_LOG"

: > "$MOCK_DOCKER_LOG"
if MOCK_RESTORE_FAIL=1 bash "$ROOT/scripts/staging-backup-restore-rehearsal.sh" "$TMP/backup.dump"; then
  echo "Expected an invalid restore to fail closed" >&2
  exit 1
fi
grep -Fq 'rm -fv cricpulse-staging-restore-probe-' "$MOCK_DOCKER_LOG"

if bash "$ROOT/scripts/staging-backup-restore-rehearsal.sh" "$TMP/missing.dump"; then
  echo "Expected a missing backup to be rejected" >&2
  exit 1
fi
echo "PASS: isolated backup restore success, restore-failure cleanup, and missing-backup guards"
