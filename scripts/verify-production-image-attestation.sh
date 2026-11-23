#!/usr/bin/env bash
set -Eeuo pipefail

fail() { echo "IMAGE ATTESTATION VERIFICATION FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 1 ]] || fail "Expected exactly one immutable backend image tag"
IMAGE_TAG="$1"
[[ "$IMAGE_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Backend image tag must be immutable sha-*"
command -v gh >/dev/null 2>&1 || fail "GitHub CLI (gh) is required"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required for GitHub attestation verification"

IMAGE_URI="oci://ghcr.io/cscpratapnagar-ai/cricpulse-backend:${IMAGE_TAG}"
SIGNER_WORKFLOW="cscpratapnagar-ai/cricpulse-backend/.github/workflows/publish-production-container.yml"

echo "Verifying signed SLSA provenance for ${IMAGE_URI}"
gh attestation verify "$IMAGE_URI" \
  --repo cscpratapnagar-ai/cricpulse-backend \
  --signer-workflow "$SIGNER_WORKFLOW"
echo "PASS: backend image provenance is valid and signed by the trusted publish workflow"
