# Production release evidence

Every manually dispatched production deployment attempt generates a sanitized Markdown evidence report, including attempts that fail before deployment starts.

The report records the GitHub workflow run and source revision, requested immutable image tags, verified image digests when available, release preflight outcome, image provenance outcomes, deployment outcome, and overall workflow result. It is attached to the GitHub Actions Job Summary and uploaded as a 90-day artifact.

The report intentionally excludes deployment hostnames, SSH material, environment secrets, application URLs, deployment directories, and backup file paths. Values such as image tags and digests are validated before inclusion.

A failed deployment step is not automatically labeled as a successful rollback. Operators must inspect the remote deployment log for the explicit `ROLLBACK PASS` or `CRITICAL` marker before deciding whether rollback succeeded.

This evidence is an audit aid, not a replacement for deployment logs, backup verification, or operator approval.
