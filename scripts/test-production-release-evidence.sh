#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

export GITHUB_REPOSITORY="cscpratapnagar-ai/cricpulse-backend"
export GITHUB_RUN_ID=987654321
export GITHUB_RUN_ATTEMPT=3
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

# These values must never be copied into the report.
export DEPLOY_HOST=production.internal.example
export DEPLOY_APP_DIR=/srv/cricpulse/production
export BACKUP_FILE=/secure/backups/prod.dump
export DEPLOY_SSH_PRIVATE_KEY="PRIVATE-KEY-MUST-NOT-APPEAR"
export PRODUCTION_API_BASE_URL=https://api.private.example

bash "$ROOT/scripts/write-production-release-evidence.sh"
grep -Fq "DEPLOYMENT STEP SUCCEEDED" "$EVIDENCE_FILE"
grep -Fq "$BACKEND_DIGEST" "$EVIDENCE_FILE"
grep -Fq "$FRONTEND_DIGEST" "$GITHUB_STEP_SUMMARY"
grep -Fq "attempt 3" "$EVIDENCE_FILE"
for secret in "$DEPLOY_HOST" "$DEPLOY_APP_DIR" "$BACKUP_FILE" "$DEPLOY_SSH_PRIVATE_KEY" "$PRODUCTION_API_BASE_URL"; do
  ! grep -Fq "$secret" "$EVIDENCE_FILE"
done

# A failed deployment is not misrepresented as a successful rollback.
export DEPLOY_OUTCOME=failure
export WORKFLOW_STATUS=failure
bash "$ROOT/scripts/write-production-release-evidence.sh"
grep -Fq "distinguish successful rollback from rollback failure" "$EVIDENCE_FILE"
! grep -Fq "ROLLBACK PASS" "$EVIDENCE_FILE"

# Invalid metadata is safely omitted instead of being copied into evidence.
export BACKEND_DIGEST="sha256:invalid-value"
bash "$ROOT/scripts/write-production-release-evidence.sh"
grep -Fq "Not available (validation failed)" "$EVIDENCE_FILE"
! grep -Fq "invalid-value" "$EVIDENCE_FILE"

echo "PASS: production evidence success, failure semantics, secret exclusion, and metadata validation"
