# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

(To be filled by the team)

---

## Required Patterns

<!-- Patterns that must always be used -->

(To be filled by the team)

---

## Testing Requirements

<!-- What level of testing is expected -->

### Spring context tests must not write outside the workspace or tmpdir

CI runners are **non-root**; local Maven containers run as root. Any bean that
creates directories at startup (e.g. `Files.createDirectories` in a constructor)
will pass locally and fail in CI with `AccessDeniedException` if its configured
path defaults to an absolute system location (the F-02 incident: default
`/app/archives` kept `main` red for 12 days).

Rule: every `@SpringBootTest` must override path-like properties to a tmpdir,
following the existing inline-properties style:

```java
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.sandbox.signing-secret=test-signing-secret",
        // CI runner 非 root,默认 /app/archives 会因 AccessDeniedException 拉不起上下文(F-02)
        "app.sandbox.archive-root=${java.io.tmpdir}/reposage-test-archives"
})
```

`${java.io.tmpdir}` is resolved by the Spring `Environment` (systemProperties
source), so it works in inline test properties. When adding a new configurable
path, add the override to the affected context tests in the same change.

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)
