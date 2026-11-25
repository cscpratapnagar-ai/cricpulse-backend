#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/staging-deployment.yml"

# workflow_dispatch values must enter shell steps through environment variables.
grep -Fq 'BACKEND_TAG: ${{ inputs.backend_tag }}' "$WORKFLOW"
grep -Fq 'FRONTEND_TAG: ${{ inputs.frontend_tag }}' "$WORKFLOW"
grep -Fq 'run: bash scripts/verify-production-image-attestation.sh "$BACKEND_TAG"' "$WORKFLOW"
grep -Fq 'run: bash scripts/verify-frontend-image-attestation.sh "$FRONTEND_TAG"' "$WORKFLOW"

# Reject GitHub expressions inside both inline and block-style shell run commands.
if awk '
  function indent(line, copy) {
    copy = line
    sub(/[^ ].*$/, "", copy)
    return length(copy)
  }
  /^[[:space:]]*run:/ {
    run_indent = indent($0)
    in_run = ($0 ~ /\|[[:space:]]*$/)
    if ($0 ~ /\$\{\{/) bad = 1
    next
  }
  in_run {
    if ($0 ~ /^[[:space:]]*$/) next
    current_indent = indent($0)
    if (current_indent <= run_indent) {
      in_run = 0
      next
    }
    if ($0 ~ /\$\{\{/) bad = 1
  }
  END { exit bad ? 0 : 1 }
' "$WORKFLOW"; then
  echo "Staging workflow interpolates a GitHub expression directly into a shell run block" >&2
  exit 1
fi

# Preserve the manual-only staging boundary and explicit approval environment.
grep -Fq 'workflow_dispatch:' "$WORKFLOW"
! grep -Eq '^  (push|pull_request|schedule):' "$WORKFLOW"
grep -Fq 'environment: staging' "$WORKFLOW"
grep -Fq 'StrictHostKeyChecking=yes' "$WORKFLOW"

echo "PASS: staging workflow_dispatch inputs are shell-safe and deployment remains manual/environment-gated"
