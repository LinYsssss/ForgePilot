# Error Handling

## Current state

The backend has no error-handling implementation. There is no exception type,
no `@ControllerAdvice`, no error mapper, and no API endpoint that could return
an error body; the only production classes are the bootstrap class and eight
`package-info` boundary markers.

Nothing here should be treated as an existing convention, because none exists
yet.

## Authoritative contract

The API error response shape is defined once in
[ARCHITECTURE.md](../../../docs/v2/ARCHITECTURE.md) §2.4 (`common` returns a
uniform body and Legacy error codes are not reused), and the mapping of
constraint conflicts to HTTP status is in §2.3. Use those sections as the
contract until this file describes real code.

## Ownership

This guide is filled by the phase that introduces `common.web`, together with
the first Controller that can actually fail. That change must record the
implemented exception types, the propagation pattern, the status mapping, and
the file that demonstrates each one — not aspirational rules.
