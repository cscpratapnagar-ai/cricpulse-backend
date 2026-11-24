# Production Release and Deployment Smoke Runbook

This runbook is for an operator deploying the backend and frontend images built by their respective production-container workflows. It does not perform a deployment or replace a staging rehearsal.

## 1. Select immutable images

Use the `sha-<7 lowercase hex>` tags produced by the GitHub Actions production image workflows. Backend and frontend tags come from their own repositories and are expected to differ.

Do not use the mutable `main` or `latest` tags for a production release. The workflow resolves each selected tag to a registry manifest digest, verifies provenance against that digest, and deploys only the resulting `repository@sha256:...` reference. Record both tags, digests, and source commit SHAs in the release notes. The backend publish workflow emits a BuildKit SBOM/provenance attestation and a signed GitHub SLSA build-provenance attestation for the image digest.

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

It rejects missing/weak/default secrets, mutable image tags, and invalid production Compose configuration. It does not test registry access or prove that an image is the intended release; the approval-gated deployment workflow verifies both selected image attestations before it configures SSH or contacts the deployment host. The backend verifier pins this repository's `.github/workflows/publish-production-container.yml`; the frontend verifier pins `cscpratapnagar-ai/cricpulse-ui/.github/workflows/publish-production-container.yml`.

## 3. Back up and verify a PostgreSQL restore

Install PostgreSQL client tools compatible with the server major version on the deployment host. Run the certification script with a role that can read the source database and create/drop a temporary verification database. Use a protected destination with sufficient free space; the dump contains application data and is created with owner-only file permissions.

```bash
export PGHOST=127.0.0.1
export PGPORT=5432
export PGUSER=cricket_app
export PGPASSWORD='<database-password>'
export PGDATABASE=cricket
export BACKUP_FILE=/var/backups/cricpulse/pre-deploy-$(date +%Y%m%d-%H%M%S).dump
bash scripts/postgres-backup-restore-certification.sh
```

The script refuses to overwrite an existing backup. It creates a custom-format `pg_dump`, validates the archive, restores it into a uniquely named temporary database, compares the public table inventory, queries every restored table's exact row count, and drops the temporary database on exit. Keep the resulting dump and SHA-256 checksum in protected backup storage; do not commit or upload production dumps to CI artifacts. This is a restore rehearsal, not a substitute for encrypted off-host retention, scheduled backups, or a point-in-time recovery (WAL) policy. Ensure the backup destination is on a protected volume and has enough capacity.

The managed Production Deployment workflow also performs an independent restore rehearsal against the exact supplied backup path immediately before any release-state mutation. It starts a disposable `postgres:16-alpine` container with `--network none`, no published ports, and no production volume mounts; copies the backup into that container; runs `pg_restore --exit-on-error`; and verifies that restored user database objects exist. The container is removed by an EXIT trap on both success and failure. If the restore probe fails, deployment stops before changing `.env`, pulling images, or restarting services. The host needs Docker access and must be able to pull the PostgreSQL 16 image before the release is changed.

Then deploy the selected immutable images:

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

The reusable smoke script can skip optional checks when run manually, but the managed production workflow requires both `PRODUCTION_APP_URL` and `PRODUCTION_ACTUATOR_URL`; a deployment cannot pass with either omitted.

## 5. Approval-gated deployment with automatic rollback

The repository includes a manual-only GitHub Actions workflow at `.github/workflows/production-deployment.yml`. It does not deploy on push or merge. Configure a GitHub Environment named `production` with required reviewers before using it; put the deployment secrets below in that environment (not as plaintext repository files).

Required production Environment secrets:

- `DEPLOY_HOST`: production SSH hostname
- `DEPLOY_USER`: restricted deployment user
- `DEPLOY_SSH_PRIVATE_KEY`: private key for that user
- `DEPLOY_SSH_KNOWN_HOSTS`: pinned known-hosts entry for the host; do not disable host-key checking
- `DEPLOY_APP_DIR`: absolute application directory containing the existing production `.env`
- `PRODUCTION_API_BASE_URL`: public HTTPS API origin
- `PRODUCTION_ACTUATOR_URL`: host-local readiness URL, normally `http://127.0.0.1:8081/actuator/health/readiness`
- `PRODUCTION_APP_URL`: required public HTTPS frontend origin; the production workflow fails closed if it is missing

Before triggering the workflow:

1. Ensure the host has Docker Engine, the Compose plugin and `docker inspect` support, is already logged into GHCR with read access to both private images (if applicable), and the deployment user can run Docker Compose.
2. Create and restore-verify a fresh PostgreSQL dump on the host. For example, from the application directory:

   ```bash
   export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=cricket_app PGDATABASE=cricket
   export PGPASSWORD='<database-password>'
   export BACKUP_FILE=/var/backups/cricpulse/pre-deploy-$(date +%Y%m%d-%H%M%S).dump
   bash release-tools/postgres-backup-restore-certification.sh
   ```

   The backup must be a non-empty absolute path and no older than 24 hours. Keep it protected; the workflow receives only its path and prints its checksum, never uploads the dump.
3. In GitHub Actions, run **Production Deployment** using the exact immutable `sha-...` backend and frontend tags from their respective successful image-publish runs, and provide the backup path. Optionally provide a public match ID for the match snapshot check.

The workflow first authenticates to GHCR and resolves each selected tag to a registry manifest digest. It verifies signed SLSA provenance for that exact digest with `gh attestation verify`, scoped to the owning repository and trusted publish workflow. A missing, invalid, or differently signed attestation fails closed before SSH setup or host contact. The remote deployment script independently captures the exact RepoDigest of each currently running container before changing the release. It refuses to proceed if it cannot identify exactly one running container and one valid GHCR RepoDigest for either service. The new `.env` stores both human-readable source tags and digest-pinned image references; Compose pulls and runs only the `repository@sha256:...` references. If the new release fails, rollback restores the exact previously running digests rather than resolving old mutable tags again. A successful deployment writes tags and image digests to `.last-known-good-release`.

A failed new release remains a failed workflow even when rollback succeeds; inspect the logs and investigate before retrying. If rollback also fails, the workflow reports that manual operator intervention is required. Do not run concurrent deployments outside this workflow.

This automation does not provision the server, configure DNS/TLS/firewalls, create GitHub environment reviewers, install secrets, or guarantee database schema backward compatibility. Database migrations must remain compatible with the previous application version for rollback to be safe. The pre-deployment backup is a recovery aid, not an automatic database restore during rollback.

## 6. Staging and operator certification still required

Before announcing a production release, record evidence for:
- Two physical devices/browser profiles (scorer and public viewer)
- Second-scorer lease conflict and authorized recovery
- Real network interruption, reconnect, and queued/offline scoring reconciliation
- OBS/vMix scene and overlay output, including recovery after API restart
- A sustained match-length soak and latency observations on the intended host/network
- Backup restoration rehearsal and rollback to the previous immutable image tags

CI validates the release guards and smoke script against a disposable API/database fixture. It does not connect to the production host, deploy containers, test a real internet route, or certify OBS/vMix.
