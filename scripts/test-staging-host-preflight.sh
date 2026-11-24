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
chmod 700 "$APP/release-tools/staging-deployment-smoke.sh"
printf 'mock-custom-format-backup' > "$TMP/backup.dump"

cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
case "$*" in
  "info"|"compose version"|"buildx version"|"compose --env-file "* " config --quiet") exit 0 ;;
esac
if [[ "$*" == "buildx imagetools inspect "* ]]; then exit 0; fi
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
echo "PASS: staging host bootstrap prerequisites and fail-closed guards"
