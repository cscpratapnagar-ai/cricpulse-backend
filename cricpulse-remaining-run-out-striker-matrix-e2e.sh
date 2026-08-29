#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:?ERROR: Set INNINGS_ID to a LIVE innings UUID}"

command -v curl >/dev/null || { echo "ERROR: curl is required"; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required"; exit 1; }

echo "============================================================"
echo " CRICPULSE REMAINING RUN_OUT STRIKER MATRIX E2E"
echo " Covers: striker dismissed with 1,2,3,4,5,6 runs"
echo "============================================================"

login="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
http="${login##*$'\n'}"; json="${login%$'\n'*}"
[[ "$http" == "200" ]] || { echo "[FAIL] Login HTTP $http"; echo "$json"; exit 1; }
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$json")"
[[ -n "$TOKEN" ]] || { echo "[FAIL] JWT missing"; exit 1; }
echo "[PASS] Authentication"

get_innings() {
  local r h b
  r="$(curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE_URL/scoring/innings/$INNINGS_ID")"
  h="${r##*$'\n'}"; b="${r%$'\n'*}"
  [[ "$h" == "200" ]] || { echo "[FAIL] Read innings HTTP $h"; echo "$b"; exit 1; }
  printf '%s' "$b"
}

find_new_batter() {
  local score="$1" match="$2" striker="$3" non="$4" xi team outids
  xi="$(curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE_URL/matches/$match/playing-xi")"
  local h="${xi##*$'\n'}"; xi="${xi%$'\n'*}"
  [[ "$h" == "200" ]] || { echo "[FAIL] Read Playing XI HTTP $h"; exit 1; }
  team="$(jq -r --arg id "$striker" 'first(.[] | select((.playerId // .player_id)==$id) | (.teamId // .team_id)) // empty' <<<"$xi")"
  outids="$(jq -c '[.batters[]? | select((.isOut // .is_out // .out // false)==true) | (.playerId // .player_id) | select(.!=null)]' <<<"$score")"
  jq -r --arg team "$team" --arg striker "$striker" --arg non "$non" --argjson out "$outids" '
    first(.[] | (.playerId // .player_id) as $id | (.teamId // .team_id) as $tid |
      select($tid==$team) | select($id!=$striker and $id!=$non) |
      select(($out | index($id))==null) | $id) // empty' <<<"$xi"
}

