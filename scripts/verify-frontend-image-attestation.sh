#!/usr/bin/env bash
set -Eeuo pipefail

fail() { echo "IMAGE ATTESTATION VERIFICATION FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 1 ]] || fail "Expected exactly one immutable frontend image tag"
IMAGE_TAG="$1"
[[ "$IMAGE_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "Frontend image tag must be immutable sha-*"
command -v gh >/dev/null 2>&1 || fail "GitHub CLI (gh) is required"
GH_VERSION="$(gh version | awk 'NR == 1 { print $3 }')"
[[ -n "$GH_VERSION" ]] || fail "Unable to determine GitHub CLI version"
MIN_GH_VERSION="2.102.0"
[[ "$(printf '%s\\n' "$MIN_GH_VERSION" "$GH_VERSION" | sort -V | head -n 1)" == "$MIN_GH_VERSION" ]] || fail "GitHub CLI >= $MIN_GH_VERSION is required for exact source-ref verification (found $GH_VERSION)"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required for GitHub attestation verification"

IMAGE_URI="oci://ghcr.io/cscpratapnagar-ai/cricpulse-ui:${IMAGE_TAG}"
SIGNER_WORKFLOW="cscpratapnagar-ai/cricpulse-ui/.github/workflows/publish-production-container.yml"
SOURCE_REF="refs/heads/develop"

echo "Verifying signed SLSA provenance for ${IMAGE_URI} from ${SOURCE_REF}"
gh attestation verify "${IMAGE_URI}" \
  --repo cscpratapnagar-ai/cricpulse-ui \
  --signer-workflow "${SIGNER_WORKFLOW}" \
  --source-ref "${SOURCE_REF}"
echo "PASS: frontend image provenance is valid, signed by the trusted publish workflow, and built from develop"
