# Logging Guidelines

## What exists

Exactly one logger in the whole backend:

```java
// common/ApiExceptionHandler.java
private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
```

Nothing else logs. `auth`, `project` and `requirement` contain no logger, no
`slf4j` import, and no print statement, and that is deliberate rather than an
oversight — see "Why almost nothing logs" below.

There is no logging configuration file, no MDC, no structured/JSON encoder and
no correlation filter. Log format and appenders are Spring Boot's defaults.

## Levels and the correlation field

`ApiExceptionHandler` is the only producer, and it follows one rule:

| Response | Level | What is written |
|---|---|---|
| 5xx | `error` | status, code, traceId, and the exception with its stack trace |
| 4xx | `warn` | status, code, traceId, and `exception.toString()` — no stack trace |

The `traceId` is a UUID minted **in the same statement that logs the cause**, and
the same value is returned to the caller in the `ApiError` body. That is the
whole point of the field: it joins what the caller saw to what the log recorded.

A traceId that appears in a response but in no log line is worse than no
traceId, because it invites someone to search for something that was never
written. This is why the three security filter-chain responses in
`auth/SecurityConfig.java` carry an **empty** traceId: they log nothing, by
design, and an empty value says so honestly. (They also must stay byte-identical
across the two login failure modes, which a per-response random value would
break — see `api-contract.md` §1.)

## Why almost nothing logs

Log lines are code: they need a reason, they go stale, and they leak. This
project adds them only where something is genuinely lost otherwise. At MVP scale
a failure either reaches the caller as an `ApiError` — in which case the handler
logged it — or it did not happen.

So: **do not add a logger to a feature package** to trace normal flow, to record
that a method was entered, or to "help debugging later". If a failure matters,
raise it; if it reaches a client, it is already logged. Requests are not
access-logged by the application: the container in front of it is where that
belongs.

`grep -rn "Logger\|slf4j" backend/src/main/java | grep -v "^.*/common/"` returning
nothing is a real invariant of this codebase, not a coincidence.

## Never log

- Passwords, password hashes, and anything derived from `FORGEPILOT_DB_PASSWORD`.
  `AuthApiTest` asserts after every request that no response body contains the
  raw password or the bcrypt hash; the same discipline applies to log output,
  where no test can see it.
- SCM tokens and webhook secrets, encrypted or not.
- Constraint names, column names and SQL fragments in anything returned to a
  caller. They may appear in a `warn`/`error` line, which is exactly why the
  handler logs the cause and returns a fixed message instead.
- This applies equally to CI output and to captured evidence files; see
  [quality-guidelines.md](./quality-guidelines.md).

## `ai_call_log` is not logging

Batch 2 introduces an `ai_call_log` **table**. It is persisted evaluation and
fault-localisation data with its own schema, retention and project scoping — not
an appender, not a log level, and not governed by this guide. Do not route it
through slf4j, and do not let its prompt/response payloads reach the application
log.
