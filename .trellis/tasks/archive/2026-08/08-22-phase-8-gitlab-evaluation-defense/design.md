# Phase 8 design

## 1. Boundary and delivery shape

Phase 8 has three ordered slices:

1. `scm.gitlab` completes the second provider behind the existing `scm` model and event contract.
2. A formal-evaluation toolchain freezes configuration, imports the locked corpus, runs the existing three arms, and scores preserved outputs.
3. Deployment and defense evidence prove clean startup and deterministic rescoring.

The order is strict. GitLab and all evaluation tooling/tests are completed and green before configuration freeze. Holdout material is acquired only after the freeze artifact is written. The later reference-frontend rebuild is deliberately outside this task so it cannot change runtime behavior during the experiment.

## 2. GitLab adapter

### 2.1 Package shape

`scm.gitlab` contains the smallest provider-specific set: a webhook controller, a GitLab API client, security path configuration if required, and package documentation. Shared concepts remain in `scm`; no provider may depend on `review`.

`ScmProvider` gains `GITLAB`. Existing repository registration, encryption, stable identity freeze, PR persistence, requirement parsing, author mapping, `PullRequestChanged`, and Review creation are reused unchanged.

### 2.2 Webhook routing and authentication

Endpoint: `POST /api/scm/gitlab/webhook`.

The request body is accepted as `byte[]`. Routing reads only enough JSON to obtain the GitLab project numeric ID and normalized instance identity from the project web URL. The repository row is loaded by `(provider, instance_identity, external_id)`. Current GitLab deliveries are authenticated first through Standard Webhooks: validate a recent `webhook-timestamp`, compute HMAC-SHA256 over `{webhook-id}.{webhook-timestamp}.{raw-body}`, and constant-time compare a `v1,<base64>` entry from `webhook-signature`. For older GitLab instances, absence of those signing headers falls back to a constant-time comparison of `X-Gitlab-Token` with the same decrypted stored secret. A present but invalid signed form never falls back. Parsing/routing/authentication failures return the same 401 body and perform no write or provider call.

Only a verified `Merge Request Hook` continues. Other verified events return 202 as no-ops. The MR IID is validated after authentication, then the API client performs an authoritative read.

### 2.3 Authoritative GitLab snapshot

The client uses the per-repository `api_base` and numeric external project ID. It requests the merge request and its current diff data through GitLab API v4. Numeric IDs avoid rename/namespace encoding ambiguity.

The normalized snapshot maps:

| ForgePilot field | GitLab source |
|---|---|
| external number | MR `iid` |
| title / source ref / author | authoritative MR fields |
| base/head SHA | current diff refs, checked against the current MR head |
| source revision | stable current diff-version ID when supplied |
| source updated time | MR `updated_at` |
| changed files | paginated MR diffs/changes response |

GitLab `new_file`, `deleted_file`, and `renamed_file` flags map to the existing `ChangedFile.changeType`; otherwise the file is modified. A missing, collapsed, binary, or `too_large` patch remains `null`, not an empty patch. Because `diff_refs` populate asynchronously and API calls can straddle an MR update, the client verifies that the authoritative MR identity still matches after reading paginated diffs and fails/retries rather than combining two versions. The total normalized manifest limit is enforced before persistence. Provider 429/5xx/invalid response failures use the existing API error boundary and never create a partial snapshot.

### 2.4 Same downstream behavior

The adapter calls only `PullRequestSyncService.apply`. Its transaction publishes the same synchronous `PullRequestChanged`, so PENDING Review creation, after-commit execution, idempotency, requirement association, stable author mapping, and out-of-order protection require no GitLab fork.

## 3. Formal evaluation

### 3.1 Tooling boundary

Evaluation scripts remain non-runtime Python standard-library tools. They may reuse deterministic helpers from the Phase 6 runner and existing scorer, but they do not become a second production Review Engine and do not add an application dependency.

The formal runner accepts only a validated frozen-corpus bundle and a freeze record. It renders prompts from allowed input fields only: requirement, AC, project knowledge, and changed files according to the selected arm. Expected findings, expected AC verdicts, non-findings, split labels, and selection reasons are never rendered into a model request.

### 3.2 Freeze record

Before importing holdout, generate a versioned freeze document containing:

- locked Legacy repository commit and expected split sizes;
- three fixed arms;
- model, temperature, timeout and retry count;
- prompt/schema version and SHA-256;
- runner/scorer/alias/contract SHA-256 values;
- non-secret API endpoint identity;
- UTC freeze timestamp and current ForgePilot commit/tree state.

Freeze generation refuses a dirty tracked worktree for files that influence model output or scoring. The secret key is represented only as present/absent.

### 3.3 Corpus import and holdout lock

After freeze, import the original corpus at the locked commit into an evidence workspace. Validate exactly 38 cases with the original 26/12 split and hash every manifest/fixture. Private holdout fixtures and truth need not become normal source files; preserved formal evidence may store hashes and raw outputs while keeping the private corpus out of Git.

The holdout command requires the freeze hash, writes an atomic run ledger before the first provider call, and refuses if a holdout ledger/result already exists. Partial provider/structure failures stay in the run. A failure does not authorize a second holdout run.

### 3.4 Reports and uncertainty

The scorer continues to own deterministic matching. Reporting combines its development, holdout, and full-corpus JSON outputs without changing predictions or truth. For proportions, a reporting helper adds 95% Wilson intervals over the actual numerator/denominator. Zero denominators remain `null`. The narrative states that 12 holdout cases provide limited precision and that observed arm differences are descriptive rather than strong population claims.

## 4. Deployment and UI

The existing project settings surface adds a provider selector and provider-specific webhook guidance. It reuses the existing request types and routes. No new top-level entry is introduced, and credentials remain write-only.

The defense guide starts from a clean checkout and empty volume, documents required environment names without values, verifies health and sixteen exact business tables, and shows how to recompute reports from raw outputs without a provider call.

## 5. Failure handling and rollback

- Invalid webhook routing/authentication: uniform 401, no fetch/write.
- Provider payload or diff too large: explicit 422/502-class API failure, no partial snapshot.
- Model/provider/structure failure: preserved failed case, never synthetic empty success.
- Holdout interruption: preserve the partial one-time run and report `notRun`; never delete/restart it.
- GitLab rollback: remove only adapter/UI selection changes; shared SCM/Review state remains untouched.
- Evaluation rollback before freeze: tooling can be revised. After freeze: model-facing/scoring changes require a new future experiment and must not replace this run.
