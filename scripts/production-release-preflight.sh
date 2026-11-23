#!/usr/bin/env bash
set -euo pipefail

fail() { echo "RELEASE PREFLIGHT FAILED: $*" >&2; exit 1; }

: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
: "${JWT_SECRET:?JWT_SECRET must be set}"
: "${BACKEND_TAG:?BACKEND_TAG must be an immutable sha-* image tag}"
: "${FRONTEND_TAG:?FRONTEND_TAG must be an immutable sha-* image tag}"

[[ "${#POSTGRES_PASSWORD}" -ge 20 ]] || fail "POSTGRES_PASSWORD must contain at least 20 characters"
[[ "${#JWT_SECRET}" -ge 32 ]] || fail "JWT_SECRET must contain at least 32 characters"
[[ "$JWT_SECRET" != *"change-me"* && "$JWT_SECRET" != *"local-only"* && "$JWT_SECRET" != *"ci-only"* ]] || fail "JWT_SECRET contains a known development/CI placeholder"
[[ "$POSTGRES_PASSWORD" != *"cricket_dev_password"* && "$POSTGRES_PASSWORD" != *"ci-postgres-password"* ]] || fail "POSTGRES_PASSWORD contains a known development/CI placeholder"

for image_tag in "$BACKEND_TAG" "$FRONTEND_TAG"; do
  [[ "$image_tag" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Image tag '$image_tag' is not an immutable sha-<7..40 lowercase hex> tag"
done

command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is required"

docker compose -f docker-compose.production.yml config --quiet
echo "PASS: production secrets are present, placeholders rejected, both image tags are immutable, and production Compose configuration is valid"
