#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY="$ROOT/scripts/verify-frontend-image-attestation.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export GH_CALL_LOG="$TMP/gh-call.log"
export GITHUB_OUTPUT="$TMP/github-output"
export MOCK_MANIFEST='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1},"layers":[]}'

cat > "$TMP/bin/gh" <<'MOCK'
#!/usr/bin/env bash
if [[ "${1:-}" == "version" ]]; then
  echo "gh version ${MOCK_GH_VERSION:-2.102.0}"
  exit 0
fi
printf '%s\n' "$@" > "$GH_CALL_LOG"
exit "${MOCK_GH_EXIT:-0}"
MOCK
cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
if [[ "$*" == "buildx version" ]]; then
  echo "github.com/docker/buildx mock"
  exit 0
fi
if [[ "${1:-}" == "buildx" && "${2:-}" == "imagetools" && "${3:-}" == "inspect" && "${4:-}" == "--raw" ]]; then
  printf '%s' "$MOCK_MANIFEST"
  exit 0
fi
echo "Unexpected docker invocation: $*" >&2
exit 1
MOCK
chmod +x "$TMP/bin/gh" "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH"
export GH_TOKEN="ci-test-token"

EXPECTED_DIGEST="$(printf '%s' "$MOCK_MANIFEST" | sha256sum | awk '{print $1}')"
# Valid tags resolve to a registry digest; verification and deployment output must use that digest.
bash "$VERIFY" sha-abcdef123
grep -Fxq "oci://ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:$EXPECTED_DIGEST" "$GH_CALL_LOG"
grep -Fxq -- '--repo' "$GH_CALL_LOG"
grep -Fxq 'cscpratapnagar-ai/cricpulse-ui' "$GH_CALL_LOG"
grep -Fxq 'cscpratapnagar-ai/cricpulse-ui/.github/workflows/publish-production-container.yml' "$GH_CALL_LOG"
grep -Fxq -- '--source-ref' "$GH_CALL_LOG"
grep -Fxq 'refs/heads/main' "$GH_CALL_LOG"
grep -Fxq "image_digest=sha256:$EXPECTED_DIGEST" "$GITHUB_OUTPUT"
grep -Fxq "image_ref=ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:$EXPECTED_DIGEST" "$GITHUB_OUTPUT"

# Older GitHub CLI releases must be rejected before registry or attestation verification.
rm -f "$GH_CALL_LOG" "$GITHUB_OUTPUT"
export MOCK_GH_VERSION=2.101.0
if bash "$VERIFY" sha-abcdef123; then
  echo "Verifier unexpectedly accepted an outdated GitHub CLI" >&2
  exit 1
fi
[[ ! -e "$GH_CALL_LOG" ]] || { echo "Attestation verification ran with an outdated GitHub CLI" >&2; exit 1; }
unset MOCK_GH_VERSION

# Mutable tags must fail before any registry lookup.
if bash "$VERIFY" latest; then
  echo "Verifier unexpectedly accepted a mutable image tag" >&2
  exit 1
fi

# A cryptographic verification failure must block deployment and produce no digest output.
export MOCK_GH_EXIT=1
if bash "$VERIFY" sha-abcdef123; then
  echo "Verifier unexpectedly ignored a failed attestation check" >&2
  exit 1
fi
[[ ! -s "$GITHUB_OUTPUT" ]] || { echo "Failed verification exported a deployable digest" >&2; exit 1; }

echo "PASS: frontend tag resolution, digest-bound signer/source verification, and fail-closed output"
