#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/production-deployment.yml"
DEPLOY="$ROOT/scripts/production-deploy-with-rollback.sh"
COMPOSE="$ROOT/docker-compose.production.yml"

grep -Fq "workflow_dispatch:" "$WORKFLOW"
if grep -Eq '^  (push|pull_request|schedule):' "$WORKFLOW"; then
  echo "Production deployment must remain manual-only" >&2
  exit 1
fi
grep -Fq "environment: production" "$WORKFLOW"
grep -Fq "group: cricpulse-production-deployment" "$WORKFLOW"
grep -Fq "cancel-in-progress: false" "$WORKFLOW"
grep -Fq "PRODUCTION_APP_URL PRODUCTION_ACTUATOR_URL" "$WORKFLOW"
grep -Fq "PRODUCTION_API_BASE_URL" "$WORKFLOW"
grep -Fq "StrictHostKeyChecking=yes" "$WORKFLOW"
grep -Fq "printf '%s\\n'" "$WORKFLOW"
grep -Fq "steps.backend_image.outputs.image_digest" "$WORKFLOW"
grep -Fq "steps.frontend_image.outputs.image_digest" "$WORKFLOW"
grep -Fq 'sha256:[0-9a-f]{64}' "$WORKFLOW"
grep -Fq 'bash -s -- "$DEPLOY_APP_DIR" "$BACKEND_TAG" "$FRONTEND_TAG" "$BACKEND_DIGEST" "$FRONTEND_DIGEST"' "$WORKFLOW"
grep -Fq 'if: always()' "$WORKFLOW"
grep -Fq 'retention-days: 90' "$WORKFLOW"

grep -Fq 'current_running_image_ref backend ghcr.io/cscpratapnagar-ai/cricpulse-backend' "$DEPLOY"
grep -Fq 'current_running_image_ref frontend ghcr.io/cscpratapnagar-ai/cricpulse-ui' "$DEPLOY"
grep -Fq 'RepoDigests' "$DEPLOY"
grep -Fq 'refusing a tag-based rollback' "$DEPLOY"
grep -Fq 'OLD_BACKEND_IMAGE' "$DEPLOY"
grep -Fq 'OLD_FRONTEND_IMAGE' "$DEPLOY"
grep -Fq 'BACKEND_IMAGE=' "$DEPLOY"
grep -Fq 'FRONTEND_IMAGE=' "$DEPLOY"

grep -Fq 'BACKEND_IMAGE:-ghcr.io/cscpratapnagar-ai/cricpulse-backend:' "$COMPOSE"
grep -Fq 'FRONTEND_IMAGE:-ghcr.io/cscpratapnagar-ai/cricpulse-ui:' "$COMPOSE"

echo "PASS: production deployment remains manual, approval-environment scoped, digest-pinned, rollback-safe, and evidence-producing"
