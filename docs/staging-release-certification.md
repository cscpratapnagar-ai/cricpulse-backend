# Staging Release Certification

## Scope and safety boundary

This workflow deploys only to the GitHub Environment named staging. It uses a dedicated remote application directory whose final path component must be staging, a separate Compose project name, a separate PostgreSQL volume, and loopback-only host ports. It does not call the production deployment workflow or use production environment secrets.

The workflow is manual and requires immutable backend and frontend image tags plus a non-empty staging database backup created within the last 24 hours. Both image attestations are verified before SSH configuration or host contact. A failed verification blocks deployment. All operator-supplied workflow inputs are passed to shell steps through environment variables; GitHub expressions are never expanded directly inside shell command text. CI enforces this boundary with a workflow regression test.

## Required GitHub Environment

Create a GitHub Environment named staging in the backend repository and add these environment secrets:
- STAGING_DEPLOY_HOST
- STAGING_DEPLOY_USER
- STAGING_DEPLOY_SSH_PRIVATE_KEY
- STAGING_DEPLOY_SSH_KNOWN_HOSTS (pinned host key; do not use ssh-keyscan at deploy time)
- STAGING_DEPLOY_APP_DIR (dedicated absolute path ending in /staging)
- STAGING_API_BASE_URL (HTTPS origin)
- STAGING_APP_URL (HTTPS origin)
- STAGING_ACTUATOR_URL (HTTPS readiness endpoint)

The staging host must have Docker Engine, the Docker Compose plugin, and jq. Before any backup restore rehearsal or image pull, host preflight validates the fully resolved Compose model: PostgreSQL must publish no host ports, backend and frontend published ports must bind only to 127.0.0.1, and the dedicated staging PostgreSQL volume must exist. CI includes negative tests for public database, backend, and frontend port exposure. Provision an operator-managed .env in the staging app directory containing POSTGRES_PASSWORD, JWT_SECRET, and the currently deployed immutable BACKEND_TAG and FRONTEND_TAG. Keep this file mode 0600. Never copy production secrets or production database data into staging.

## Prepare and run

1. Publish the backend image from the intended backend release commit using the backend Publish Production Container workflow.
2. Publish the frontend image from the intended frontend release commit using the frontend Publish Production Container workflow in cricpulse-ui.
3. Record the two sha-... tags and resulting digest/attestation evidence.
4. On the staging host, create a fresh staging-only PostgreSQL dump using scripts/postgres-backup-restore-certification.sh; verify the isolated restore succeeds. Keep the backup outside the Compose data volume.
5. In the backend repository, open Actions → Staging Deployment → Run workflow. Supply both immutable tags, the absolute path to the verified staging backup on the staging host, and optionally a staging match UUID.
6. The workflow verifies both attestations, checks staging environment secrets, configures pinned SSH, transfers only staging deployment files, deploys, and runs API readiness, database-backed broadcast health, frontend HTTP/HTML, and optional match snapshot checks.
7. If a deploy or smoke check fails, the script restores both previous immutable image tags and attempts to bring the previous staging release back to healthy status.

## Release evidence artifact

Every workflow attempt generates a sanitized `staging-release-evidence.md` report and uploads it as a 90-day GitHub Actions artifact, including failed attempts where the evidence step can run. The report records the workflow run/attempt, source commit/ref, requested immutable tags, verified image digests, provenance/preflight/deploy step outcomes, and overall workflow status. It is also appended to the GitHub Actions job summary.

The report deliberately excludes credentials, environment secrets, hostnames, deployment paths, backup paths, and endpoint URLs. A failed deployment step is not automatically described as a successful rollback: operators must inspect the deployment log for `ROLLBACK PASS` or `CRITICAL`.

## OBS / vMix manual certification

After the staging workflow succeeds:
- Open the staging match overlay URL in a browser and OBS Browser Source; confirm transparency, score, wickets, overs, batting/bowling details, sponsor and graphics layers.
- Connect the OBS WebSocket scene controller using a dedicated non-production endpoint/password; verify connect, disconnect, reconnect, and scene switch.
- Confirm score updates without refresh and visible stale/disconnected feed health during a controlled interruption.
- Open two independent viewer sessions while a scorer submits a scoring event; confirm both converge to the same committed score and no older snapshot regresses the display.
- Add the staging overlay as a vMix browser input; verify dimensions, transparency, refresh/recovery, and scene/input switching.
- Record workflow URL, image tags/digests, OBS/vMix versions, screenshots, operator, and test date.

Manual OBS/vMix checks require the actual operator workstation and are not represented as passed by GitHub CI.
