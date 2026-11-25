# CricPulse deployment release acceptance

Target date: **15 December 2026**

This checklist separates repository/CI evidence from actions that require the real staging host, operator workstation, DNS/TLS, and GitHub Environment configuration. A green CI run is not a live deployment certification.

## Gate 1 — infrastructure access and isolation

- [ ] Create the backend GitHub Environment named `staging`.
- [ ] Configure staging secrets: `STAGING_DEPLOY_HOST`, `STAGING_DEPLOY_USER`, `STAGING_DEPLOY_SSH_PRIVATE_KEY`, `STAGING_DEPLOY_SSH_KNOWN_HOSTS`, `STAGING_DEPLOY_APP_DIR`, `STAGING_API_BASE_URL`, `STAGING_APP_URL`, `STAGING_ACTUATOR_URL`.
- [ ] Provision a dedicated staging host/path ending in `/staging`; do not reuse production database volumes or secrets.
- [ ] Confirm Docker Engine, Compose, GHCR read access, HTTPS routing, WebSocket upgrade routing, and pinned SSH host keys.
- [ ] Confirm staging `.env` is owner-only (mode 0600) and contains strong staging-only database/JWT secrets.
- [ ] Confirm a recent staging backup can be restored in an isolated disposable PostgreSQL 16 database; the staging host preflight now runs this rehearsal automatically and blocks deployment on restore failure.

## Gate 2 — staging deployment and application certification

- [ ] Publish backend image from backend `release` and frontend image from frontend `release`; record immutable tags and verified digests.
- [ ] Run **Staging Deployment** with the two image tags and a verified staging backup path.
- [ ] Save the 90-day staging release evidence artifact and verify API readiness, database-backed broadcast health, frontend HTML, and match snapshot.
- [ ] Certify scorer and public viewer on two separate devices/browser profiles.
- [ ] Test scorer lease conflict/recovery, duplicate submission/idempotency, offline queue reconciliation, and reconnect after API restart.
- [ ] Test OBS Browser Source, OBS WebSocket connect/disconnect/reconnect/scene switching, and overlay recovery.
- [ ] Test vMix browser input, transparency, sizing, score changes, and recovery.
- [ ] Run a representative match-length soak; record latency, reconnect time, CPU/memory, and any missed/duplicate events.
- [ ] Rehearse staging rollback and attach the evidence.

## Gate 3 — production readiness (approval required)

- [ ] Create the backend GitHub Environment named `production` and configure required reviewers/branch restrictions.
- [ ] Configure production secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_PRIVATE_KEY`, `DEPLOY_SSH_KNOWN_HOSTS`, `DEPLOY_APP_DIR`, `PRODUCTION_API_BASE_URL`, `PRODUCTION_APP_URL`, `PRODUCTION_ACTUATOR_URL`.
- [ ] Provision production-only secrets in the host `.env`; require owner ownership and mode 0600.
- [ ] Verify encrypted/off-host backup retention and complete a recent restore rehearsal.
- [ ] Confirm the currently running backend and frontend containers each expose exactly one GHCR RepoDigest; the production workflow deliberately fails closed if it cannot capture exact rollback digests.
- [ ] Confirm database migrations remain backward-compatible with the currently deployed application version.
- [ ] Review the exact backend/frontend source commits, image attestations, image digests, backup checksum, and change window.
- [ ] Obtain explicit production release approval before manually dispatching **Production Deployment**.
- [ ] Verify the production evidence artifact, frontend root, public broadcast health, private Actuator readiness, and optional match snapshot.
- [ ] Confirm rollback target digests and operator contact/escalation path.

## Gate 4 — final sign-off

- [ ] No unresolved critical/high security or data-integrity issue.
- [ ] Staging manual certification evidence is attached and reviewed.
- [ ] Production deployment evidence is attached and reviewed.
- [ ] OBS/vMix live output is confirmed by the operator.
- [ ] Rollback readiness and backup restore evidence are accepted.
- [ ] Product owner signs off the release.

## Important boundaries

- CI can validate scripts, Compose syntax, mocked rollback paths, provenance checks, and application tests. It cannot create the actual server, DNS/TLS, GitHub Environment approvals/secrets, or a real backup.
- This checklist does not authorize a production deployment. Production deployment remains manual and approval-gated.
- Never paste SSH private keys, database passwords, JWT secrets, or backup contents into ChatGPT or commit them to Git.
