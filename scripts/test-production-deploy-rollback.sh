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
  cat > "$app/release-tools/production-backup-restore-rehearsal.sh" <<'RESTORE'
#!/usr/bin/env bash
set -euo pipefail
[[ -f "$1" && -s "$1" ]] || exit 20
printf '%s\n' "$1" >> "$MOCK_RESTORE_LOG"
[[ "${MOCK_RESTORE_FAIL:-0}" != 1 ]]
RESTORE
  cat > "$app/release-tools/production-deployment-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
set -euo pipefail
count=0
[[ ! -f "$MOCK_SMOKE_COUNTER_PATH" ]] || count="$(cat "$MOCK_SMOKE_COUNTER_PATH")"
count=$((count + 1))
printf '%s' "$count" > "$MOCK_SMOKE_COUNTER_PATH"
case "${MOCK_SMOKE_MODE:-success}" in
  rollback)
    [[ "$count" -ne 1 ]] || { echo "simulated new-release smoke failure" >&2; exit 1; }
    ;;
  flaky)
    [[ "$count" -ne 2 ]] || { echo "simulated intermittent production failure" >&2; exit 1; }
    ;;
esac
echo "simulated deployment smoke success"
SMOKE  touch "$app/docker-compose.production.yml"
  chmod +x "$app/release-tools/"*.sh
  printf 'test backup data\n' > "$TMP/$name.dump"
  printf '%s\n' "$app"
}

export PATH="$TMP/bin:$PATH"
export MOCK_SMOKE_COUNTER_PATH="$TMP/smoke-counter"
export MOCK_RESTORE_LOG="$TMP/restore.log"
export NEW_BACKEND_DIGEST=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
export NEW_FRONTEND_DIGEST=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd

echo "[1/5] Verify successful deployment requires three consecutive smoke passes and records a successful restore rehearsal"
rm -f "$MOCK_SMOKE_COUNTER_PATH"
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
grep -Fqx "$TMP/success.dump" "$MOCK_RESTORE_LOG"
[[ "$(cat "$MOCK_SMOKE_COUNTER_PATH")" == "3" ]] || { echo "Expected three consecutive production smoke passes" >&2; exit 1; }

echo "[2/5] Verify restore failure blocks before .env mutation or container inspection"
RESTORE_FAIL_APP="$(make_fixture restore-fail success)"
cp "$RESTORE_FAIL_APP/.env" "$TMP/restore-fail.env.before"
if MOCK_RESTORE_FAIL=1 bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$RESTORE_FAIL_APP" sha-bbbbbbb sha-ccccccc "$NEW_BACKEND_DIGEST" "$NEW_FRONTEND_DIGEST" \
  "$TMP/restore-fail.dump" https://api.example.test https://app.example.test \
  http://127.0.0.1:8081/actuator/health/readiness ""; then
  echo "Expected failed restore rehearsal to block production deployment" >&2
  exit 1
fi
cmp "$TMP/restore-fail.env.before" "$RESTORE_FAIL_APP/.env"
[[ ! -f "$RESTORE_FAIL_APP/.last-known-good-release" ]]

echo "[3/5] Verify failed smoke check restores exact previous running RepoDigests"
ROLLBACK_APP="$(make_fixture rollback rollback)"
rm -f "$MOCK_SMOKE_COUNTER_PATH"
if MOCK_SMOKE_MODE=rollback bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
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
[[ "$(cat "$MOCK_SMOKE_COUNTER_PATH")" == "4" ]] || { echo "Expected one failed new-release check and three rollback stability checks" >&2; exit 1; }

echo "[4/5] Verify an intermittent second-check failure triggers rollback"
FLAKY_APP="$(make_fixture flaky flaky)"
rm -f "$MOCK_SMOKE_COUNTER_PATH"
if MOCK_SMOKE_MODE=flaky bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$FLAKY_APP" sha-fffffff sha-9999999 "$NEW_BACKEND_DIGEST" "$NEW_FRONTEND_DIGEST" \
  "$TMP/flaky.dump" https://api.example.test https://app.example.test \
  http://127.0.0.1:8081/actuator/health/readiness ""; then
  echo "Expected intermittent production health failure to roll back the release" >&2
  exit 1
fi
grep -qx 'BACKEND_TAG=sha-abcdef1' "$FLAKY_APP/.env"
grep -qx 'FRONTEND_TAG=sha-1234567' "$FLAKY_APP/.env"
[[ "$(cat "$MOCK_SMOKE_COUNTER_PATH")" == "5" ]] || { echo "Expected two new-release checks and three rollback checks" >&2; exit 1; }

echo "[5/5] Fail closed when current running image has no registry RepoDigest"
NO_DIGEST_APP="$(make_fixture no-digest success)"
cp "$NO_DIGEST_APP/.env" "$TMP/no-digest.env.before"
if MOCK_NO_REPODIGEST=backend bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$NO_DIGEST_APP" sha-fffffff sha-9999999 "$NEW_BACKEND_DIGEST" "$NEW_FRONTEND_DIGEST" \
  "$TMP/no-digest.dump" https://api.example.test https://app.example.test \
  http://127.0.0.1:8081/actuator/health/readiness ""; then
  echo "Expected deployment to fail when exact rollback digest cannot be captured" >&2
  exit 1
fi
cmp "$TMP/no-digest.env.before" "$NO_DIGEST_APP/.env"

echo "PASS: production sustained health gate, backup restore, digest pinning, exact rollback, and fail-closed rollback precondition are covered."
