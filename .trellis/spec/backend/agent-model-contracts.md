# Agent Model-Output Contracts

> Executable contracts between agent step executors, model prompts, and server-side
> validators. Distilled from the r2 e2e campaign (runs 10–18, 2026-08-04 → 08-09),
> where seven consecutive chain breaks were all instances of the same defect class.

---

## Scenario: adding or changing any model-output constraint

### 1. Scope / Trigger

Applies whenever a step executor sends a prompt whose response is parsed into a
typed contract (`StructuredModelResponse`, `FindingModelResponse`, …) and any
validator/parser enforces a rule on that response. Every rule enforced server-side
is a **wire contract with the model** and must follow the two-tier defense below.

**The pattern (proven seven times in one batch):** a constraint enforced by a
validator but absent from the prompt WILL break the chain at temperature 0 — the
model cannot comply with rules it was never told, and the same input reproduces
the same violation on every retry.

### 2. Signatures

```java
// Tier 1 — prompt must declare the constraint, sourced from the SAME value the
// validator enforces (never a copied literal):
ReviewPlanValidator.defaultToolLimit()      // per-tool repetition cap (config: app.agent.plan.default-tool-limit)
PlanPolicy.remainingToolCalls()             // total plan budget
FindingSeverity.values()                    // legal severity list, joined into the prompt

// Tier 2 — server-side deterministic fallback, one of:
PlanPolicy(…, boolean clampOverBudget)      // over-budget items dropped + WARN, not fatal
AgentStepExecutionService catch (AiCallTransientException) // → RETRYABLE_PROVIDER_ERROR → scheduleRetry
ModelJsonOutputs.unwrapMarkdown(String)     // single shared fence-stripper for ALL parse paths
```

### 3. Contracts

- Schema strings shown to the model must spell out **every key of every nested
  object**. An empty-array example (`"claims":[]`) is not a contract — the model
  invented `{"claim": …}` against `CitedClaim(text, knowledgeBacked, citationIds)`
  the first time it populated one (run13).
- Prompt numeric limits must be interpolated from the enforcing source
  (`policy.remainingToolCalls()`, `validator.defaultToolLimit()`), zero literals.
- Receipt-style re-validation (final plan echo in `ExecutingToolsStepExecutor`)
  budgets against the echoed content size (`response.plan().size()`), never against
  leftover execution budget (`8 - requests.size()` rejected any plan ≥5 items).

### 4. Validation & Error Matrix

| Condition | Behavior |
|---|---|
| Intrinsic defect (unknown tool, missing required field, unknown JSON key) | hard error, step fails (`INVALID_MODEL_OUTPUT`) |
| Budget overrun of an intrinsically valid item (`clampOverBudget=true`) | drop item + `WARN` log with index/tool/reason; survivors proceed |
| Every item dropped from a non-empty response | hard error (garbage must not masquerade as a clean empty result) |
| Provider 429/5xx/timeout (`AiCallTransientException`) | `RETRYABLE_PROVIDER_ERROR` → `scheduleRetry` until `maxRetry` |
| Parse failure | error message and audit row MUST carry bounded cause (see error-handling.md) |

### 5. Good/Base/Bad Cases

- **Good**: prompt says "at most 8 items, same toolName at most 3 times" (sourced),
  model sends 5 items with a 4th repetition → item clamped, WARN logged, step green.
- **Base**: model output fenced in ```json → `ModelJsonOutputs.unwrapMarkdown`
  strips it on every parse path.
- **Bad** (forbidden): adding a validator rule without touching the prompt;
  duplicating the limit as a prompt literal; a second parse path with its own
  fence handling (see below).

### 6. Tests Required

- Prompt-content assertions pinning that the instruction text contains the
  runtime-sourced limits (`PlanningAndToolStepExecutorTest`) and nested-object
  key lists (claims / findings shapes).
- Clamp four-state tests: partial drop survives + WARN, total drop errors,
  clean passes untouched, empty input passes (`ReviewPlanValidatorTest`,
  `AgentFindingModelServiceTest`).
- Retry mapping tests: transient exception → `RETRY_SCHEDULED`; exhausted → `FAILED`
  (`AgentStepExecutionServiceTest`).

### 7. Wrong vs Correct

#### Wrong
```java
// Validator enforces limit 3; prompt says nothing (run11) — or says "3" as its own
// literal that drifts when config changes.
errors.add(prefix + "tool repetition exceeds limit " + limit);
```
#### Correct
```java
// One source feeds both tiers:
"… the same toolName may appear at most " + validator.defaultToolLimit() + " times …"
// and over-budget items are clamped, not fatal, when policy.clampOverBudget().
```

---

## Convention: one defense implementation per concern, shared by every code path

**What**: Any concern with two consumers (markdown fence stripping, reference
encoding, truncation) lives in exactly one place (`ModelJsonOutputs`,
`WorkspaceArchiveReference`).

**Why**: `AgentFindingModelService` was a second parse path without the fence
stripper `ModelOutputValidator` already had — the defense protected half the
traffic and the other half failed in production (run15). Same root cause as the
original F-03 archive-reference drift.

**Related**: for the cross-deployable variant (backend ↔ sandbox-runner), the
isomorphic-mirror-plus-golden-test pattern is specified in
`docs/adr/0001-工作区归档引用契约的单一事实源.md`.

---

## Convention: legal deployment postures degrade explicitly, never FAIL

**What**: When a posture signal shows a capability is absent, the dependent step
switches contract instead of failing:

| Posture signal | Switched contract |
|---|---|
| retrieved evidence empty (project has no ingested knowledge) | findings prompt demands `citationIds: []`; validator lets zero-citation findings live; fabricated citations still dropped |
| `installation.encryptedCredential` null/blank (local demo installation) | publication renders + persists (`status=PUBLISHED`, message `SCM delivery skipped: …`), remote delivery skipped with WARN |

**Why**: an empty citation whitelist plus a mandatory-citation rule is an
unsatisfiable constraint — every run on such a project fails forever (run16);
a credentialless installation cannot deliver to GitHub, and pretending otherwise
turns a legal posture into a permanent FAILED (run17).

**Rule**: the posture signal is the existing data (empty list, null credential) —
do not add config switches for what the data already states. Production paths
(knowledge present, credential present) must stay byte-identical.
