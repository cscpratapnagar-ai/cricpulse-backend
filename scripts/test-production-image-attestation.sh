#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY="$ROOT/scripts/verify-production-image-attestation.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export GH_CALL_LOG="$TMP/gh-call.log"

cat > "$TMP/bin/gh" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$GH_CALL_LOG"
exit "${MOCK_GH_EXIT:-0}"
MOCK
chmod +x "$TMP/bin/gh"
export PATH="$TMP/bin:$PATH"
export GH_TOKEN="ci-test-token"

# Valid immutable tags must call gh with the exact image and trusted workflow identity.
bash "$VERIFY" sha-abcdef123
grep -Fxq 'oci://ghcr.io/cscpratapnagar-ai/cricpulse-backend:sha-abcdef123' "$GH_CALL_LOG"
grep -Fxq -- '--repo' "$GH_CALL_LOG"
grep -Fxq 'cscpratapnagar-ai/cricpulse-backend/.github/workflows/publish-production-container.yml' "$GH_CALL_LOG"

# Mutable or malformed tags must fail before invoking gh.
rm -f "$GH_CALL_LOG"
if bash "$VERIFY" latest; then
  echo "Verifier unexpectedly accepted a mutable image tag" >&2
  exit 1
fi
[[ ! -e "$GH_CALL_LOG" ]] || { echo "gh was called for an invalid tag" >&2; exit 1; }

# A cryptographic verification failure must block deployment (non-zero is preserved).
export MOCK_GH_EXIT=1
if bash "$VERIFY" sha-abcdef123; then
  echo "Verifier unexpectedly ignored a failed gh attestation check" >&2
  exit 1
fi

echo "PASS: immutable tag validation, signer pinning, and fail-closed attestation verification"
