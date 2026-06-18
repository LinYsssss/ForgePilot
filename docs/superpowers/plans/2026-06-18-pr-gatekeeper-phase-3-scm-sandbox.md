# PR Gatekeeper Phase 3 SCM and Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trigger Agent runs from authenticated GitHub/GitLab events and execute repository tools in an isolated runner.

**Architecture:** Provider adapters normalize webhooks and PR metadata into one domain model. The backend remains the control plane; a separate sandbox-runner consumes signed jobs and launches constrained ephemeral Docker containers with no network and no secrets.

**Tech Stack:** Spring MVC, HMAC SHA-256, GitLab token verification, RabbitMQ, Docker Engine API/CLI wrapper, Docker Compose, WireMock, Testcontainers.

---

## Security boundary

- Verify signatures against exact raw request bytes before JSON normalization.
- Resolve installation/project identity before selecting a secret; never accept secrets or provider hosts from payload fields.
- Allow only configured GitHub/GitLab API hosts and validated HTTPS archive URLs to prevent SSRF.
- SCM credentials remain in the backend; the runner receives a sanitized archive or temporary object reference.
- The trusted runner may access Docker on the demo host; analyzed repository containers never receive the Docker socket.
- Dependency preparation and untrusted tests are separate jobs. Test jobs have no network.
- GitHub uses a GitHub App installation (App ID, encrypted private key, webhook secret); do not store long-lived user PATs.
- GitLab v1 uses an encrypted project access token plus webhook secret; OAuth user delegation is outside the first release.
- Provider credentials support rotation and are never returned by read APIs.

