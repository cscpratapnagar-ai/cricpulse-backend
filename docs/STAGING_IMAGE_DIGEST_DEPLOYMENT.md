# Staging image digest deployment

The staging workflow accepts immutable commit tags as operator-friendly inputs, but it does not deploy those tags directly.

## Release flow

1. The operator supplies backend and frontend `sha-<commit>` tags to the `Staging Deployment` workflow.
2. The workflow resolves each tag to the raw OCI registry manifest and computes its SHA-256 digest.
3. GitHub CLI verifies the signed attestation against the digest reference, not the tag:
   - Backend: `ghcr.io/cscpratapnagar-ai/cricpulse-backend@sha256:...`, signer workflow in the backend repository, source ref `refs/heads/main`.
   - Frontend: `ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:...`, signer workflow in the frontend repository, source ref `refs/heads/develop`.
4. Only after both verifications pass are the exact digest values passed to the remote deployment script.
5. Docker Compose pulls and runs image references containing `@sha256:`. It never re-resolves the input tags during deployment.
6. The last-known-good state and automatic rollback both store and use digest-pinned image references.

## Existing staging host migration

On the first deployment after this change, the remote script supports an existing staging `.env` containing the previous `BACKEND_TAG=sha-...` and `FRONTEND_TAG=sha-...` keys. It resolves those two prior SHA tags to digests once and uses those immutable references as the rollback baseline. After a successful deployment, `.env` contains `BACKEND_IMAGE` and `FRONTEND_IMAGE` values in the form:

```dotenv
BACKEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-backend@sha256:<64-hex-digest>
FRONTEND_IMAGE=ghcr.io/cscpratapnagar-ai/cricpulse-ui@sha256:<64-hex-digest>
```

The staging host must already have Docker Compose, Docker Buildx, `pg_restore`, registry access to GHCR, its staging-only `.env`, and a known-good prior release. The existing isolated `/staging` directory requirement and recent custom-format PostgreSQL backup requirement remain in force.

## Fail-closed guarantees

- Non-SHA input tags are rejected.
- Missing Buildx, malformed registry manifest digests, unsupported GitHub CLI versions, missing GitHub credentials, or failed attestation verification stop the workflow before SSH deployment.
- No digest is exported to the deployment step unless its attestation verification succeeds.
- The remote deploy script rejects malformed digests and image references from any repository other than the two expected GHCR repositories.
- Failed deployment smoke checks restore the exact previous digest-pinned references. A failed rollback reports a critical operator-intervention error.

This workflow change does not deploy to staging or production by itself. Staging still requires configured GitHub Environment secrets, a reachable staging host, GHCR access, and a recent verified database backup.

## Remote staging host bootstrap preflight

Before the deployment script can change the running stack, the workflow transfers the staging Compose and smoke-check files and runs `scripts/staging-host-preflight.sh` over the pinned SSH connection. This check is read-only with respect to the running containers and fails closed unless:

- The dedicated `/staging` application directory exists, is not a symlink, resolves within the permitted staging path, and is owned by the SSH deployment user.
- The staging `.env` is a regular file owned by that user with no group/other permissions (normally `0600`); required database/JWT secrets exist, meet minimum lengths, and are not known CI/development placeholders.
- Docker daemon access, Docker Compose, Docker Buildx, `pg_restore`, `curl`, `jq`, and required host utilities are available.
- The supplied custom-format PostgreSQL backup is readable, non-empty, and no older than 24 hours.
- At least 2 GiB of free disk space is available.
- The staging host can inspect both exact verified GHCR digest references using its own registry credentials.
- The staged Compose configuration validates with those exact digest references and the existing staging secrets.

The host's GHCR login must be configured for the SSH deployment user before running the workflow. The preflight never prints secret values and does not source `.env` as shell code. It does not install packages, change firewall rules, create secrets, or deploy containers; missing prerequisites are reported so an operator can bootstrap the host safely and rerun the workflow.


## Isolated PostgreSQL backup restore rehearsal

The staging host preflight now performs a real restore rehearsal before the deployment script is allowed to change the running staging stack:

1. It starts a disposable `postgres:16-alpine` container with a temporary random credential and a fresh `restore_probe` database.
2. It copies the supplied backup into that isolated container and runs `pg_restore --exit-on-error --no-owner --no-privileges`.
3. It queries PostgreSQL system catalogs and requires at least one restored user table, partitioned table, view, materialized view, or sequence.
4. An exit trap removes the temporary container on both success and failure. The probe does not publish a port, mount the staging data volume, connect to the staging database, or modify the supplied backup.

A successful `pg_restore --list` alone is not considered sufficient. If the archive cannot be restored, PostgreSQL does not become ready, or no user database objects are present, the host preflight fails closed and deployment does not begin. The staging host must be able to pull the PostgreSQL 16 Alpine image and have enough free disk space for the temporary restore.
