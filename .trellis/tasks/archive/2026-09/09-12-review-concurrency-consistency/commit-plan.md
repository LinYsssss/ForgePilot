# Authorized commit and rollout — 2026-09-12

The user requested: “推送远程并部署 帮我清楚前面的审查记录 然后模拟实际操作进行15PR的审查 一个一个来”.
This explicitly authorizes the commit, push, deployment, prior-review cleanup,
and sequential demo workflow. No additional confirmation is required.

## Commit grouping

1. `fix: guard review and requirement concurrency`
   - The four backend production files for shared requirement locking, manual
     review snapshot locking, and GitHub snapshot consistency.
   - `frontend/src/features/review/ReviewDetailPage.vue` navigation ownership.
   - Three new regression suites and the three updated SCM test classes.
   - The corresponding backend/frontend prevention specs and this task's
     implementation and verification artifacts.
2. Operational evidence after deployment and the sequential review exercise.
3. Task archive and session journal bookkeeping after work commits.

The dirty-path inventory contained only files from this task. `main` and
`origin/main` both pointed to `3b7fda5` after fetching origin. The unrelated
`08-30-scm-pr-decision-actions` planning task is excluded.

## Deployment and exercise scope

- Existing Compose project `fp-demo`, `/root/ForgePilot/compose.yaml`, and the
  existing private root `.env`; retain configuration and PostgreSQL volume.
- Preserve a private, validated database backup before rollout and cleanup.
- Existing demo projects: mall order service, tenant user center, and payment
  settlement service. Their archived materials contain five review branches each.
- Finish each PR's review and inspect its actual findings before starting the next.
- Preserve the immutable formal evaluation records and outputs.
