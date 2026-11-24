#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { echo "PRODUCTION DEPLOYMENT FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 10 ]] || fail "Expected APP_DIR BACKEND_TAG FRONTEND_TAG BACKEND_DIGEST FRONTEND_DIGEST BACKUP_FILE API_BASE_URL APP_URL ACTUATOR_URL MATCH_ID"

APP_DIR="$1"
NEW_BACKEND_TAG="$2"
NEW_FRONTEND_TAG="$3"
BACKEND_DIGEST="$4"
FRONTEND_DIGEST="$5"
BACKUP_FILE="$6"
API_BASE_URL="$7"
APP_URL="$8"
ACTUATOR_URL="$9"
MATCH_ID="${10}"

[[ "$APP_DIR" =~ ^/[a-zA-Z0-9_./-]+$ ]] || fail "APP_DIR must be a safe absolute path"
[[ "$NEW_BACKEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Backend tag must be immutable sha-*"
[[ "$NEW_FRONTEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Frontend tag must be immutable sha-*"
[[ "$BACKEND_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Backend digest must be a verified sha256 digest"
[[ "$FRONTEND_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Frontend digest must be a verified sha256 digest"
[[ "$BACKUP_FILE" == /* && -f "$BACKUP_FILE" && ! -L "$BACKUP_FILE" && -s "$BACKUP_FILE" ]] || fail "A non-empty regular pre-deployment backup file is required"
find "$BACKUP_FILE" -mmin -1440 -print -quit | grep -q . || fail "Backup must be no older than 24 hours"
[[ "$API_BASE_URL" == https://* && "$APP_URL" == https://* ]] || fail "Production API and application URLs must use HTTPS"
[[ "$ACTUATOR_URL" =~ ^http://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?/ ]] || fail "Actuator URL must use a host-local HTTP endpoint"
if [[ -n "$MATCH_ID" ]]; then
  [[ "$MATCH_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || fail "MATCH_ID must be a UUID"
fi
command -v docker >/dev/null 2>&1 || fail "Docker is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
command -v stat >/dev/null 2>&1 || fail "stat is required"

APP_DIR="$(cd "$APP_DIR" && pwd)"
TOOLS_DIR="$APP_DIR/release-tools"
ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose.production.yml"
PREFLIGHT="$TOOLS_DIR/production-release-preflight.sh"
RESTORE_REHEARSAL="$TOOLS_DIR/production-backup-restore-rehearsal.sh"
SMOKE="$TOOLS_DIR/production-deployment-smoke.sh"
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" && -f "$COMPOSE_FILE" && -f "$PREFLIGHT" && -f "$RESTORE_REHEARSAL" && -f "$SMOKE" ]] || fail "Deployment files or existing .env are missing"
[[ -O "$ENV_FILE" ]] || fail "Production .env must be owned by the deployment user"
[[ "$(stat -c '%a' "$ENV_FILE")" == "600" ]] || fail "Production .env must have mode 0600"

echo "Pre-deployment backup checksum:"
sha256sum "$BACKUP_FILE"

# Prove the supplied backup is actually restorable before reading or changing
# release state. The probe uses a disposable PostgreSQL container with no
# network, published ports, or production volume mounts.
bash "$RESTORE_REHEARSAL" "$BACKUP_FILE" || fail "Production backup restore rehearsal failed; deployment is blocked"

# Load the existing operator-managed environment without printing secrets.
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
OLD_BACKEND_TAG="${BACKEND_TAG:-}"
OLD_FRONTEND_TAG="${FRONTEND_TAG:-}"
[[ "$OLD_BACKEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Existing BACKEND_TAG is missing or not immutable; automatic rollback is unsafe"
[[ "$OLD_FRONTEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Existing FRONTEND_TAG is missing or not immutable; automatic rollback is unsafe"
[[ "$OLD_BACKEND_TAG" != "$NEW_BACKEND_TAG" || "$OLD_FRONTEND_TAG" != "$NEW_FRONTEND_TAG" ]] || fail "Requested release is already deployed"

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

current_running_image_ref() {
  local service="$1" repository="$2" container running image_id repo_digest
  local -a containers refs
  mapfile -t containers < <(compose ps -q "$service")
  [[ "${#containers[@]}" -eq 1 && -n "${containers[0]}" ]] || fail "Expected exactly one existing $service container; refusing an unsafe rollback"
  container="${containers[0]}"
  running="$(docker inspect --format '{{.State.Running}}' "$container")" || fail "Unable to inspect existing $service container"
  [[ "$running" == "true" ]] || fail "Existing $service container is not running; refusing an unsafe rollback"
  image_id="$(docker inspect --format '{{.Image}}' "$container")" || fail "Unable to identify existing $service image"
  [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Existing $service image ID is invalid"
  repo_digest="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image_id")" || fail "Unable to read existing $service RepoDigests"
  mapfile -t refs < <(printf '%s\\n' "$repo_digest" | grep -E "^$repository@sha256:[0-9a-f]{64}$" || true)
  [[ "${#refs[@]}" -eq 1 ]] || fail "Expected exactly one existing $service GHCR RepoDigest; refusing a tag-based rollback"
  printf '%s' "${refs[0]}"
}

OLD_BACKEND_IMAGE="$(current_running_image_ref backend ghcr.io/cscpratapnagar-ai/cricpulse-backend)"
OLD_FRONTEND_IMAGE="$(current_running_image_ref frontend ghcr.io/cscpratapnagar-ai/cricpulse-ui)"
NEW_BACKEND_IMAGE="ghcr.io/cscpratapnagar-ai/cricpulse-backend@$BACKEND_DIGEST"
NEW_FRONTEND_IMAGE="ghcr.io/cscpratapnagar-ai/cricpulse-ui@$FRONTEND_DIGEST"

update_release() {
  local backend_tag="$1" frontend_tag="$2" backend_image="$3" frontend_image="$4" temp
  temp="$(mktemp "$APP_DIR/.env.release.XXXXXX")"
  awk -v backend_tag="$backend_tag" -v frontend_tag="$frontend_tag" -v backend_image="$backend_image" -v frontend_image="$frontend_image" '
    BEGIN { bt=0; ft=0; bi=0; fi=0 }
    /^BACKEND_TAG=/ { print "BACKEND_TAG=" backend_tag; bt=1; next }
    /^FRONTEND_TAG=/ { print "FRONTEND_TAG=" frontend_tag; ft=1; next }
    /^BACKEND_IMAGE=/ { print "BACKEND_IMAGE=" backend_image; bi=1; next }
    /^FRONTEND_IMAGE=/ { print "FRONTEND_IMAGE=" frontend_image; fi=1; next }
    { print }
    END {
      if (!bt) print "BACKEND_TAG=" backend_tag
      if (!ft) print "FRONTEND_TAG=" frontend_tag
      if (!bi) print "BACKEND_IMAGE=" backend_image
      if (!fi) print "FRONTEND_IMAGE=" frontend_image
    }
  ' "$ENV_FILE" > "$temp"
  chmod 600 "$temp"
  mv "$temp" "$ENV_FILE"
}

run_preflight() {
  (
    cd "$APP_DIR"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
    BACKEND_TAG="$1" FRONTEND_TAG="$2" bash "$PREFLIGHT"
  )
}

wait_and_smoke() {
  compose pull backend frontend
  compose up -d --remove-orphans --wait --wait-timeout 180
  API_BASE_URL="$API_BASE_URL" APP_URL="$APP_URL" ACTUATOR_URL="$ACTUATOR_URL" MATCH_ID="$MATCH_ID" REQUIRE_HTTPS=true bash "$SMOKE"
}

echo "Applying digest-pinned production release:"
echo "  backend tag: $NEW_BACKEND_TAG"
echo "  backend digest: $BACKEND_DIGEST"
echo "  frontend tag: $NEW_FRONTEND_TAG"
echo "  frontend digest: $FRONTEND_DIGEST"
update_release "$NEW_BACKEND_TAG" "$NEW_FRONTEND_TAG" "$NEW_BACKEND_IMAGE" "$NEW_FRONTEND_IMAGE"
if ! run_preflight "$NEW_BACKEND_TAG" "$NEW_FRONTEND_TAG"; then
  update_release "$OLD_BACKEND_TAG" "$OLD_FRONTEND_TAG" "$OLD_BACKEND_IMAGE" "$OLD_FRONTEND_IMAGE"
  fail "Release preflight failed; previous exact image digests were restored in .env"
fi

if wait_and_smoke; then
  printf 'BACKEND_TAG=%s\\nFRONTEND_TAG=%s\\nBACKEND_IMAGE=%s\\nFRONTEND_IMAGE=%s\\nDEPLOYED_AT=%s\\n' \
    "$NEW_BACKEND_TAG" "$NEW_FRONTEND_TAG" "$NEW_BACKEND_IMAGE" "$NEW_FRONTEND_IMAGE" "$(date -u +%FT%TZ)" > "$APP_DIR/.last-known-good-release.tmp"
  chmod 600 "$APP_DIR/.last-known-good-release.tmp"
  mv "$APP_DIR/.last-known-good-release.tmp" "$APP_DIR/.last-known-good-release"
  echo "PASS: production deployment used verified image digests and all required smoke checks succeeded."
  exit 0
fi

echo "New release failed health/smoke checks; rolling back to the exact previously running image digests." >&2
update_release "$OLD_BACKEND_TAG" "$OLD_FRONTEND_TAG" "$OLD_BACKEND_IMAGE" "$OLD_FRONTEND_IMAGE"
if wait_and_smoke; then
  echo "ROLLBACK PASS: previous exact image digests are healthy again." >&2
  fail "New release was rolled back. Investigate deployment logs before retrying."
fi

echo "CRITICAL: automatic rollback could not restore a healthy previous release." >&2
fail "Manual operator intervention is required. Previous exact image digests remain recorded in .env"
