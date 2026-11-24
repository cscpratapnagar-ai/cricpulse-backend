#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { echo "STAGING DEPLOYMENT FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 8 ]] || fail "Expected APP_DIR BACKEND_DIGEST FRONTEND_DIGEST BACKUP_FILE API_BASE_URL APP_URL ACTUATOR_URL MATCH_ID"
APP_DIR="$1"; NEW_BACKEND_DIGEST="$2"; NEW_FRONTEND_DIGEST="$3"; BACKUP_FILE="$4"
API_BASE_URL="$5"; APP_URL="$6"; ACTUATOR_URL="$7"; MATCH_ID="$8"

[[ "$APP_DIR" =~ ^/[a-zA-Z0-9_./-]+/staging$ ]] || fail "APP_DIR must be a dedicated absolute directory ending in /staging"
[[ "$NEW_BACKEND_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Backend image digest must be sha256:<64 lowercase hex>"
[[ "$NEW_FRONTEND_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Frontend image digest must be sha256:<64 lowercase hex>"
[[ "$BACKUP_FILE" == /* && -s "$BACKUP_FILE" ]] || fail "A non-empty absolute staging database backup is required"
find "$BACKUP_FILE" -mmin -1440 -print -quit | grep -q . || fail "Staging backup must be no older than 24 hours"
command -v docker >/dev/null 2>&1 || fail "Docker is required"
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is required"
docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is required for legacy rollback reference migration"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
command -v pg_restore >/dev/null 2>&1 || fail "PostgreSQL pg_restore client is required"

APP_DIR="$(cd "$APP_DIR" && pwd)"
TOOLS_DIR="$APP_DIR/release-tools"; ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose.staging.yml"; SMOKE="$TOOLS_DIR/staging-deployment-smoke.sh"
[[ -f "$ENV_FILE" && -f "$COMPOSE_FILE" && -f "$SMOKE" ]] || fail "Staging deployment files or existing .env are missing"

echo "Staging pre-deployment backup checksum:"
sha256sum "$BACKUP_FILE"
pg_restore --list "$BACKUP_FILE" >/dev/null || fail "Staging backup is not a readable PostgreSQL custom-format archive"

# Parse only literal KEY=value lines. Never execute the staging .env as shell code.
read_env_value() {
  local key="$1"
  awk -v key="$key" '
    index($0, "=") > 0 {
      name=substr($0, 1, index($0, "=")-1)
      if (name == key) {
        count++
        value=substr($0, index($0, "=")+1)
      }
    }
    END { if (count == 1) print value; else exit 1 }
  ' "$ENV_FILE"
}
read_optional_env_value() {
  local key="$1"
  awk -v key="$key" '
    index($0, "=") > 0 {
      name=substr($0, 1, index($0, "=")-1)
      if (name == key) {
        count++
        value=substr($0, index($0, "=")+1)
      }
    }
    END { if (count > 1) exit 1; if (count == 1) print value }
  ' "$ENV_FILE"
}
POSTGRES_PASSWORD="$(read_env_value POSTGRES_PASSWORD)" || fail "POSTGRES_PASSWORD must appear exactly once in .env"
JWT_SECRET="$(read_env_value JWT_SECRET)" || fail "JWT_SECRET must appear exactly once in .env"

BACKEND_REPOSITORY="ghcr.io/cscpratapnagar-ai/cricpulse-backend"
FRONTEND_REPOSITORY="ghcr.io/cscpratapnagar-ai/cricpulse-ui"

resolve_legacy_tag() {
  local repository="$1" tag="$2" digest
  [[ "$tag" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Legacy rollback tag is missing or mutable"
  digest="$(docker buildx imagetools inspect --raw "$repository:$tag" | sha256sum | awk '{print $1}')"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || fail "Could not resolve legacy rollback tag to a registry digest"
  printf '%s@sha256:%s' "$repository" "$digest"
}

validate_image_ref() {
  local value="$1" expected="$2" label="$3"
  [[ "$value" == "$expected"@sha256:* ]] || fail "$label must reference the expected registry repository by digest"
  [[ "${value##*@sha256:}" =~ ^[0-9a-f]{64}$ ]] || fail "$label digest is malformed"
}

OLD_BACKEND_IMAGE="$(read_optional_env_value BACKEND_IMAGE)" || fail "BACKEND_IMAGE appears more than once in .env"
OLD_FRONTEND_IMAGE="$(read_optional_env_value FRONTEND_IMAGE)" || fail "FRONTEND_IMAGE appears more than once in .env"
LEGACY_BACKEND_TAG="$(read_optional_env_value BACKEND_TAG)" || fail "BACKEND_TAG appears more than once in .env"
LEGACY_FRONTEND_TAG="$(read_optional_env_value FRONTEND_TAG)" || fail "FRONTEND_TAG appears more than once in .env"
# One-time migration for an existing staging host deployed by the prior SHA-tag workflow.
if [[ -z "$OLD_BACKEND_IMAGE" ]]; then
  OLD_BACKEND_IMAGE="$(resolve_legacy_tag "$BACKEND_REPOSITORY" "$LEGACY_BACKEND_TAG")"
fi
if [[ -z "$OLD_FRONTEND_IMAGE" ]]; then
  OLD_FRONTEND_IMAGE="$(resolve_legacy_tag "$FRONTEND_REPOSITORY" "$LEGACY_FRONTEND_TAG")"
fi
validate_image_ref "$OLD_BACKEND_IMAGE" "$BACKEND_REPOSITORY" "Existing backend rollback image"
validate_image_ref "$OLD_FRONTEND_IMAGE" "$FRONTEND_REPOSITORY" "Existing frontend rollback image"

NEW_BACKEND_IMAGE="$BACKEND_REPOSITORY@$NEW_BACKEND_DIGEST"
NEW_FRONTEND_IMAGE="$FRONTEND_REPOSITORY@$NEW_FRONTEND_DIGEST"
[[ "$OLD_BACKEND_IMAGE" != "$NEW_BACKEND_IMAGE" || "$OLD_FRONTEND_IMAGE" != "$NEW_FRONTEND_IMAGE" ]] || fail "Requested staging release is already deployed"

update_images() {
  local backend="$1" frontend="$2" temp
  temp="$(mktemp "$APP_DIR/.env.staging.XXXXXX")"
  awk -v backend="$backend" -v frontend="$frontend" '
    BEGIN { b=0; f=0 }
    /^BACKEND_IMAGE=/ { print "BACKEND_IMAGE=" backend; b=1; next }
    /^FRONTEND_IMAGE=/ { print "FRONTEND_IMAGE=" frontend; f=1; next }
    { print }
    END { if (!b) print "BACKEND_IMAGE=" backend; if (!f) print "FRONTEND_IMAGE=" frontend }
  ' "$ENV_FILE" > "$temp"
  chmod 600 "$temp"; mv "$temp" "$ENV_FILE"
}
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
run_preflight() {
  ( cd "$APP_DIR"
    [[ "${#POSTGRES_PASSWORD}" -ge 20 && "${#JWT_SECRET}" -ge 32 ]] || exit 1
    [[ "$JWT_SECRET" != *"change-me"* && "$JWT_SECRET" != *"local-only"* && "$JWT_SECRET" != *"ci-only"* ]] || exit 1
    [[ "$POSTGRES_PASSWORD" != *"cricket_dev_password"* && "$POSTGRES_PASSWORD" != *"ci-postgres-password"* ]] || exit 1
    BACKEND_IMAGE="$1" FRONTEND_IMAGE="$2" docker compose --env-file "$ENV_FILE" -f docker-compose.staging.yml config --quiet
  )
}
wait_and_smoke() {
  compose up -d --remove-orphans --wait --wait-timeout 180
  # A single healthy response immediately after startup is not sufficient to certify a release.
  # Require three complete external smoke passes over a 20-second observation window.
  for attempt in 1 2 3; do
    echo "Staging release stability check $attempt/3"
    API_BASE_URL="$API_BASE_URL" APP_URL="$APP_URL" ACTUATOR_URL="$ACTUATOR_URL" MATCH_ID="$MATCH_ID" bash "$SMOKE" || {
      echo "Staging stability check $attempt/3 failed" >&2
      return 1
    }
    if [[ "$attempt" -lt 3 ]]; then
      sleep 10
    fi
  done
}

echo "Applying digest-pinned staging release:"
echo "  backend: $NEW_BACKEND_IMAGE"
echo "  frontend: $NEW_FRONTEND_IMAGE"
update_images "$NEW_BACKEND_IMAGE" "$NEW_FRONTEND_IMAGE"
if ! run_preflight "$NEW_BACKEND_IMAGE" "$NEW_FRONTEND_IMAGE"; then
  update_images "$OLD_BACKEND_IMAGE" "$OLD_FRONTEND_IMAGE"
  fail "Staging preflight failed; previous digest-pinned images restored"
fi
if compose pull backend frontend && wait_and_smoke; then
  printf 'BACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\nDEPLOYED_AT=%s\n' "$NEW_BACKEND_IMAGE" "$NEW_FRONTEND_IMAGE" "$(date -u +%FT%TZ)" > "$APP_DIR/.last-known-good-staging.tmp"
  chmod 600 "$APP_DIR/.last-known-good-staging.tmp"; mv "$APP_DIR/.last-known-good-staging.tmp" "$APP_DIR/.last-known-good-staging"
  echo "PASS: staging deployment and all required health checks succeeded"; exit 0
fi

echo "Staging release failed; rolling back to previous digest-pinned images." >&2
update_images "$OLD_BACKEND_IMAGE" "$OLD_FRONTEND_IMAGE"
if compose pull backend frontend && wait_and_smoke; then
  echo "ROLLBACK PASS: previous staging release is healthy again" >&2
  fail "New staging release was rolled back; inspect logs before retrying"
fi
echo "CRITICAL: staging rollback could not restore a healthy previous release" >&2
fail "Manual staging operator intervention is required"
