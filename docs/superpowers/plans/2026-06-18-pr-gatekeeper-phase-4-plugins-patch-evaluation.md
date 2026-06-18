# PR Gatekeeper Phase 4 Plugins, Patch Verification, and Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Java, Python, and JavaScript/TypeScript analysis plugins, evidence-based gate decisions, verified candidate patches, approval, and repeatable Agent evaluation.

**Architecture:** Language plugins convert repository changes and tool output into provider-neutral finding candidates. An evidence service calculates confidence deterministically. Generated patches run through scope validation and the sandbox before they become approvable.

**Tech Stack:** JavaParser, PMD, SpotBugs, Checkstyle, Ruff, Bandit, Pytest, ESLint, Semgrep, TypeScript, Jest/Vitest, Docker, Spring Boot, Vue 3.

---

## Plugin and quality boundaries

- Plugins declare capabilities and fixed command IDs; they never supply arbitrary shell text.
- Tool images and rule versions are pinned and persisted with findings.
- Missing tools/dependencies and unsupported layouts are environment results, not defects.
- Model-only findings never block a PR.
- Patch content is untrusted and must pass path, size, protected-file, apply, and validation checks.

### Task 1: Define language plugin and finding contracts

**Files:**
- Create interfaces and records under `backend/src/main/java/com/example/codereview/language/`.
- Create finding domain under `backend/src/main/java/com/example/codereview/finding/`.
- Create migration `V7__findings_and_evidence.sql`.

- [ ] Test plugin selection for pure and mixed-language PRs.
- [ ] Define `RepositoryProfile`, `ChangeSet`, `ChangeAnalysis`, `ToolCommand`, `FindingCandidate`, and `FindingEvidence`.
- [ ] Persist evidence type, source version, file/line, bounded excerpt, score, and content hash.
- [ ] Commit with `feat: define language plugin and evidence contracts`.

### Task 2: Implement Java plugin

**Files:**
- Create package `backend/src/main/java/com/example/codereview/language/java/`.
- Add Java fixtures under `demo-repos/evaluation/java/`.

- [ ] Test Maven/Gradle detection and changed-symbol extraction.
- [ ] Add JavaParser-based class, method, annotation, and call context extraction.
- [ ] Register fixed command IDs for Maven/Gradle compile/test, PMD, SpotBugs, and Checkstyle.
- [ ] Normalize SARIF/XML findings into `FindingCandidate`.
- [ ] Commit with `feat: add java analysis plugin`.

### Task 3: Implement Python plugin

**Files:**
- Create package `backend/src/main/java/com/example/codereview/language/python/`.
- Add fixtures under `demo-repos/evaluation/python/`.

- [ ] Test Python project detection from `pyproject.toml`, `requirements.txt`, and changed `.py` files.
- [ ] Register Ruff, Bandit, and Pytest command IDs.
- [ ] Parse JSON/JUnit output into normalized findings and validation results.
- [ ] Commit with `feat: add python analysis plugin`.

### Task 4: Implement JavaScript/TypeScript plugin

**Files:**
- Create package `backend/src/main/java/com/example/codereview/language/javascript/`.
- Add fixtures under `demo-repos/evaluation/javascript/`.

- [ ] Test package manager and test framework detection.
- [ ] Register ESLint, Semgrep, TypeScript, Jest, and Vitest command IDs.
- [ ] Parse tool output without executing arbitrary `package.json` scripts unless explicitly whitelisted.
- [ ] Commit with `feat: add javascript typescript analysis plugin`.

### Task 5: Implement deterministic evidence confidence

**Files:**
- Create: `backend/src/main/java/com/example/codereview/finding/FindingConfidenceService.java`
- Create: `backend/src/main/java/com/example/codereview/finding/GateDecisionService.java`
- Test both services.

- [ ] Test exact weights: tool `0.35`, reproducible location `0.20`, knowledge `0.20`, verifier agreement `0.15`, test reproduction `0.10`.
- [ ] Test conflicts and stale line locations reduce confidence.
- [ ] Test blocking requires HIGH severity, confidence at least `0.75`, and valid code location.
- [ ] Make thresholds configurable but weights versioned and persisted with each decision.
- [ ] Clamp confidence to `[0,1]` and persist each score contribution for UI explanation.
- [ ] Commit with `feat: calculate evidence-based gate decisions`.

### Task 6: Add finding verification and deduplication

**Files:**
- Create: `backend/src/main/java/com/example/codereview/finding/FindingDeduplicator.java`
- Create: `backend/src/main/java/com/example/codereview/finding/FindingVerifier.java`
- Test semantic duplicates and conflicting evidence.

- [ ] Build a stable fingerprint from category, normalized file, symbol, and line neighborhood hash.
- [ ] Merge tool, knowledge, and model evidence without increasing confidence twice for the same source.
- [ ] Persist rejected candidates with rejection reason for evaluation.
- [ ] Commit with `feat: verify and deduplicate findings`.

### Task 7: Upgrade contextual retrieval for code review

