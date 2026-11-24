#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
fail() { echo "STAGING DEPLOYMENT FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 8 ]] || fail "Expected APP_DIR BACKEND_TAG FRONTEND_TAG BACKUP_FILE API_BASE_URL APP_URL ACTUATOR_URL MATCH_ID"
APP_DIR="$1"; NEW_BACKEND_TAG="$2"; NEW_FRONTEND_TAG="$3"; BACKUP_FILE="$4"
API_BASE_URL="$5"; APP_URL="$6"; ACTUATOR_URL="$7"; MATCH_ID="$8"
[[ "$APP_DIR" =~ ^/[a-zA-Z0-9_./-]+/staging$ ]] || fail "APP_DIR must be a dedicated absolute directory ending in /staging"
[[ "$NEW_BACKEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Backend tag must be immutable sha-*"
[[ "$NEW_FRONTEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Frontend tag must be immutable sha-*"
[[ "$BACKUP_FILE" == /* && -s "$BACKUP_FILE" ]] || fail "A non-empty absolute staging database backup is required"
find "$BACKUP_FILE" -mmin -1440 -print -quit | grep -q . || fail "Staging backup must be no older than 24 hours"
command -v docker >/dev/null 2>&1 || fail "Docker is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
APP_DIR="$(cd "$APP_DIR" && pwd)"
TOOLS_DIR="$APP_DIR/release-tools"; ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose.staging.yml"; SMOKE="$TOOLS_DIR/staging-deployment-smoke.sh"
[[ -f "$ENV_FILE" && -f "$COMPOSE_FILE" && -f "$SMOKE" ]] || fail "Staging deployment files or existing .env are missing"
echo "Staging pre-deployment backup checksum:"; sha256sum "$BACKUP_FILE"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
OLD_BACKEND_TAG="${BACKEND_TAG:-}"; OLD_FRONTEND_TAG="${FRONTEND_TAG:-}"
[[ "$OLD_BACKEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Existing staging BACKEND_TAG is missing or not immutable; rollback is unsafe"
[[ "$OLD_FRONTEND_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Existing staging FRONTEND_TAG is missing or not immutable; rollback is unsafe"
[[ "$OLD_BACKEND_TAG" != "$NEW_BACKEND_TAG" || "$OLD_FRONTEND_TAG" != "$NEW_FRONTEND_TAG" ]] || fail "Requested staging release is already deployed"
update_tags() {
  local backend="$1" frontend="$2" temp
  temp="$(mktemp "$APP_DIR/.env.staging.XXXXXX")"
  awk -v backend="$backend" -v frontend="$frontend" '
    BEGIN { b=0; f=0 }
    /^BACKEND_TAG=/ { print "BACKEND_TAG=" backend; b=1; next }
    /^FRONTEND_TAG=/ { print "FRONTEND_TAG=" frontend; f=1; next }
    { print }
    END { if (!b) print "BACKEND_TAG=" backend; if (!f) print "FRONTEND_TAG=" frontend }
  ' "$ENV_FILE" > "$temp"
  chmod 600 "$temp"; mv "$temp" "$ENV_FILE"
}
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
run_preflight() {
  ( cd "$APP_DIR"; set -a; source "$ENV_FILE"; set +a
    [[ "${#POSTGRES_PASSWORD}" -ge 20 && "${#JWT_SECRET}" -ge 32 ]] || exit 1
    [[ "$JWT_SECRET" != *"change-me"* && "$JWT_SECRET" != *"local-only"* && "$JWT_SECRET" != *"ci-only"* ]] || exit 1
    BACKEND_TAG="$1" FRONTEND_TAG="$2" docker compose -f docker-compose.staging.yml config --quiet
  )
}
wait_and_smoke() {
  compose up -d --remove-orphans --wait --wait-timeout 180
  API_BASE_URL="$API_BASE_URL" APP_URL="$APP_URL" ACTUATOR_URL="$ACTUATOR_URL" MATCH_ID="$MATCH_ID" bash "$SMOKE"
}
echo "Applying staging release $NEW_BACKEND_TAG / $NEW_FRONTEND_TAG"
update_tags "$NEW_BACKEND_TAG" "$NEW_FRONTEND_TAG"
if ! run_preflight "$NEW_BACKEND_TAG" "$NEW_FRONTEND_TAG"; then
  update_tags "$OLD_BACKEND_TAG" "$OLD_FRONTEND_TAG"
  fail "Staging preflight failed; previous immutable tags restored"
fi
if compose pull backend frontend && wait_and_smoke; then
  printf 'BACKEND_TAG=%s\nFRONTEND_TAG=%s\nDEPLOYED_AT=%s\n' "$NEW_BACKEND_TAG" "$NEW_FRONTEND_TAG" "$(date -u +%FT%TZ)" > "$APP_DIR/.last-known-good-staging.tmp"
  chmod 600 "$APP_DIR/.last-known-good-staging.tmp"; mv "$APP_DIR/.last-known-good-staging.tmp" "$APP_DIR/.last-known-good-staging"
  echo "PASS: staging deployment and all required health checks succeeded"; exit 0
fi
echo "Staging release failed; rolling back to previous immutable image tags." >&2
update_tags "$OLD_BACKEND_TAG" "$OLD_FRONTEND_TAG"
if compose pull backend frontend && wait_and_smoke; then
  echo "ROLLBACK PASS: previous staging release is healthy again" >&2
  fail "New staging release was rolled back; inspect logs before retrying"
fi
echo "CRITICAL: staging rollback could not restore a healthy previous release" >&2
fail "Manual staging operator intervention is required"
