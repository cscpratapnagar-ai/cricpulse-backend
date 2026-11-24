#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
APP="$TMP/host/staging"
TOOLS="$APP/release-tools"
mkdir -p "$TOOLS" "$TMP/bin"
cp "$ROOT/docker-compose.staging.yml" "$APP/docker-compose.staging.yml"
cat > "$TOOLS/staging-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
exit 0
SMOKE
printf "mock-postgres-backup-archive" > "$TMP/verified-backup.dump"
printf 'POSTGRES_PASSWORD=Staging-Only-Secret-123456\nJWT_SECRET=staging-jwt-secret-at-least-32-bytes-long\nBACKEND_TAG=sha-1111111\nFRONTEND_TAG=sha-2222222\n' > "$APP/.env"

export MOCK_DOCKER_LOG="$TMP/docker.log"
export MOCK_MANIFEST='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1},"layers":[]}'
LEGACY_DIGEST="$(printf '%s' "$MOCK_MANIFEST" | sha256sum | awk '{print $1}')"
BACKEND_NEW_DIGEST="sha256:3333333333333333333333333333333333333333333333333333333333333333"
FRONTEND_NEW_DIGEST="sha256:4444444444444444444444444444444444444444444444444444444444444444"

cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
[[ "$*" == "compose version" || "$*" == "buildx version" ]] && exit 0
if [[ "${1:-}" == "buildx" && "${2:-}" == "imagetools" && "${3:-}" == "inspect" && "${4:-}" == "--raw" ]]; then
  printf '%s' "$MOCK_MANIFEST"
  exit 0
fi
exit 0
MOCK
printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/pg_restore"
chmod +x "$TMP/bin/docker" "$TMP/bin/pg_restore"
export PATH="$TMP/bin:$PATH"

bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" "$BACKEND_NEW_DIGEST" "$FRONTEND_NEW_DIGEST" "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""
grep -Fxq "BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@$BACKEND_NEW_DIGEST" "$APP/.last-known-good-staging"
grep -Fxq "FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@$FRONTEND_NEW_DIGEST" "$APP/.last-known-good-staging"
grep -Fxq "BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@$BACKEND_NEW_DIGEST" "$APP/.env"
grep -Fxq "FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@$FRONTEND_NEW_DIGEST" "$APP/.env"
grep -Fq "pull backend frontend" "$MOCK_DOCKER_LOG"

# Simulate a failed rollout: the previous image tags are migrated to digests and rollback restores those exact digests.
printf 'POSTGRES_PASSWORD=Staging-Only-Secret-123456\nJWT_SECRET=staging-jwt-secret-at-least-32-bytes-long\nBACKEND_TAG=sha-1111111\nFRONTEND_TAG=sha-2222222\n' > "$APP/.env"
cat > "$TOOLS/staging-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
set -Eeuo pipefail
COUNT_FILE="$(dirname "$0")/smoke-count"
COUNT=0
[[ ! -f "$COUNT_FILE" ]] || COUNT="$(cat "$COUNT_FILE")"
COUNT=$((COUNT + 1))
printf '%s' "$COUNT" > "$COUNT_FILE"
[[ "$COUNT" -ne 1 ]] || exit 9
exit 0
SMOKE
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" "$BACKEND_NEW_DIGEST" "$FRONTEND_NEW_DIGEST" "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected failed staging smoke to report rollback" >&2
  exit 1
fi
grep -Fxq "BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@sha256:$LEGACY_DIGEST" "$APP/.env"
grep -Fxq "FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:$LEGACY_DIGEST" "$APP/.env"

# Mutable digests, non-staging paths, and missing backups are rejected.
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" latest "$FRONTEND_NEW_DIGEST" "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected mutable backend reference to be rejected" >&2
  exit 1
fi
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$TMP/host/production" "$BACKEND_NEW_DIGEST" "$FRONTEND_NEW_DIGEST" "$TMP/verified-backup.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected non-staging path to be rejected" >&2
  exit 1
fi
if bash "$ROOT/scripts/staging-deploy-with-rollback.sh" "$APP" "$BACKEND_NEW_DIGEST" "$FRONTEND_NEW_DIGEST" "$TMP/missing.dump" https://api.staging.example.test https://staging.example.test https://api.staging.example.test/actuator/health/readiness ""; then
  echo "Expected missing backup to be rejected" >&2
  exit 1
fi

echo "PASS: digest-pinned staging success, legacy migration, rollback, and fail-closed guards"