**Files:**
- Create: `backend/src/main/java/com/example/codereview/context/ReviewContextService.java`
- Create: `backend/src/main/java/com/example/codereview/context/HybridContextRanker.java`
- Modify: `backend/src/main/java/com/example/codereview/knowledge/KnowledgeService.java`
- Modify: `backend/src/main/java/com/example/codereview/rag/RagService.java`
- Test: `backend/src/test/java/com/example/codereview/context/ReviewContextServiceTest.java`

- [ ] Test queries built from changed paths, symbols, imports, annotations, strings, and tool rule IDs.
- [ ] Test project/document isolation, thresholds, deduplication, context byte budget, and source-version metadata.
- [ ] Combine lexical score, vector similarity, changed-symbol relationships, and document-type weights.
- [ ] Prefer heading/code-boundary chunks over fixed character cuts while retaining a fallback for existing data.
- [ ] Wrap every source as untrusted evidence and preserve exact references.
- [ ] Commit with `feat: add hybrid review context retrieval`.

### Task 8: Persist and validate patch candidates

**Files:**
- Create migration `V8__patch_candidates_and_approvals.sql`.
- Create package `backend/src/main/java/com/example/codereview/patch/`.
- Test unified diff parsing and scope policy.

- [ ] Reject absolute paths, traversal, binary patches, protected files, excessive file count, and excessive changed lines.
- [ ] Bind each patch to Agent Run, head SHA, finding IDs, generator model, and prompt version.
- [ ] Reject a patch when the current PR head SHA differs from its bound SHA.
- [ ] Commit with `feat: validate generated patch candidates`.

### Task 9: Apply and verify patches in sandbox

**Files:**
- Add `patch.apply` and `patch.validate` tools.
- Add runner command handlers and tests.

- [ ] Test clean application, stale head SHA, conflicting patch, compilation failure, test failure, and successful validation.
- [ ] Execute baseline and patched scans/tests with identical image and limits.
- [ ] Store structured before/after deltas and bounded logs.
- [ ] Allow approval only when patch application succeeds; mark build/test status independently.
- [ ] Require the target finding fingerprint or reproducer to disappear; passing unrelated tests alone does not prove repair.
- [ ] Commit with `feat: verify candidate patches in sandbox`.

### Task 10: Add approval API and UI

**Files:**
- Create backend approval controller/service/DTOs.
- Modify frontend API client.
- Split Agent timeline, findings, patch diff, and approval into focused Vue components under `frontend/src/components/agent/`.

- [ ] Test only authorized project members can approve/reject.
- [ ] Test repeated approval is idempotent and approval of stale patches is rejected.
- [ ] Record approver, decision, immutable Patch hash, head SHA, comment, and timestamp.
- [ ] Add UI views for evidence, confidence calculation, validation logs, patch download, approve, and reject.
- [ ] Add frontend component tests for disabled approval on invalid patches.
- [ ] Commit with `feat: add human patch approval workflow`.

### Task 11: Build versioned evaluation corpus

**Files:**
- Create `evaluation/manifest.json`.
- Create `evaluation/cases/<case-id>/` fixtures for Java, Python, and TypeScript.
- Create `scripts/run-agent-evaluation.ps1`.
- Create backend evaluation report DTO/service.

- [ ] Include true positives, true negatives, ambiguous cases, prompt injection, broken builds, and known patches.
- [ ] Split cases into development and holdout sets; do not tune prompts or weights against holdout labels.
- [ ] Validate each manifest entry has expected category, severity, location, non-findings, and optional patch result.
- [ ] Run each case with fixed tool images, model/prompt version, and budget.
- [ ] Commit with `test: add versioned agent evaluation corpus`.

### Task 12: Calculate quality and cost metrics

**Files:**
- Create `backend/src/main/java/com/example/codereview/evaluation/EvaluationMetrics.java`.
- Create tests with a small known confusion matrix.

- [ ] Calculate precision, recall, F1, high-risk recall, false-positive rate, location accuracy, patch apply rate, build rate, test rate, duration, and cost.
- [ ] Fail evaluation when required high-risk recall or false-positive thresholds regress.
- [ ] Enforce initial gates: recall `>= 0.80`, precision `>= 0.70`, location accuracy `>= 0.90`, and patch application `>= 0.70` for repairable cases.
- [ ] Export JSON and Markdown reports under `evaluation/results/` while ignoring timestamped local artifacts.
- [ ] Commit with `feat: report agent evaluation metrics`.

### Task 13: Add OpenTelemetry and final release verification

**Files:**
- Modify backend and runner dependencies/configuration.
- Modify `deploy/docker-compose.yml`.
- Add Prometheus/OpenTelemetry collector configuration under `deploy/observability/`.

- [ ] Propagate trace context through webhook, RabbitMQ, model calls, and sandbox jobs.
- [ ] Verify metrics do not use unbounded IDs as tags.
- [ ] Run full backend, frontend, runner, Docker Compose, security, and evaluation suites.
- [ ] Record final benchmark numbers and demo procedure in README.
- [ ] Commit with `feat: complete observable pr gatekeeper agent`.
