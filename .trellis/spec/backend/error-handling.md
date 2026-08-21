# Error Handling

## The single body

Every failure the API returns is `common.ApiError`:

```json
{ "code": "not_found", "message": "Resource not found.", "traceId": "…" }
```

There is no second shape. `ARCHITECTURE.md` §2.4 defines it and Legacy error
codes are never reused.

## Where failures are turned into that body

Two places, because they run at different times:

| Producer | Covers | File |
|---|---|---|
| `common.ApiExceptionHandler` (`@RestControllerAdvice`) | anything thrown from a Controller or Service | `common/ApiExceptionHandler.java` |
| The security filter chain's entry point and access-denied handler | 401 and 403 | `auth/SecurityConfig.java` |

The security answers **cannot** go through the advice: they are produced before
Spring MVC dispatches, so `@RestControllerAdvice` never sees them. Anything that
adds a new filter-chain rejection has to write the body itself, or that response
silently escapes the contract.

## Raising a failure

Services throw `common.ApiException`, which carries its own status and code:

| Factory | Status | Use for |
|---|---|---|
| `ApiException.notFound()` | 404 | the resource does not exist **or** the caller is not a member of its project |
| `ApiException.forbidden()` | 403 | the caller is a member but their role does not allow this |
| `ApiException.conflict(msg)` | 409 | refusals that need the resource's current state |
| `ApiException.unprocessable(msg)` | 422 | refusals decidable from the request body alone |

`notFound()` deliberately answers two different questions the same way. If a
non-member got 403 for an existing project and 404 for a missing one, the status
code would confirm that another project's id exists — see `design.md` §5 and
`BatchOneApiTest.anotherProjectsIdsAreInvisibleOverHttp`, which asserts the two
answers are indistinguishable. Never "improve" a 404 into a 403 on a read path.

The 409 / 422 split is the same one `api-contract.md` §0 states: could this have
been rejected by looking only at the request? Then 422. Did it need the row?
Then 409.

## Constraint violations

A database constraint conflict is **never caught and continued** ([D013.11]).
It rolls its transaction back and arrives at `ApiExceptionHandler` as
`DataIntegrityViolationException`, which maps to 409. The measured reason is in
`research/pg15-hibernate-constraints.md`: a constraint-trigger error puts the
PostgreSQL transaction into `25P02`, and even a JDBC savepoint cannot recover
it — the attempt fails later with `UnexpectedRollbackException`.

So services do not pre-check what a constraint already enforces. Adding a
second LEADER, reusing an SCM identity, or parenting a row across projects are
all left to the database, and the 409 comes back for free. Service-side checks
exist only where no constraint can express the rule — "at least one LEADER"
(D013.9) and "this revision is frozen".

## traceId

`ApiExceptionHandler` mints a UUID and logs it **with** the cause: the traceId
exists to join what the caller saw to what the log recorded, and a traceId that
leads to no log line is decoration. 5xx logs at `error` with the stack trace,
4xx at `warn` with the exception's own text; the exception message and constraint
names are never returned to the caller.

The three filter-chain bodies in `SecurityConfig` carry an **empty** traceId,
for two reasons: nothing is logged there, and `api-contract.md` §1 requires a
login failure to be byte-identical whether the username is unknown or the
password is wrong — a per-response random value would break that.

## Common mistakes to avoid

- Returning a Spring default error body (the `/error` page shape) from a new
  endpoint or filter. Nothing outside these two producers may answer.
- Catching `DataIntegrityViolationException` in a service to "handle it nicely".
  The transaction is already dead.
- Letting a message carry a constraint name, column name, or SQL fragment to the
  caller.
- Distinguishing "missing" from "forbidden" on any project-scoped read.
