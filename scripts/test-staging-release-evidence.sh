#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export GITHUB_REPOSITORY="cscpratapnagar-ai/cricpulse-backend"
export GITHUB_RUN_ID=123456789
export GITHUB_RUN_ATTEMPT=2
export GITHUB_SHA=0123456789abcdef0123456789abcdef01234567
export GITHUB_REF=refs/heads/main
export GITHUB_STEP_SUMMARY="$TMP/summary.md"
export EVIDENCE_FILE="$TMP/evidence/report.md"
export BACKEND_TAG=sha-abcdef1234567
export FRONTEND_TAG=sha-1234567abcdef
export BACKEND_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export FRONTEND_DIGEST=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
export BACKEND_VERIFY_OUTCOME=success
export FRONTEND_VERIFY_OUTCOME=success
export PREFLIGHT_OUTCOME=success
export DEPLOY_OUTCOME=success
export WORKFLOW_STATUS=success

bash "$ROOT/scripts/write-staging-release-evidence.sh"
grep -Fq "DEPLOYMENT STEP SUCCEEDED" "$EVIDENCE_FILE"
grep -Fq "$BACKEND_DIGEST" "$EVIDENCE_FILE"
grep -Fq "$FRONTEND_DIGEST" "$GITHUB_STEP_SUMMARY"
grep -Fq "attempt 2" "$EVIDENCE_FILE"

# Failed deployment must not be misreported as a successful rollback.
export DEPLOY_OUTCOME=failure
export WORKFLOW_STATUS=failure
bash "$ROOT/scripts/write-staging-release-evidence.sh"
grep -Fq "inspect the deployment log to distinguish successful rollback" "$EVIDENCE_FILE"
! grep -Fq "ROLLBACK PASS" "$EVIDENCE_FILE"

# Invalid image metadata is safely omitted rather than copied into the report.
export BACKEND_DIGEST="sha256:invalid-secret-looking-value"
bash "$ROOT/scripts/write-staging-release-evidence.sh"
grep -Fq "Not available (validation failed)" "$EVIDENCE_FILE"
! grep -Fq "invalid-secret-looking-value" "$EVIDENCE_FILE"

echo "PASS: staging evidence success, failure semantics, and metadata validation"
