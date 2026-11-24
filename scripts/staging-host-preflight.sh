#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { echo "STAGING HOST PREFLIGHT FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 4 ]] || fail "Expected APP_DIR BACKUP_FILE BACKEND_IMAGE FRONTEND_IMAGE"
APP_DIR="$1"; BACKUP_FILE="$2"; BACKEND_IMAGE="$3"; FRONTEND_IMAGE="$4"

[[ "$APP_DIR" =~ ^/[a-zA-Z0-9_./-]+/staging$ ]] || fail "APP_DIR must be a dedicated absolute path ending in /staging"
[[ -d "$APP_DIR" && ! -L "$APP_DIR" ]] || fail "Staging application directory must already exist and must not be a symlink"
APP_DIR="$(realpath -e "$APP_DIR")" || fail "Cannot resolve staging application directory"
[[ "$APP_DIR" =~ ^/[a-zA-Z0-9_./-]+/staging$ ]] || fail "Resolved staging path is outside the permitted staging layout"
[[ "$(stat -c '%U' "$APP_DIR")" == "$(id -un)" ]] || fail "Staging application directory must be owned by the SSH deployment user"

ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose.staging.yml"
SMOKE_FILE="$APP_DIR/release-tools/staging-deployment-smoke.sh"
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" && -r "$ENV_FILE" ]] || fail "A readable regular staging .env file is required"
[[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" ]] || fail "Staging Compose file is missing or is a symlink"
[[ -f "$SMOKE_FILE" && ! -L "$SMOKE_FILE" && -x "$SMOKE_FILE" ]] || fail "Staging smoke-check script is missing or not executable"
[[ "$(stat -c '%U' "$ENV_FILE")" == "$(id -un)" ]] || fail "Staging .env must be owned by the SSH deployment user"
ENV_MODE="$(stat -c '%a' "$ENV_FILE")"
(( (8#$ENV_MODE & 077) == 0 )) || fail "Staging .env permissions are too broad; use chmod 600"

[[ "$BACKEND_IMAGE" =~ ^ghcr\.io/cscpratapnagar-ai/cricpulse-backend@sha256:[0-9a-f]{64}$ ]] || fail "Backend image must be the verified GHCR digest reference"
[[ "$FRONTEND_IMAGE" =~ ^ghcr\.io/cscpratapnagar-ai/cricpulse-ui@sha256:[0-9a-f]{64}$ ]] || fail "Frontend image must be the verified GHCR digest reference"
[[ "$BACKUP_FILE" == /* && -f "$BACKUP_FILE" && ! -L "$BACKUP_FILE" && -r "$BACKUP_FILE" && -s "$BACKUP_FILE" ]] || fail "Backup must be a readable, non-empty regular file at an absolute path"
find "$BACKUP_FILE" -mmin -1440 -print -quit | grep -q . || fail "Staging backup must be no older than 24 hours"

# Parse only literal KEY=value lines. Never source a deployment .env as shell code.
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
POSTGRES_PASSWORD="$(read_env_value POSTGRES_PASSWORD)" || fail "POSTGRES_PASSWORD must appear exactly once in .env"
JWT_SECRET="$(read_env_value JWT_SECRET)" || fail "JWT_SECRET must appear exactly once in .env"
[[ "${#POSTGRES_PASSWORD}" -ge 20 ]] || fail "POSTGRES_PASSWORD must contain at least 20 characters"
[[ "${#JWT_SECRET}" -ge 32 ]] || fail "JWT_SECRET must contain at least 32 characters"
[[ "$POSTGRES_PASSWORD" != *"cricket_dev_password"* && "$POSTGRES_PASSWORD" != *"ci-postgres-password"* ]] || fail "CI/development database password is forbidden"
[[ "$JWT_SECRET" != *"change-me"* && "$JWT_SECRET" != *"local-only"* && "$JWT_SECRET" != *"ci-only"* ]] || fail "Placeholder JWT secret is forbidden"

for tool in docker pg_restore curl jq sha256sum df; do
  command -v "$tool" >/dev/null 2>&1 || fail "Required host utility is missing: $tool"
done
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable to the deployment user"
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is unavailable"
docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is unavailable"
pg_restore --list "$BACKUP_FILE" >/dev/null 2>&1 || fail "Backup is not a readable PostgreSQL custom-format archive"

AVAILABLE_KB="$(df -Pk "$APP_DIR" | awk 'NR == 2 {print $4}')"
[[ "$AVAILABLE_KB" =~ ^[0-9]+$ ]] || fail "Unable to determine free disk space"
(( AVAILABLE_KB >= 2097152 )) || fail "At least 2 GiB of free disk space is required for safe image pulls and rollback"

# Prove that the staging host itself can read both exact immutable GHCR images.
docker buildx imagetools inspect "$BACKEND_IMAGE" >/dev/null 2>&1 || fail "Staging host cannot access the verified backend digest in GHCR"
docker buildx imagetools inspect "$FRONTEND_IMAGE" >/dev/null 2>&1 || fail "Staging host cannot access the verified frontend digest in GHCR"

BACKEND_IMAGE="$BACKEND_IMAGE" FRONTEND_IMAGE="$FRONTEND_IMAGE" POSTGRES_PASSWORD="$POSTGRES_PASSWORD" JWT_SECRET="$JWT_SECRET" \
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet || fail "Staging Compose configuration is invalid"

echo "PASS: staging host, secret-file permissions, backup, Docker, disk, GHCR digest access, and Compose configuration are ready"
