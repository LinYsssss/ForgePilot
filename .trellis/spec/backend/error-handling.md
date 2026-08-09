# Error Handling

> How errors are handled in this project.

---

## Overview

<!--
Document your project's error handling conventions here.

Questions to answer:
- What error types do you define?
- How are errors propagated?
- How are errors logged?
- How are errors returned to clients?
-->

(To be filled by the team)

---

## Error Types

<!-- Custom error classes/types -->

(To be filled by the team)

---

## Error Handling Patterns

<!-- Try-catch patterns, error propagation -->

### Required: swallowed-cause messages must carry a bounded cause ("no blind errors")

Any `catch` that converts an exception into a summary message — step failures,
audit rows, `BusinessException` wrapping — MUST append a bounded slice of the
underlying cause. A bare summary makes production diagnosis impossible when the
payload itself is (by design) not retained.

**Contract**:

```java
// Bound the cause to 2_000 chars — matches the persistence caps of
// AgentStep.fail / AgentModelCall.fail. Prefer getMessage(); fall back to the
// exception's simple class name when the message is null/blank.
throw new AgentStepExecutionException(failureType,
        "finding model output is not schema-valid JSON: " + limit(ex.getOriginalMessage()));
call.fail("invalid finding model JSON: " + limit(detail));   // audit row too, not just the throw
```

**Incident record** (why this is a rule, not a preference): three blind errors in
one batch — `"finding model output is not schema-valid JSON"` with the parse
reason discarded left run15 undiagnosable from the DB (the audit table stores no
payload, so the discarded message was the only evidence); `"SCM publication failed"`
hid the null-apiBaseUrl cause (run17); `GitCliService.archiveLocked` dropped the
`IOException` behind a constant message (caught in review before it hurt).

**Test assertion point**: the thrown message AND the persisted audit/failure row
both contain a distinctive fragment of the injected cause
(`AgentFindingModelServiceTest`, `AgentPublicationServiceTest`).

### Required: transient provider failures map to the retryable failure type

`AiCallTransientException` (the AI clients' classification for 429/5xx/timeouts)
must reach `AgentFailureType.RETRYABLE_PROVIDER_ERROR`, never fall through a
generic `catch (RuntimeException)` into `INTERNAL_ERROR` (terminal). The step
retry machinery (`AgentStepCompletionService.recordFailure` → `scheduleRetry`)
only fires for `RETRYABLE_*` types — classification without mapping leaves the
whole retry stack dead code, and one provider hiccup kills the run (run14).
`AgentStepExecutionService.runClaimed` owns the catch; if a new dispatch path
around it appears, it needs the same catch and the same test pair
(retry scheduled / retries exhausted).

---

## API Error Responses

<!-- Standard error response format -->

(To be filled by the team)

---

## Common Mistakes

<!-- Error handling mistakes your team has made -->

(To be filled by the team)
