# Production Release and Deployment Smoke Runbook

This runbook is for an operator deploying the backend and frontend images built by their respective production-container workflows. It does not perform a deployment or replace a staging rehearsal.

## 1. Select immutable images

Use the `sha-<7 lowercase hex>` tags produced by the GitHub Actions production image workflows. Backend and frontend tags come from their own repositories and are expected to differ.

Do not use the mutable `main` or `latest` tags for a production release. Record both selected image tags and the source commit SHAs in the release notes.

## 2. Prepare production secrets

Set these values in the deployment environment (never commit a production `.env` file):

- `POSTGRES_PASSWORD`: unique secret, at least 20 characters
- `JWT_SECRET`: unique secret, at least 32 characters; never use local or CI defaults
- `BACKEND_TAG`: immutable backend `sha-...` tag
- `FRONTEND_TAG`: immutable frontend `sha-...` tag
- Optional `API_HEALTH_PORT`: host-local readiness port, default `8081`

Run the release guard before pulling or restarting containers:

```bash
bash scripts/production-release-preflight.sh
```

It rejects missing/weak/default secrets, mutable image tags, and invalid production Compose configuration. It does not test registry access or prove that an image is the intended release; operators must confirm the selected tags against the successful image-publish workflow runs.

## 3. Back up and deploy

Take and verify a PostgreSQL backup according to the host's database backup policy before upgrading. Then:

```bash
docker compose -f docker-compose.production.yml pull
docker compose -f docker-compose.production.yml up -d
docker compose -f docker-compose.production.yml ps
```

The backend's operational port is bound to `127.0.0.1` only (default `8081`); it is not intended to be internet-accessible. The frontend reverse proxy routes `/api/` and `/ws/` to the backend. Keep the public application behind HTTPS at the edge/reverse proxy.

## 4. Run post-deployment smoke checks

Run this from the deployment host after the containers become healthy. `API_BASE_URL` is the public HTTPS origin served by the frontend reverse proxy. `ACTUATOR_URL` uses the loopback-only operational port. `APP_URL` checks the actual frontend root. Supply a real public match ID to verify the broadcast snapshot:

```bash
API_BASE_URL=https://your-cricpulse-domain.example \
ACTUATOR_URL=http://127.0.0.1:8081/actuator/health/readiness \
APP_URL=https://your-cricpulse-domain.example \
MATCH_ID=<public-match-uuid> \
REQUIRE_HTTPS=true \
bash scripts/production-deployment-smoke.sh
```

The script checks:
- Spring Boot readiness via the private loopback actuator endpoint
- Database-backed public broadcast readiness via `/api/public/broadcast-health`
- Frontend root returns HTTP 200 and HTML
- Optional match-specific broadcast snapshot returns the expected match ID and innings array

If no `APP_URL` or `MATCH_ID` is supplied, those checks are explicitly skipped and the script reports the skips. Do not treat a partial run as full release sign-off.

## 5. Staging and operator certification still required

Before announcing a production release, record evidence for:
- Two physical devices/browser profiles (scorer and public viewer)
- Second-scorer lease conflict and authorized recovery
- Real network interruption, reconnect, and queued/offline scoring reconciliation
- OBS/vMix scene and overlay output, including recovery after API restart
- A sustained match-length soak and latency observations on the intended host/network
- Backup restoration rehearsal and rollback to the previous immutable image tags

CI validates the release guards and smoke script against a disposable API/database fixture. It does not connect to the production host, deploy containers, test a real internet route, or certify OBS/vMix.
