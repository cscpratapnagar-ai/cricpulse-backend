#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
REQUESTS="${REQUESTS:-30}"
P95_LIMIT_MS="${P95_LIMIT_MS:-750}"

[[ -n "$EMAIL" && -n "$PASSWORD" ]] || { echo "ERROR: EMAIL and PASSWORD are required" >&2; exit 1; }
command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

LOGIN="$(curl -sS -f -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN")"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || { echo "ERROR: login token missing" >&2; exit 1; }

run_benchmark() {
  local label="$1" url="$2" i elapsed status
  : > "$TMP_DIR/$label"
  for ((i=1; i<=REQUESTS; i++)); do
    elapsed="$(curl -sS -o /dev/null -w '%{time_total}' -H "Authorization: Bearer $TOKEN" "$url")"
    awk -v seconds="$elapsed" 'BEGIN { printf "%.3f\n", seconds * 1000 }' >> "$TMP_DIR/$label"
  done
  status="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$url")"
  [[ "$status" == "200" ]] || { echo "FAIL: $label returned HTTP $status" >&2; exit 1; }
  local p95 avg
  p95="$(sort -n "$TMP_DIR/$label" | awk -v n="$REQUESTS" 'NR == int(n*0.95+0.999) { print; exit }')"
  avg="$(awk '{sum += $1} END { if (NR) printf "%.1f", sum/NR; }' "$TMP_DIR/$label")"
  echo "PERF $label requests=$REQUESTS avg_ms=$avg p95_ms=$p95"
  awk -v p95="$p95" -v limit="$P95_LIMIT_MS" 'BEGIN { exit !(p95 <= limit) }' || {
    echo "FAIL: $label p95 ${p95}ms exceeds ${P95_LIMIT_MS}ms" >&2
    exit 1
  }
}

run_benchmark "matches" "$BASE_URL/matches"
run_benchmark "teams-mine" "$BASE_URL/teams/mine"
echo "PASS: authenticated access query performance smoke passed."