### Task 1: Persist SCM installations and webhook deliveries

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__scm_webhooks.sql`
- Create: `backend/src/main/java/com/example/codereview/scm/ScmInstallation.java`
- Create: `backend/src/main/java/com/example/codereview/scm/WebhookDelivery.java`
- Create repositories and persistence tests under matching packages.

- [ ] Test encrypted credentials, unique `(provider, externalInstallationId)` and unique `(provider, deliveryId)`.
- [ ] Add statuses `RECEIVED`, `VERIFIED`, `DUPLICATE`, `REJECTED`, `PROCESSED`, `FAILED`.
- [ ] Store payload hash and bounded sanitized preview, not unrestricted raw private payloads.
- [ ] Run tests and commit with `feat: persist scm installations and deliveries`.

### Task 2: Define provider-neutral SCM contracts

**Files:**
- Create: `backend/src/main/java/com/example/codereview/scm/ScmProvider.java`
- Create: `backend/src/main/java/com/example/codereview/scm/NormalizedPullRequestEvent.java`
- Create: `backend/src/main/java/com/example/codereview/scm/PullRequestSnapshot.java`
- Create: `backend/src/main/java/com/example/codereview/scm/ReviewPublication.java`
- Test: `backend/src/test/java/com/example/codereview/scm/ScmProviderContractTest.java`

- [ ] Define contract fixtures that require provider, installation/project identity, repository clone URL, PR number, base/head SHA, source/target branch, and delivery ID.
- [ ] Ensure all provider adapters return the same normalized records.
- [ ] Commit with `feat: define scm provider contracts`.

### Task 3: Implement GitHub webhook verification and normalization

**Files:**
- Create: `backend/src/main/java/com/example/codereview/scm/github/GitHubWebhookVerifier.java`
- Create: `backend/src/main/java/com/example/codereview/scm/github/GitHubScmProvider.java`
- Create: `backend/src/main/java/com/example/codereview/scm/github/GitHubWebhookController.java`
- Add JSON fixtures under `backend/src/test/resources/webhooks/github/`.

- [ ] Test valid and invalid `X-Hub-Signature-256` with constant-time comparison.
- [ ] Compute HMAC from captured raw bytes, not reserialized JSON.
- [ ] Test `pull_request` actions `opened`, `reopened`, and `synchronize`; ignore unrelated actions with HTTP 202.
- [ ] Test duplicate `X-GitHub-Delivery` returns the existing Agent Run.
- [ ] Run tests and commit with `feat: receive github pull request webhooks`.

### Task 4: Implement GitLab webhook verification and normalization

**Files:**
- Create matching files under `backend/src/main/java/com/example/codereview/scm/gitlab/`.
- Add fixtures under `backend/src/test/resources/webhooks/gitlab/`.

- [ ] Verify `X-Gitlab-Token` against the encrypted installation secret.
- [ ] Normalize Merge Request open, reopen, and update events.
- [ ] Build a deterministic delivery key from GitLab event UUID when present, otherwise hash project, MR, head SHA, and event timestamp.
- [ ] Run tests and commit with `feat: receive gitlab merge request webhooks`.

### Task 5: Create Agent runs from normalized events

**Files:**
- Create: `backend/src/main/java/com/example/codereview/scm/WebhookAgentRunService.java`
- Test: `backend/src/test/java/com/example/codereview/scm/WebhookAgentRunServiceTest.java`

- [ ] Test one verified delivery creates one `RECEIVED` Agent Run.
- [ ] Test duplicate delivery returns the original run without a second MQ publish.
- [ ] Test a newer head SHA creates a new run and marks an older active run superseded/canceled.
- [ ] Return HTTP 202 after durable delivery/outbox persistence; do not wait for repository download or Agent execution.
- [ ] Commit with `feat: start agent runs from scm events`.

### Task 6: Define signed sandbox job protocol

**Files:**
- Create: `backend/src/main/java/com/example/codereview/sandbox/SandboxJob.java`
- Create: `backend/src/main/java/com/example/codereview/sandbox/SandboxResult.java`
- Create: `backend/src/main/java/com/example/codereview/sandbox/SandboxJobSigner.java`
- Create matching protocol models in `sandbox-runner/src/main/java/...`.
- Test signature compatibility in both modules.

- [ ] Include job ID, workspace archive reference, image digest, command ID, arguments, limits, expiry, and nonce.
- [ ] Sign canonical JSON with HMAC SHA-256.
- [ ] Reject expired, replayed, or invalidly signed jobs.
- [ ] Commit with `feat: define signed sandbox job protocol`.

### Task 7: Scaffold the sandbox-runner service

**Files:**
- Create: `sandbox-runner/pom.xml`
- Create: `sandbox-runner/src/main/java/com/example/reposage/sandbox/SandboxRunnerApplication.java`
- Create: `sandbox-runner/src/main/java/com/example/reposage/sandbox/SandboxJobConsumer.java`
- Create: `sandbox-runner/Dockerfile`
- Modify: `deploy/docker-compose.yml`

- [ ] Add a context-load test and a consumer test with a fake executor.
- [ ] Configure a dedicated RabbitMQ queue and no inbound public HTTP port.
- [ ] Mount the Docker socket only into the trusted runner, preferably through a restricted socket proxy; never mount it into analyzed containers.
- [ ] Document that single-host Compose is for controlled demonstrations, not hostile multi-tenant execution.
- [ ] Commit with `feat: add isolated sandbox runner service`.

### Task 8: Enforce container execution policy

**Files:**
- Create: `sandbox-runner/src/main/java/com/example/reposage/sandbox/ContainerPolicy.java`
- Create: `sandbox-runner/src/main/java/com/example/reposage/sandbox/DockerSandboxExecutor.java`
- Test: `sandbox-runner/src/test/java/com/example/reposage/sandbox/ContainerPolicyTest.java`

- [ ] Test generated container arguments include `--network none`, read-only root filesystem, non-root user, CPU/memory/PID limits, timeout, and temporary writable workspace.
- [ ] Test command IDs resolve only through a whitelist; reject command strings from messages.
- [ ] Test paths remain within the job workspace after normalization and symlink resolution.
- [ ] Kill and remove the active container on cancellation or timeout; cleanup must be idempotent.
- [ ] Commit with `feat: enforce sandbox container policy`.

### Task 9: Add dependency cache preparation

**Files:**
- Create: `sandbox-runner/src/main/java/com/example/reposage/sandbox/DependencyPreparationPolicy.java`
- Create: `sandbox-runner/src/main/java/com/example/reposage/sandbox/DependencyCacheManager.java`
- Modify: `deploy/docker-compose.yml`
- Test: `sandbox-runner/src/test/java/com/example/reposage/sandbox/DependencyPreparationPolicyTest.java`

- [ ] Test Maven, Gradle, pip, npm, pnpm, and yarn lockfiles produce deterministic cache keys.
- [ ] Test untrusted test jobs mount caches read-only and retain `--network none`.
- [ ] Permit dependency preparation only as a separate allowlisted job with size/time limits and no SCM/LLM secrets.
- [ ] Return `ENVIRONMENT_INCOMPLETE` when dependencies are unavailable; do not create a code finding.
- [ ] Commit with `feat: prepare isolated dependency caches`.

### Task 10: Add repository preparation and read-only tools

**Files:**
- Create backend tools under `backend/src/main/java/com/example/codereview/agent/tool/git/`.
- Create runner command handlers for repository unpack, file read, diff, and code search.
- Test with `demo-repos/mall-order-service`.

- [ ] Register `git.diff`, `git.file`, and `code.search`.
- [ ] Ensure archive extraction rejects absolute paths, `..`, and escaping symlinks.
- [ ] Reject oversized archives, excessive file counts, unsafe submodule hosts, and URLs resolving to private/link-local addresses.
- [ ] Return bounded output with truncation metadata.
- [ ] Commit with `feat: execute repository read tools in sandbox`.

### Task 11: Publish provider-specific PR results

**Files:**
- Add publication clients under GitHub and GitLab adapter packages.
- Test with WireMock.

- [ ] Test GitHub Check/PR comment payloads and GitLab MR note/status payloads.
- [ ] Include summary, blocking findings, evidence links, Agent Run URL, and patch validation state.
- [ ] Require approval for any publication that exposes generated patch content.
- [ ] Commit with `feat: publish agent reviews to scm providers`.

### Task 12: Run security and integration verification

- [ ] Run backend and runner unit tests.
- [ ] Run Testcontainers webhook-to-run integration tests.
- [ ] Run a Docker Compose smoke test proving a malicious command and network request are blocked.
- [ ] Prove analyzed containers cannot access the Docker socket, backend network, cloud metadata addresses, or host paths outside the workspace.
- [ ] Verify no SCM or LLM secret appears in runner environment or logs.
- [ ] Commit documentation with `docs: document scm and sandbox operations`.
