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