run_case() {
  local bat_runs="$1"
  local score match status legal runs wickets striker non bowler new over ball body response h result
  local after after_legal after_runs after_wickets after_striker after_non batter dismissal isout fow recent
  local expected_striker expected_non

  echo
  echo "============================================================"
  echo " CASE: RUN_OUT + $bat_runs RUN(S) + STRIKER DISMISSED"
  echo "============================================================"

  score="$(get_innings)"
  match="$(jq -r '.matchId // .match_id // empty' <<<"$score")"
  status="$(jq -r '.status // empty' <<<"$score")"
  legal="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$score")"
  runs="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$score")"
  wickets="$(jq -r '.wickets // 0' <<<"$score")"
  striker="$(jq -r '.strikerId // .striker_id // empty' <<<"$score")"
  non="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$score")"
  bowler="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$score")"

  [[ "$status" == "LIVE" ]] || { echo "[FAIL] Innings is not LIVE"; exit 1; }
  [[ "$wickets" -lt 9 ]] || { echo "[FAIL] Need fewer than 9 wickets before next case; create a fresh innings"; exit 1; }

  new="$(find_new_batter "$score" "$match" "$striker" "$non")"
  [[ -n "$new" && "$new" != "null" ]] || { echo "[FAIL] No available new batter"; exit 1; }

  over=$((legal / 6)); ball=$((legal % 6 + 1))
  echo "    before wickets=$wickets balls=$legal runs=$runs"
  echo "    striker=$striker"
  echo "    non-striker=$non"
  echo "    new-batter=$new"
  echo "    over=$over ball=$ball"

  body="$(jq -nc --arg innings "$INNINGS_ID" --arg striker "$striker" --arg non "$non" --arg bowler "$bowler" --arg dismissed "$striker" --arg newbat "$new" --argjson over "$over" --argjson ball "$ball" --argjson bat "$bat_runs" '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:$bat,extraRuns:0,extraType:null,wicketType:"RUN_OUT",dismissedPlayerId:$dismissed,newBatterId:$newbat}')"

  response="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")"
  h="${response##*$'\n'}"; result="${response%$'\n'*}"
  [[ "$h" == "200" ]] || { echo "[FAIL] Record RUN_OUT + $bat_runs runs HTTP $h"; echo "$result"; exit 1; }
  echo "[PASS] Delivery recorded"

  after="$(get_innings)"
  after_legal="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$after")"
  after_runs="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$after")"
  after_wickets="$(jq -r '.wickets // 0' <<<"$after")"
  after_striker="$(jq -r '.strikerId // .striker_id // empty' <<<"$after")"
  after_non="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$after")"

  [[ "$after_wickets" -eq $((wickets+1)) ]] || { echo "[FAIL] Wickets mismatch"; exit 1; }
  [[ "$after_legal" -eq $((legal+1)) ]] || { echo "[FAIL] Legal balls mismatch"; exit 1; }
  [[ "$after_runs" -eq $((runs+bat_runs)) ]] || { echo "[FAIL] Score mismatch"; exit 1; }
  echo "[PASS] Score + wicket + legal-ball state verified"

  if (( bat_runs % 2 == 0 )); then
    expected_striker="$new"; expected_non="$non"
  else
    expected_striker="$non"; expected_non="$new"
  fi
  if (( after_legal % 6 == 0 )); then
    local tmp="$expected_striker"
    expected_striker="$expected_non"
    expected_non="$tmp"
  fi

  [[ "$after_striker" == "$expected_striker" ]] || { echo "[FAIL] Striker position mismatch expected=$expected_striker actual=$after_striker"; exit 1; }
  [[ "$after_non" == "$expected_non" ]] || { echo "[FAIL] Non-striker position mismatch expected=$expected_non actual=$after_non"; exit 1; }
  echo "[PASS] Strike / crossing / over-end position verified"

  batter="$(jq -c --arg id "$striker" 'first(.batters[]? | select((.playerId // .player_id)==$id)) // empty' <<<"$after")"
  isout="$(jq -r '.out // .isOut // .is_out // false' <<<"$batter")"
  dismissal="$(jq -r '.dismissalType // .dismissal_type // empty' <<<"$batter")"
  fow="$(jq '.fallOfWickets // .fall_of_wickets // [] | length' <<<"$after")"
  recent="$(jq -r '.recentBalls[]? | select((.wicketType // .wicket_type)=="RUN_OUT") | (.wicketType // .wicket_type)' <<<"$after" | tail -n1)"

  [[ "$isout" == "true" ]] || { echo "[FAIL] Dismissed striker not OUT"; exit 1; }
  [[ "$dismissal" == "RUN_OUT" ]] || { echo "[FAIL] Dismissal type mismatch"; exit 1; }
  [[ "$fow" -ge $((wickets+1)) ]] || { echo "[FAIL] FOW missing"; exit 1; }
  [[ "$recent" == "RUN_OUT" ]] || { echo "[FAIL] Recent RUN_OUT missing"; exit 1; }
  echo "[PASS] Batter + dismissal + FOW + delivery data verified"
}

for r in 1 2 3 4 5 6; do
  run_case "$r"
done

echo
echo "============================================================"
echo " REMAINING RUN_OUT STRIKER MATRIX E2E PASSED"
echo " Cases passed: 1, 2, 3, 4, 5, 6 runs"
echo "============================================================"
echo "INNINGS_ID=$INNINGS_ID"
