#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { echo "PRODUCTION EVIDENCE GENERATION FAILED: $*" >&2; exit 1; }
valid_outcome() {
  case "$1" in success|failure|cancelled|skipped|unknown) return 0 ;; *) return 1 ;; esac
}
safe_value() {
  local value="$1" pattern="$2"
  [[ "$value" =~ $pattern ]] && printf '%s' "$value" || printf '%s' "Not available (validation failed)"
}

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${GITHUB_RUN_ATTEMPT:?GITHUB_RUN_ATTEMPT is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_REF:?GITHUB_REF is required}"
: "${GITHUB_STEP_SUMMARY:?GITHUB_STEP_SUMMARY is required}"
: "${EVIDENCE_FILE:?EVIDENCE_FILE is required}"

[[ "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail "Invalid repository identifier"
[[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ && "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]] || fail "Invalid workflow run identifier"
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "Invalid source commit SHA"
[[ "$GITHUB_REF" =~ ^refs/(heads|tags)/[A-Za-z0-9_./-]+$ ]] || fail "Invalid source ref"

for name in BACKEND_VERIFY_OUTCOME FRONTEND_VERIFY_OUTCOME PREFLIGHT_OUTCOME DEPLOY_OUTCOME WORKFLOW_STATUS; do
  value="${!name:-unknown}"
  valid_outcome "$value" || fail "Invalid step outcome for $name"
done

backend_tag="$(safe_value "${BACKEND_TAG:-}" '^sha-[0-9a-f]{7,40}$')"
frontend_tag="$(safe_value "${FRONTEND_TAG:-}" '^sha-[0-9a-f]{7,40}$')"
backend_digest="$(safe_value "${BACKEND_DIGEST:-}" '^sha256:[0-9a-f]{64}
if [[ "$deploy_outcome" == success ]]; then
  release_result="DEPLOYMENT STEP SUCCEEDED"
elif [[ "$deploy_outcome" == failure ]]; then
  release_result="DEPLOYMENT STEP FAILED — inspect deployment logs to distinguish successful rollback from rollback failure"
elif [[ "$deploy_outcome" == skipped ]]; then
  release_result="DEPLOYMENT NOT STARTED"
else
  release_result="DEPLOYMENT ${deploy_outcome^^}"
fi

mkdir -p "$(dirname "$EVIDENCE_FILE")"
cat > "$EVIDENCE_FILE" <<REPORT
# CricPulse production release evidence

- Result: **$release_result**
- Repository: `$GITHUB_REPOSITORY`
- Workflow run: [#$GITHUB_RUN_ID](https://github.com/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID) (attempt $GITHUB_RUN_ATTEMPT)
- Source commit: `$GITHUB_SHA`
- Source ref: `$GITHUB_REF`
- Backend source tag: `$backend_tag`
- Frontend source tag: `$frontend_tag`
- Backend verified digest: `$backend_digest`
- Frontend verified digest: `$frontend_digest`

## Gate outcomes

| Gate | Outcome |
|---|---|
| Production release input and secret preflight | $preflight_outcome |
| Backend image provenance | $backend_verify_outcome |
| Frontend image provenance | $frontend_verify_outcome |
| Deployment and production smoke checks | $deploy_outcome |
| Overall workflow | $workflow_status |

## Interpretation

A successful deployment step means the remote deployment script completed its configured production smoke checks. A failed deployment step may have completed an automatic rollback; inspect the deployment log for the explicit `ROLLBACK PASS` or `CRITICAL` marker. This report never infers rollback success from a non-zero exit code.

This report intentionally excludes SSH credentials, environment secrets, hostnames, deployment paths, backup paths, and endpoint URLs.
REPORT

cat "$EVIDENCE_FILE" >> "$GITHUB_STEP_SUMMARY"
chmod 600 "$EVIDENCE_FILE"
echo "PASS: sanitized production release evidence written"
)"
frontend_digest="$(safe_value "${FRONTEND_DIGEST:-}" '^sha256:[0-9a-f]{64}
if [[ "$DEPLOY_OUTCOME" == success ]]; then
  release_result="DEPLOYMENT STEP SUCCEEDED"
elif [[ "$DEPLOY_OUTCOME" == failure ]]; then
  release_result="DEPLOYMENT STEP FAILED — inspect deployment logs to distinguish successful rollback from rollback failure"
elif [[ "$DEPLOY_OUTCOME" == skipped ]]; then
  release_result="DEPLOYMENT NOT STARTED"
else
  release_result="DEPLOYMENT ${DEPLOY_OUTCOME^^}"
fi

mkdir -p "$(dirname "$EVIDENCE_FILE")"
cat > "$EVIDENCE_FILE" <<REPORT
# CricPulse production release evidence

- Result: **$release_result**
- Repository: `$GITHUB_REPOSITORY`
- Workflow run: [#$GITHUB_RUN_ID](https://github.com/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID) (attempt $GITHUB_RUN_ATTEMPT)
- Source commit: `$GITHUB_SHA`
- Source ref: `$GITHUB_REF`
- Backend source tag: `$backend_tag`
- Frontend source tag: `$frontend_tag`
- Backend verified digest: `$backend_digest`
- Frontend verified digest: `$frontend_digest`

## Gate outcomes

| Gate | Outcome |
|---|---|
| Production release input and secret preflight | ${PREFLIGHT_OUTCOME:-unknown} |
| Backend image provenance | $BACKEND_VERIFY_OUTCOME |
| Frontend image provenance | $FRONTEND_VERIFY_OUTCOME |
| Deployment and production smoke checks | $DEPLOY_OUTCOME |
| Overall workflow | $WORKFLOW_STATUS |

## Interpretation

A successful deployment step means the remote deployment script completed its configured production smoke checks. A failed deployment step may have completed an automatic rollback; inspect the deployment log for the explicit `ROLLBACK PASS` or `CRITICAL` marker. This report never infers rollback success from a non-zero exit code.

This report intentionally excludes SSH credentials, environment secrets, hostnames, deployment paths, backup paths, and endpoint URLs.
REPORT

cat "$EVIDENCE_FILE" >> "$GITHUB_STEP_SUMMARY"
chmod 600 "$EVIDENCE_FILE"
echo "PASS: sanitized production release evidence written"
)"
backend_verify_outcome="${BACKEND_VERIFY_OUTCOME:-unknown}"
frontend_verify_outcome="${FRONTEND_VERIFY_OUTCOME:-unknown}"
preflight_outcome="${PREFLIGHT_OUTCOME:-unknown}"
deploy_outcome="${DEPLOY_OUTCOME:-unknown}"
workflow_status="${WORKFLOW_STATUS:-unknown}"

if [[ "$DEPLOY_OUTCOME" == success ]]; then
  release_result="DEPLOYMENT STEP SUCCEEDED"
elif [[ "$DEPLOY_OUTCOME" == failure ]]; then
  release_result="DEPLOYMENT STEP FAILED — inspect deployment logs to distinguish successful rollback from rollback failure"
elif [[ "$DEPLOY_OUTCOME" == skipped ]]; then
  release_result="DEPLOYMENT NOT STARTED"
else
  release_result="DEPLOYMENT ${DEPLOY_OUTCOME^^}"
fi

mkdir -p "$(dirname "$EVIDENCE_FILE")"
cat > "$EVIDENCE_FILE" <<REPORT
# CricPulse production release evidence

- Result: **$release_result**
- Repository: `$GITHUB_REPOSITORY`
- Workflow run: [#$GITHUB_RUN_ID](https://github.com/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID) (attempt $GITHUB_RUN_ATTEMPT)
- Source commit: `$GITHUB_SHA`
- Source ref: `$GITHUB_REF`
- Backend source tag: `$backend_tag`
- Frontend source tag: `$frontend_tag`
- Backend verified digest: `$backend_digest`
- Frontend verified digest: `$frontend_digest`

## Gate outcomes

| Gate | Outcome |
|---|---|
| Production release input and secret preflight | ${PREFLIGHT_OUTCOME:-unknown} |
| Backend image provenance | $BACKEND_VERIFY_OUTCOME |
| Frontend image provenance | $FRONTEND_VERIFY_OUTCOME |
| Deployment and production smoke checks | $DEPLOY_OUTCOME |
| Overall workflow | $WORKFLOW_STATUS |

## Interpretation

A successful deployment step means the remote deployment script completed its configured production smoke checks. A failed deployment step may have completed an automatic rollback; inspect the deployment log for the explicit `ROLLBACK PASS` or `CRITICAL` marker. This report never infers rollback success from a non-zero exit code.

This report intentionally excludes SSH credentials, environment secrets, hostnames, deployment paths, backup paths, and endpoint URLs.
REPORT

cat "$EVIDENCE_FILE" >> "$GITHUB_STEP_SUMMARY"
chmod 600 "$EVIDENCE_FILE"
echo "PASS: sanitized production release evidence written"
