#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  compose)
    case " $* " in
      *" compose version "*) echo "Docker Compose version v2.30.0"; exit 0 ;;
      *" ps -q backend "*) echo "mock-backend-container"; exit 0 ;;
      *" ps -q frontend "*) echo "mock-frontend-container"; exit 0 ;;
      *) exit 0 ;;
    esac
    ;;
  inspect)
    format="${3:-}"
    container="${4:-}"
    if [[ "$format" == "{{.State.Running}}" ]]; then
      [[ "$container" == mock-backend-container || "$container" == mock-frontend-container ]] || exit 1
      echo true
      exit 0
    fi
    if [[ "$format" == "{{.Image}}" ]]; then
      case "$container" in
        mock-backend-container) echo "sha256:1111111111111111111111111111111111111111111111111111111111111111"; exit 0 ;;
        mock-frontend-container) echo "sha256:2222222222222222222222222222222222222222222222222222222222222222"; exit 0 ;;
      esac
    fi
    exit 1
    ;;
  image)
    [[ "${2:-}" == inspect ]] || exit 1
    image_id="${5:-}"
    case "$image_id" in
      sha256:1111111111111111111111111111111111111111111111111111111111111111)
        [[ "${MOCK_NO_REPODIGEST:-}" == backend ]] || echo "ghcr.io/cscpratapnagar-ai/cricpulse-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        exit 0
        ;;
      sha256:2222222222222222222222222222222222222222222222222222222222222222)
        [[ "${MOCK_NO_REPODIGEST:-}" == frontend ]] || echo "ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        exit 0
        ;;
    esac
    exit 1
    ;;
  *) echo "Unexpected docker invocation: $*" >&2; exit 1 ;;
esac
MOCK_DOCKER
chmod +x "$TMP/bin/docker"

make_fixture() {
  local name="$1" smoke_mode="$2"
  local app="$TMP/$name"
  mkdir -p "$app/release-tools"
  cat > "$app/.env" <<'ENV'
POSTGRES_PASSWORD=Preflight-Only-Secret-7c29a4b6
JWT_SECRET=ci-jwt-secret-at-least-32-bytes-long
BACKEND_TAG=sha-abcdef1
FRONTEND_TAG=sha-1234567
ENV
  chmod 600 "$app/.env"
  cp "$ROOT/scripts/production-release-preflight.sh" "$app/release-tools/"
  touch "$app/docker-compose.production.yml"
  if [[ "$smoke_mode" == rollback ]]; then
    cat > "$app/release-tools/production-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
set -euo pipefail
count=0
[[ ! -f "$MOCK_SMOKE_COUNTER_PATH" ]] || count="$(cat "$MOCK_SMOKE_COUNTER_PATH")"
count=$((count + 1))
printf '%s' "$count" > "$MOCK_SMOKE_COUNTER_PATH"
if [[ "$count" -eq 1 ]]; then
  echo "simulated new-release smoke failure" >&2
  exit 1
fi
echo "simulated previous-release smoke success"
SMOKE
  else
    cat > "$app/release-tools/production-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
set -euo pipefail
echo "simulated deployment smoke success"
SMOKE
  fi
  chmod +x "$app/release-tools/"*.sh
  printf 'test backup data\n' > "$TMP/$name.dump"
  printf '%s\n' "$app"
}

export PATH="$TMP/bin:$PATH"
export MOCK_SMOKE_COUNTER_PATH="$TMP/smoke-counter"
export NEW_BACKEND_DIGEST=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
export NEW_FRONTEND_DIGEST=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd

echo "[1/3] Verify successful deployment records and uses new digest-pinned images"
SUCCESS_APP="$(make_fixture success success)"
bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$SUCCESS_APP" sha-bbbbbbb sha-ccccccc "$NEW_BACKEND_DIGEST" "$NEW_FRONTEND_DIGEST" \
  "$TMP/success.dump" https://api.example.test https://app.example.test \
  http://127.0.0.1:8081/actuator/health/readiness ""
grep -qx 'BACKEND_TAG=sha-bbbbbbb' "$SUCCESS_APP/.env"
grep -qx 'FRONTEND_TAG=sha-ccccccc' "$SUCCESS_APP/.env"
grep -qx "BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@$NEW_BACKEND_DIGEST" "$SUCCESS_APP/.env"
grep -qx "FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@$NEW_FRONTEND_DIGEST" "$SUCCESS_APP/.env"
grep -qx "BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@$NEW_BACKEND_DIGEST" "$SUCCESS_APP/.last-known-good-release"
grep -qx "FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@$NEW_FRONTEND_DIGEST" "$SUCCESS_APP/.last-known-good-release"

echo "[2/3] Verify failed smoke check restores exact previous running RepoDigests"
ROLLBACK_APP="$(make_fixture rollback rollback)"
rm -f "$MOCK_SMOKE_COUNTER_PATH"
if bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$ROLLBACK_APP" sha-ddddddd sha-eeeeeee "$NEW_BACKEND_DIGEST" "$NEW_FRONTEND_DIGEST" \
  "$TMP/rollback.dump" https://api.example.test https://app.example.test \
  http://127.0.0.1:8081/actuator/health/readiness ""; then
  echo "Expected the deployment command to report the failed release after rollback" >&2
  exit 1
fi
grep -qx 'BACKEND_TAG=sha-abcdef1' "$ROLLBACK_APP/.env"
grep -qx 'FRONTEND_TAG=sha-1234567' "$ROLLBACK_APP/.env"
grep -qx 'BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$ROLLBACK_APP/.env"
grep -qx 'FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' "$ROLLBACK_APP/.env"
[[ "$(cat "$MOCK_SMOKE_COUNTER_PATH")" == "2" ]] || { echo "Expected smoke to run once for failed release and once for rollback" >&2; exit 1; }

echo "[3/3] Fail closed when current running image has no registry RepoDigest"
NO_DIGEST_APP="$(make_fixture no-digest success)"
cp "$NO_DIGEST_APP/.env" "$TMP/no-digest.env.before"
export MOCK_NO_REPODIGEST=backend
if bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$NO_DIGEST_APP" sha-fffffff sha-9999999 "$NEW_BACKEND_DIGEST" "$NEW_FRONTEND_DIGEST" \
  "$TMP/no-digest.dump" https://api.example.test https://app.example.test \
  http://127.0.0.1:8081/actuator/health/readiness ""; then
  echo "Expected deployment to fail when exact rollback digest cannot be captured" >&2
  exit 1
fi
cmp "$TMP/no-digest.env.before" "$NO_DIGEST_APP/.env"
unset MOCK_NO_REPODIGEST

echo "PASS: production digest pinning, exact rollback, and fail-closed rollback precondition are covered."
