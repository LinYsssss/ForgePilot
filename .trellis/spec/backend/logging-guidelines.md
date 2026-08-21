# Logging Guidelines

## Current state

The backend has no logging implementation and no logging convention. There is
no logger declaration, no logging configuration file, and no log format,
correlation field, or level policy in the repository. The only log output today
is Spring Boot's own startup logging under the framework defaults.

Nothing here should be treated as an existing convention, because none exists
yet.

## Rules that already apply

- Never log credentials, tokens, or any value read from
  `FORGEPILOT_DB_PASSWORD`. This also applies to CI output and captured
  evidence; see [quality-guidelines.md](./quality-guidelines.md).
- The error/trace fields returned to clients are defined in
  [ARCHITECTURE.md](../../../docs/v2/ARCHITECTURE.md) §2.4, not here.

## Ownership

This guide is filled by the phase that introduces a real logging need,
alongside `common.web`. That change must record the actual levels, structure,
and correlation fields it implements, and point at the code that produces them.
