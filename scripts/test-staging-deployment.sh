#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
APP="$TMP/host/staging"; TOOLS="$APP/release-tools"
mkdir -p "$TOOLS" "$TMP/bin"
cp "$ROOT/docker-compose.staging.yml" "$APP/docker-compose.staging.yml"
cat > "$TOOLS/staging-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
exit 0
SMOKE
printf "mock-postgres-backup-archive" > "$TMP/verified-backup.dump"
printf 'POSTGRES_PASSWORD=Staging-Only-Secret-123456\nJWT_SECRET=staging-jwt-secret-at-least-32-bytes-long\nBACKEND_TAG=sha-1111111\nFRONTEND_TAG=sha-2222222\n' > "$APP/.env"
export MOCK_DOCKER_LOG="$TMP/docker.log"
cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
[[ "$*" == "compose version" ]] && exit 0
exit 0
MOCK
printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/pg_restore"
chmod +x "$TMP/bin/docker" "$TMP/bin/pg_restore"; export PATH="$TMP/bin:$PATH"
bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" sha-3333333 sha-4444444 "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""
grep -Fxq 'BACKEND_TAG=sha-3333333' "$APP/.last-known-good-staging"
grep -Fxq 'FRONTEND_TAG=sha-4444444' "$APP/.last-known-good-staging"
printf 'POSTGRES_PASSWORD=Staging-Only-Secret-123456\nJWT_SECRET=staging-jwt-secret-at-least-32-bytes-long\nBACKEND_TAG=sha-1111111\nFRONTEND_TAG=sha-2222222\n' > "$APP/.env"
cat > "$TOOLS/staging-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
set -Eeuo pipefail
COUNT_FILE="$(dirname "$0")/smoke-count"; COUNT=0
[[ ! -f "$COUNT_FILE" ]] || COUNT="$(cat "$COUNT_FILE")"
COUNT=$((COUNT + 1)); printf '%s' "$COUNT" > "$COUNT_FILE"
[[ "$COUNT" -ne 1 ]] || exit 9
exit 0
SMOKE
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" sha-5555555 sha-6666666 "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected failed staging smoke to report rollback" >&2; exit 1
fi
grep -Fxq 'BACKEND_TAG=sha-1111111' "$APP/.env"
grep -Fxq 'FRONTEND_TAG=sha-2222222' "$APP/.env"
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$TMP/host/production" sha-7777777 sha-8888888 "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected non-staging path to be rejected" >&2; exit 1
fi
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" sha-7777777 sha-8888888 "$TMP/missing.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected missing backup to be rejected" >&2; exit 1
fi
echo "PASS: staging success path, automatic rollback, and backup/path guards"
