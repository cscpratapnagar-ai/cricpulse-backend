#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
exit 0
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
  cp "$ROOT/scripts/production-release-preflight.sh" "$app/release-tools/"
  touch "$app/docker-compose.production.yml"
  if [[ "$smoke_mode" == "rollback" ]]; then
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
  touch "$TMP/$name.dump"
  printf '%s\n' "$app"
}

export PATH="$TMP/bin:$PATH"
export MOCK_SMOKE_COUNTER_PATH="$TMP/smoke-counter"

echo "[1/2] Verify successful deployment records the new immutable release"
SUCCESS_APP="$(make_fixture success success)"
bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$SUCCESS_APP" sha-bbbbbbb sha-ccccccc "$TMP/success.dump" \
  https://api.example.test https://app.example.test http://127.0.0.1:8081/actuator/health/readiness ""
grep -qx 'BACKEND_TAG=sha-bbbbbbb' "$SUCCESS_APP/.env"
grep -qx 'FRONTEND_TAG=sha-ccccccc' "$SUCCESS_APP/.env"
grep -qx 'BACKEND_TAG=sha-bbbbbbb' "$SUCCESS_APP/.last-known-good-release"

echo "[2/2] Verify failed smoke check restores the previous immutable release"
ROLLBACK_APP="$(make_fixture rollback rollback)"
rm -f "$MOCK_SMOKE_COUNTER_PATH"
if bash "$ROOT/scripts/production-deploy-with-rollback.sh" \
  "$ROLLBACK_APP" sha-ddddddd sha-eeeeeee "$TMP/rollback.dump" \
  https://api.example.test https://app.example.test http://127.0.0.1:8081/actuator/health/readiness ""; then
  echo "Expected the deployment command to report the failed release after rollback" >&2
  exit 1
fi
grep -qx 'BACKEND_TAG=sha-abcdef1' "$ROLLBACK_APP/.env"
grep -qx 'FRONTEND_TAG=sha-1234567' "$ROLLBACK_APP/.env"
[[ "$(cat "$MOCK_SMOKE_COUNTER_PATH")" == "2" ]] || { echo "Expected smoke to run once for failed release and once for rollback" >&2; exit 1; }

echo "PASS: deployment success and automatic rollback paths are covered."
