#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY="$ROOT/scripts/verify-frontend-image-attestation.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export GH_CALL_LOG="$TMP/gh-call.log"

cat > "$TMP/bin/gh" <<'MOCK'
#!/usr/bin/env bash
if [[ "${1:-}" == "version" ]]; then
  echo "gh version ${MOCK_GH_VERSION:-2.102.0}"
  exit 0
fi
printf '%s\n' "$@" > "$GH_CALL_LOG"
exit "${MOCK_GH_EXIT:-0}"
MOCK
chmod +x "$TMP/bin/gh"
export PATH="$TMP/bin:$PATH"
export GH_TOKEN="ci-test-token"

# Valid immutable tags must pin the exact frontend repository, signer, and staging source branch.
bash "$VERIFY" sha-abcdef123
grep -Fxq 'oci://ghcr.io/cscpratapnagar-ai/cricpulse-ui:sha-abcdef123' "$GH_CALL_LOG"
grep -Fxq -- '--repo' "$GH_CALL_LOG"
grep -Fxq 'cscpratapnagar-ai/cricpulse-ui' "$GH_CALL_LOG"
grep -Fxq 'cscpratapnagar-ai/cricpulse-ui/.github/workflows/publish-production-container.yml' "$GH_CALL_LOG"
grep -Fxq -- '--source-ref' "$GH_CALL_LOG"
grep -Fxq 'refs/heads/develop' "$GH_CALL_LOG"

# Older GitHub CLI releases have a case-insensitive source-ref policy bug and must be rejected.
rm -f "$GH_CALL_LOG"
export MOCK_GH_VERSION=2.101.0
if bash "$VERIFY" sha-abcdef123; then
  echo "Verifier unexpectedly accepted an outdated GitHub CLI" >&2
  exit 1
fi
[[ ! -e "$GH_CALL_LOG" ]] || { echo "Attestation verification ran with an outdated GitHub CLI" >&2; exit 1; }
unset MOCK_GH_VERSION

# Mutable or malformed tags must fail before invoking gh.
rm -f "$GH_CALL_LOG"
if bash "$VERIFY" latest; then
  echo "Verifier unexpectedly accepted a mutable frontend image tag" >&2
  exit 1
fi
[[ ! -e "$GH_CALL_LOG" ]] || { echo "gh was called for an invalid frontend tag" >&2; exit 1; }

# A cryptographic verification failure must block deployment (non-zero is preserved).
export MOCK_GH_EXIT=1
if bash "$VERIFY" sha-abcdef123; then
  echo "Verifier unexpectedly ignored a failed frontend attestation check" >&2
  exit 1
fi

echo "PASS: frontend immutable tag, trusted signer, develop source-ref, and fail-closed verification"
