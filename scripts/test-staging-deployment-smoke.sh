#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export CURL_CALL_LOG="$TMP/curl.log"
cat > "$TMP/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$CURL_CALL_LOG"
[[ " $* " == *" --proto =https "* ]] || { echo "curl request is missing HTTPS protocol restriction" >&2; exit 91; }
[[ " $* " == *" --proto-redir =https "* ]] || { echo "curl request is missing HTTPS redirect restriction" >&2; exit 92; }
[[ " $* " == *" --max-redirs 5 "* ]] || { echo "curl request is missing redirect limit" >&2; exit 93; }
output=""
while (($#)); do
  if [[ "$1" == "-o" ]]; then output="$2"; shift 2; continue; fi
  shift
done
[[ -n "$output" ]] || exit 94
case "$output" in
  *.html) printf '<!doctype html><html><body>staging</body></html>\n' > "$output" ;;
  *) printf '{"status":"UP","match":{"id":"11111111-1111-1111-1111-111111111111"},"innings":[]}' > "$output" ;;
esac
printf '200'
MOCK
cat > "$TMP/bin/jq" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod +x "$TMP/bin/curl" "$TMP/bin/jq"
export PATH="$TMP/bin:$PATH"
export API_BASE_URL="https://api.staging.example.test"
export APP_URL="https://staging.example.test"
export ACTUATOR_URL="https://api.staging.example.test/actuator/health/readiness"
export MATCH_ID="11111111-1111-1111-1111-111111111111"
bash "$ROOT/scripts/staging-deployment-smoke.sh"
[[ "$(wc -l < "$CURL_CALL_LOG" | tr -d ' ')" == 4 ]] || { echo "Expected four HTTPS-only smoke requests including match snapshot" >&2; exit 1; }
if API_BASE_URL="http://api.staging.example.test" bash "$ROOT/scripts/staging-deployment-smoke.sh"; then
  echo "Expected non-HTTPS staging endpoint to be rejected" >&2
  exit 1
fi
echo "PASS: every staging smoke request pins HTTPS for initial and redirected connections"
