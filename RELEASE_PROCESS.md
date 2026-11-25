# CricPulse Release Process

## Branches

- Backend development branch: `main`
- UI development branch: `develop`
- Deployment branch in both repositories: `release`

Vercel Git deployments are allowed only from the exact `release` branch. All other branches are blocked by `vercel.json`.

## Release promotion

1. Complete development and merge changes into the repository's development branch.
2. Confirm required GitHub Actions checks are green.
3. Open a pull request from the development branch into `release`.
   - Backend: `main` -> `release`
   - UI: `develop` -> `release`
4. Review both promotion PRs together and merge only after both are green.
5. Confirm Vercel has built the new `release` commit successfully and run smoke tests.
6. Create the same annotated version tag in both repositories on the deployed release commit (for example, `v1.0.0`). Never tag an unverified build.
7. For a rollback, promote the last known-good commit back to `release` and record the rollback version.

## Version tags

Use semantic versioning: `vMAJOR.MINOR.PATCH`. Tags are immutable release records, not the deployment trigger. Vercel Git deployment is triggered by a push/merge to `release`.

## Important

- Keep `release` as the Vercel production branch in each Vercel project.
- Keep payment checkout disabled until its separately approved final release.
- A green GitHub CI run is not proof of a successful Vercel deployment; verify the Vercel deployment status and application health separately.
