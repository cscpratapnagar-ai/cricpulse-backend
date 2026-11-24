#!/usr/bin/env bash
set -Eeuo pipefail

fail() { echo "IMAGE ATTESTATION VERIFICATION FAILED: $*" >&2; exit 1; }
[[ "$#" -eq 1 ]] || fail "Expected exactly one immutable backend image tag"
IMAGE_TAG="$1"
[[ "$IMAGE_TAG" =~ ^sha-[0-9a-f]{7,40}$ ]] || fail "backend image tag must be immutable sha-*"
command -v gh >/dev/null 2>&1 || fail "GitHub CLI (gh) is required"
command -v docker >/dev/null 2>&1 || fail "Docker CLI is required to resolve the registry digest"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
GH_VERSION="$(gh version | awk 'NR == 1 { print $3 }')"
[[ -n "$GH_VERSION" ]] || fail "Unable to determine GitHub CLI version"
MIN_GH_VERSION="2.102.0"
[[ "$(printf '%s\n' "$MIN_GH_VERSION" "$GH_VERSION" | sort -V | head -n 1)" == "$MIN_GH_VERSION" ]] || fail "GitHub CLI >= $MIN_GH_VERSION is required for exact source-ref verification (found $GH_VERSION)"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required for GitHub attestation verification"
docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is required to resolve the registry manifest digest"

IMAGE_NAME="ghcr.io/cscpratapnagar-ai/cricpulse-backend"
IMAGE_TAG_URI="${IMAGE_NAME}:${IMAGE_TAG}"
SIGNER_WORKFLOW="cscpratapnagar-ai/cricpulse-backend/.github/workflows/publish-production-container.yml"
SOURCE_REF="refs/heads/main"

# Resolve the tag once, then verify and deploy the immutable digest rather than
# trusting the mutable registry tag for subsequent pulls.
DIGEST="$(docker buildx imagetools inspect --raw "$IMAGE_TAG_URI" | sha256sum | awk '{print $1}')"
[[ "$DIGEST" =~ ^[0-9a-f]{64}$ ]] || fail "Unable to resolve a valid sha256 registry manifest digest"
IMAGE_REF="${IMAGE_NAME}@sha256:${DIGEST}"

echo "Verifying signed SLSA provenance for oci://${IMAGE_REF} from ${SOURCE_REF}"
gh attestation verify "oci://${IMAGE_REF}" \
  --repo "cscpratapnagar-ai/cricpulse-backend" \
  --signer-workflow "$SIGNER_WORKFLOW" \
  --source-ref "$SOURCE_REF"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'image_digest=sha256:%s\nimage_ref=%s\n' "$DIGEST" "$IMAGE_REF" >> "$GITHUB_OUTPUT"
fi
echo "IMAGE_DIGEST=sha256:${DIGEST}"
echo "PASS: backend image digest is immutable and its signed provenance is valid for ${SOURCE_REF}"
