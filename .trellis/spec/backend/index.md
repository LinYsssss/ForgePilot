# Backend Development Guidelines

> Conventions the backend actually implements today.

---

## Overview

`backend/` is one Spring Boot modular monolith rooted at `com.forgepilot`.
Module boundaries, dependency direction, the data model, and naming rules are
defined once in [ARCHITECTURE.md](../../../docs/v2/ARCHITECTURE.md) §1, §2 and
§7. These guides do not repeat that content; they record the build, layout,
database, test, and configuration conventions this repository enforces, and
name the file or command that enforces each one.

The implementation covers the Phase 1 foundation, batches 1–3, Phase 8, and
the product flow: local accounts, projects, requirements,
knowledge/attachments, SCM, the AI gateway, the single Review Engine and its
human decision loop. These guides describe the resulting runtime conventions.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Actual `backend/` layout and package boundaries | Active |
| [Database Guidelines](./database-guidelines.md) | Flyway authority and the real-PostgreSQL test contract | Active |
| [Quality Guidelines](./quality-guidelines.md) | Executable build, architecture, scope, and configuration checks | Active |
| [Error Handling](./error-handling.md) | `ApiError` production in MVC and the security chain | Active |
| [Logging Guidelines](./logging-guidelines.md) | Single-handler logging and secret exclusions | Active |

---

## Pre-development checklist

- [ ] Pick the owning top-level package and the allowed dependency direction
      from `ARCHITECTURE.md` §1 before creating any class.
- [ ] Read [directory-structure.md](./directory-structure.md) so the new files
      land in the real layout rather than a new parallel tree.
- [ ] Read [database-guidelines.md](./database-guidelines.md) before adding a
      migration, a datasource property, or a database test.
- [ ] Read [quality-guidelines.md](./quality-guidelines.md) for the build entry
      point, the architecture rules, and the credential/exposure rules.

---

## Quality check

- [ ] `cd backend && ./mvnw -B -ntp verify` passes, including the ArchUnit
      suite and the real PostgreSQL/pgvector test.
- [ ] The scope and configuration checks in
      [quality-guidelines.md](./quality-guidelines.md) produce their expected
      output.
- [ ] Any new convention introduced by the change is added to the relevant
      guide in the same change, with a file or command anchor. A rule that no
      code follows does not belong here; remove it or implement it.

---

**Language**: All documentation should be written in **English**.
