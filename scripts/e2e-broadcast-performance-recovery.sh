#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
: "${MATCH_ID:?MATCH_ID is required}"
: "${API_PID_FILE:?API_PID_FILE is required}"
: "${API_LOG_FILE:?API_LOG_FILE is required}"
: "${GITHUB_STEP_SUMMARY:?GITHUB_STEP_SUMMARY is required}"

tmp="$(mktemp -d)"
worker_pids=()
cleanup() { rm -rf "$tmp"; }
trap cleanup EXIT

health_url="$BASE_URL/public/broadcast-health"
state_url="$BASE_URL/public/matches/$MATCH_ID/broadcast-state"

echo "[1/4] Verify broadcast readiness before load"
curl -fsS --max-time 5 "$health_url" > "$tmp/health-before.json"
jq -e '.status == "UP"' "$tmp/health-before.json" >/dev/null

echo "[2/4] Exercise public broadcast-state under concurrent read load"
# 8 independent clients × 8 requests = 64 real HTTP reads against the
# PostgreSQL-backed broadcast-state projection. No writes or fixture mutation.
request_worker() {
  local worker="$1" request response status seconds
  for request in {1..8}; do
    response="$tmp/body-$worker-$request.json"
    result="$(curl -sS --max-time 10 -o "$response" -w '%{http_code} %{time_total}' "$state_url")" || {
      echo "worker=$worker request=$request transport failure" >&2
      return 1
    }
    read -r status seconds <<< "$result"
    if [[ "$status" != "200" ]]; then
      echo "worker=$worker request=$request returned HTTP $status" >&2
      cat "$response" >&2
      return 1
    fi
    if ! jq -e --arg match "$MATCH_ID" '.match.id == $match and (.innings | type == "array")' "$response" >/dev/null; then
      echo "worker=$worker request=$request returned an invalid broadcast-state payload" >&2
      cat "$response" >&2
      return 1
    fi
    awk -v seconds="$seconds" 'BEGIN { printf "%.3f\\n", seconds * 1000 }' >> "$tmp/latencies-ms.txt"
  done
}
for worker in {1..8}; do
  request_worker "$worker" &
  worker_pids+=("$!")
done
worker_failure=0
for pid in "${worker_pids[@]}"; do
  if ! wait "$pid"; then worker_failure=1; fi
done
if [[ "$worker_failure" != "0" ]]; then
  echo "FAIL: one or more concurrent broadcast-state clients failed" >&2
  exit 1
fi

request_count="$(wc -l < "$tmp/latencies-ms.txt" | tr -d ' ')"
[[ "$request_count" == "64" ]] || { echo "Expected 64 measured requests, got $request_count" >&2; exit 1; }
sort -n "$tmp/latencies-ms.txt" > "$tmp/latencies-sorted.txt"
p95_index=$(( (request_count * 95 + 99) / 100 ))
p95_ms="$(sed -n "${p95_index}p" "$tmp/latencies-sorted.txt")"
max_ms="$(tail -n 1 "$tmp/latencies-sorted.txt")"
echo "Measured broadcast-state requests=$request_count p95_ms=$p95_ms max_ms=$max_ms"
# Keep this a coarse regression guard, not a production SLO claim. The shared
# GitHub runner is noisy; a 5s p95 catches hangs and severe query regressions.
if awk -v p95="$p95_ms" 'BEGIN { exit !(p95 > 5000) }'; then
  echo "FAIL: broadcast-state p95 exceeded the 5000ms CI regression ceiling" >&2
  exit 1
fi

echo "[3/4] Simulate API/network interruption and confirm readiness goes DOWN"
api_pid="$(cat "$API_PID_FILE")"
if [[ -z "$api_pid" ]] || ! kill -0 "$api_pid" 2>/dev/null; then
  echo "API PID file does not point to a running process" >&2
  exit 1
fi
outage_started_ms="$(date +%s%3N)"
kill -TERM "$api_pid"
api_down=0
for attempt in {1..40}; do
  if ! curl -fsS --max-time 1 "$health_url" >/dev/null 2>&1; then
    api_down=1
    break
  fi
  sleep 0.25
done
if [[ "$api_down" != "1" ]]; then
  echo "FAIL: API did not become unavailable after process termination" >&2
  exit 1
fi

echo "[4/4] Restart API and verify readiness plus broadcast snapshot recovery"
jar_path="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
if [[ -z "$jar_path" ]]; then
  echo "Could not locate the packaged application JAR" >&2
  exit 1
fi
nohup java -jar "$jar_path" > "$API_LOG_FILE" 2>&1 &
restarted_pid=$!
echo "$restarted_pid" > "$API_PID_FILE"
api_recovered=0
for attempt in {1..60}; do
  if curl -fsS --max-time 2 "$health_url" > "$tmp/health-after.json" 2>/dev/null &&
     jq -e '.status == "UP"' "$tmp/health-after.json" >/dev/null; then
    api_recovered=1
    break
  fi
  if ! kill -0 "$restarted_pid" 2>/dev/null; then
    echo "Restarted API process exited before becoming healthy" >&2
    cat "$API_LOG_FILE" >&2 || true
    exit 1
  fi
  sleep 2
done
if [[ "$api_recovered" != "1" ]]; then
  echo "FAIL: API did not recover within 120 seconds" >&2
  cat "$API_LOG_FILE" >&2 || true
  exit 1
fi
curl -fsS --max-time 10 "$state_url" > "$tmp/state-after-restart.json"
jq -e --arg match "$MATCH_ID" '.match.id == $match and (.innings | type == "array")' "$tmp/state-after-restart.json" >/dev/null
outage_ended_ms="$(date +%s%3N)"
recovery_ms=$((outage_ended_ms - outage_started_ms))

{
  echo "## Broadcast performance and recovery E2E"
  echo
  echo "- Concurrent public broadcast-state requests: $request_count (8 clients × 8 requests)"
  echo "- Broadcast-state p95 latency: ${p95_ms} ms"
  echo "- Broadcast-state max latency: ${max_ms} ms"
  echo "- API process interruption to recovered readiness + state snapshot: ${recovery_ms} ms"
  echo "- Public health: DOWN observed during interruption; UP after restart"
  echo "- Post-restart public broadcast-state snapshot: verified for match $MATCH_ID"
  echo
  echo "This is a CI regression benchmark on a shared runner, not a production latency SLO or physical network certification."
} >> "$GITHUB_STEP_SUMMARY"

echo "PASS: concurrent broadcast reads, interruption detection, API restart and state recovery"
