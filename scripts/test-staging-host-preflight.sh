#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
APP="$TMP/host/staging"
mkdir -p "$APP/release-tools" "$TMP/bin"
printf 'POSTGRES_PASSWORD=Staging-Only-Secret-123456\nJWT_SECRET=staging-jwt-secret-at-least-32-bytes-long\n' > "$APP/.env"
chmod 600 "$APP/.env"
printf 'services: {}\n' > "$APP/docker-compose.staging.yml"
printf '#!/usr/bin/env bash\nexit 0\n' > "$APP/release-tools/staging-deployment-smoke.sh"
cp "$ROOT/scripts/staging-backup-restore-rehearsal.sh" "$APP/release-tools/"
chmod 700 "$APP/release-tools/staging-deployment-smoke.sh" "$APP/release-tools/staging-backup-restore-rehearsal.sh"
printf 'mock-custom-format-backup' > "$TMP/backup.dump"
export MOCK_DOCKER_LOG="$TMP/docker.log"

cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
if [[ "$1" == "info" ]]; then exit 0; fi
if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
if [[ "$1" == "buildx" && "$2" == "version" ]]; then exit 0; fi
if [[ "$1" == "buildx" && "$2" == "imagetools" && "$3" == "inspect" ]]; then exit 0; fi
if [[ "$1" == "compose" ]]; then exit 0; fi
if [[ "$1" == "run" ]]; then
  [[ " $* " == *" --network none "* ]] || { echo "Restore probe must use --network none" >&2; exit 1; }
  exit 0
fi
if [[ "$1" == "cp" || "$1" == "rm" ]]; then exit 0; fi
if [[ "$1" == "exec" ]]; then
  case "$3" in
    pg_isready) exit 0 ;;
    pg_restore) [[ "\${MOCK_RESTORE_FAIL:-0}" != 1 ]] ;;
    psql) printf '3\n' ;;
    *) exit 1 ;;
  esac
fi
echo "Unexpected docker invocation: $*" >&2
exit 1
MOCK
cat > "$TMP/bin/pg_restore" <<'MOCK'
#!/usr/bin/env bash
[[ "$1" == "--list" && -s "$2" ]]
MOCK
chmod +x "$TMP/bin/docker" "$TMP/bin/pg_restore"
export PATH="$TMP/bin:$PATH"

BACKEND="ghcr.io/cscpratapnagar-ai/cricpulse-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
FRONTEND="ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
bash "$ROOT/scripts/staging-host-preflight.sh" "$APP" "$TMP/backup.dump" "$BACKEND" "$FRONTEND"

# The host preflight must run a disposable, network-isolated PostgreSQL restore and clean it up.
grep -q '^run .*--network none' "$MOCK_DOCKER_LOG"
grep -q '^exec .* pg_restore ' "$MOCK_DOCKER_LOG"
grep -q '^rm -fv ' "$MOCK_DOCKER_LOG"

# A backup that fails its actual restore rehearsal must block before any image deployment preflight.
: > "$MOCK_DOCKER_LOG"
export MOCK_RESTORE_FAIL=1
if bash "$ROOT/scripts/staging-host-preflight.sh" "$APP" "$TMP/backup.dump" "$BACKEND" "$FRONTEND"; then
  echo "Expected a failed isolated restore rehearsal to block staging preflight" >&2
  exit 1
fi
unset MOCK_RESTORE_FAIL
grep -q '^rm -fv ' "$MOCK_DOCKER_LOG"
if grep -q 'buildx imagetools inspect' "$MOCK_DOCKER_LOG"; then
  echo "Image access/deployment preflight continued after restore failure" >&2
  exit 1
fi

# Mutable tags or wrong repositories must fail before host deployment.
if bash "$ROOT/scripts/staging-host-preflight.sh" "$APP" "$TMP/backup.dump" ghcr.io/cscpratapnagar-ai/cricpulse-backend:latest "$FRONTEND"; then
  echo "Expected mutable backend reference to be rejected" >&2
  exit 1
fi
# Broad permissions on the secret file must fail closed.
chmod 644 "$APP/.env"
if bash "$ROOT/scripts/staging-host-preflight.sh" "$APP" "$TMP/backup.dump" "$BACKEND" "$FRONTEND"; then
  echo "Expected unsafe .env permissions to be rejected" >&2
  exit 1
fi
chmod 600 "$APP/.env"
# A stale or missing backup must fail closed.
touch -d '2 days ago' "$TMP/backup.dump"
if bash "$ROOT/scripts/staging-host-preflight.sh" "$APP" "$TMP/backup.dump" "$BACKEND" "$FRONTEND"; then
  echo "Expected stale backup to be rejected" >&2
  exit 1
fi
echo "PASS: staging host preflight executes isolated backup restore and fails closed on restore, image, secret, and backup errors"
