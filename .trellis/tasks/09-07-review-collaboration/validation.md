# Validation — 2026-09-08

## Final CI and deployment

- Application commit: `746e40b9eabaef80b80176f0373f0712eda4d4bb`, pushed to `origin/main`.
- [CI run 34202968598](https://github.com/LinYsssss/ForgePilot/actions/runs/34202968598) completed successfully at 08:12:32 UTC. All four jobs passed: Backend verify, Frontend gates, Evaluation contract, Empty-stack Compose smoke.
- Actual CI log: **379 backend tests / 50 classes, 0 failures/errors/skips**, BUILD SUCCESS at 08:10:53 UTC; **44 frontend tests / 15 files**, with lint/typecheck/build also passing.
- Two independent fresh-volume Compose projects passed at 08:12:04 and 08:12:28 UTC, including V1–V14 and the exact 21 business tables.
- Evaluation contract ran quick-set validation, deterministic scorer selftest, synthetic reference comparison and the source-checkout holdout guard. It did not execute a formal model evaluation or the private holdout.
- The existing `fp-demo` application containers were rebuilt and deployed with the original PostgreSQL container and volume retained. V14, record counts, container health, loopback/public HTTP and served asset hashes passed; see [deployment.md](deployment.md).
- CI log on this workspace: `/tmp/forgepilot-review-collaboration-ci-work.log`. The earlier local executions below remain the historical debugging evidence; the CI run is a new, single all-green full-suite result.

## Backend

Executed through the repository wrapper in the documented Java 21 container:

```bash
docker run --rm --network host \
  -v /root/ForgePilot/backend:/workspace \
  -v /root/.m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk \
  ./mvnw -B -ntp verify
```

- Finished 2026-09-08 03:46:59 UTC, duration 4:35.
- 379 tests in 50 classes: 1 failure, 0 errors, 0 skips.
- Sole failure: `ResourceRemovalTest.removingAMemberRevokesLivePermissionsAndKeepsEveryAccomplishedFact` expected the pre-reviewer deletion audit text. Production cleanup had succeeded.
- Updated that existing fixture to carry a reviewer assignment, asserted its removal and the additional audit count. No production change for this failure.
- Re-executed the same container command with `./mvnw -B -ntp -Dtest=ResourceRemovalTest verify`: 4 tests, 0 failures/errors/skips, JAR packaging and Spring Boot repackaging succeeded. Finished 04:02:11 UTC, duration 1:58.
- After the code review fixed developer-assignment target-role validation, executed the same container with `./mvnw -B -ntp -Dtest=RequirementLifecycleTest,ImplementationGuidanceTest,BatchOneApiTest verify`: 24 tests (12 + 6 + 6), 0 failures/errors/skips, packaging/repackaging succeeded. Finished 07:06:12 UTC, duration 2:23. Existing assignment coverage now rejects an ineligible role and non-member without changing lifecycle or the previous assignee, and accepts DEVELOPER/LEADER targets.
- These local checks were a full execution followed by failed-class verification and an affected-class audit check. The later CI result above supplies the single all-green full-suite run. A stale local `CoreApiJourneyTest` XML (5 tests, absent from the full-run log) is excluded from the count.

Logs on this workspace:

- `/tmp/forgepilot-review-collaboration-focused.log`: focused development checks.
- `/tmp/forgepilot-review-collaboration-verify.log`: full run, including the original failure.
- `/tmp/forgepilot-review-collaboration-recheck.log`: corrected class and packaging success.
- `/tmp/forgepilot-review-collaboration-audit-backend.log`: post-review assignment checks and packaging success.

Changed-behavior coverage: reviewer assignment/role checks and cleanup, one-time final authority and lifecycle gates, mandatory return reason, rollback on failed merge, expected-head GitHub/GitLab merge and read-only lost-response recovery, notification commit visibility/rollback silence/delivery failure isolation, public content and attachment/project isolation. SCM and DingTalk writes use stubs/mocks.

## Frontend

Executed in `frontend/`:

```bash
npm run lint && npm run typecheck && npm test -- --run && npm run build
```

All passed again after the code review: 44 tests in 15 files; production JS 258.41 kB (gzip 84.43 kB), CSS 69.00 kB (gzip 12.26 kB). Test run started 07:03:30 UTC and took 44.24 s. Full gate output: `/tmp/forgepilot-review-collaboration-audit-frontend.log`.

The existing three-role journey covers reviewer assignment and denial of an unassigned reviewer, blank return reason rejected before POST, return reason visible on the requirement, next round and previous reason, public knowledge reading/downloading, and preserved excerpt after original deletion. Shared phase coverage ensures draft/ready/terminal lifecycles are not overwritten by review activity.

The code review extended that same journey to reopen a suppressed finding and unlink the PR from its requirement. Both assertions failed against the respective old UI behavior, then passed after the minimal fixes: reopened items return to the main list with SUPPRESSED lineage intact; historical Review handlers resolve through the Review's saved requirement id. No new frontend test file or case was added.

## Documentation and static checks

- All six `docs/deliverables/*.html` files: parsed tag nesting, duplicate IDs and local anchors/links; no errors.
- Final audit checks also verified the MANUAL's eight package source-count rows and 32 frontend source rows against disk. Backend package/table/migration counts remain 9/21/14; dependencies, runtime configuration and Review prompt/hash rules are unchanged.
- `bash -n scripts/phase1-compose-smoke.sh`: passed. Its migration assertion is now 14/V14; table set remains 21.
- `git diff --check`: passed.
- Current documentation synchronized for nine packages, 21 tables, 14 migrations, six navigation entries and 11 routes. Evaluation assets and historical measurement dates preserved.

## Limits

Real-browser layout/accessibility, live GitHub/GitLab merges and live DingTalk delivery remain unexecuted. Deployment health and asset checks used HTTP requests, not browser interaction. Existing capacity and formal evaluation results were not rerun. The user-authorized deployment and CI cold starts are recorded above and in `deployment.md`.
