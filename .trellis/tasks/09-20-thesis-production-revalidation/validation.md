# Validation — 2026-09-20

## Source reconciliation

The compact CSV was produced by joining the archived production Review API
responses with read-only PostgreSQL aggregates for Reviews 17–31. Generation
asserted the same 15 Review IDs and the same Finding count in both sources.
Per-case endpoint duration retains 0.001 ms precision; this avoids a 1 ms
difference caused by rounding every row before summing.

The resulting dataset contains 15 data rows and 42 columns. It contains only
identifiers, statuses, counts, latency, token usage, coverage counts, consistency
flags, and engine/version/model names. It contains no credential, browser state,
header, prompt, response prose, knowledge excerpt, file path, patch, or Finding
text.

## Checks

- `python3 docs/thesis/verify-production-revalidation.py` passed. It recomputed
  all summary values, asserted every status/version/identity invariant, checked
  the CSV SHA-256, and checked the key values printed in the thesis narrative.
- Local Markdown link validation passed for the root index, defense guide,
  thesis files, and archived operational record.
- `git diff --check` passed.
- `git diff --name-only -- evaluation` returned no path.
- `formal_evaluation.py verify-freeze` passed with freeze hash
  `55fc3176b6c843d214aa405781c1fe404ee7663c3e177f8a3cd67d9b22021e5d`.
- `postfreeze_provider_correction.py verify` passed with correction hash
  `8c2401958679866753b87926d8c1b28939a2390189c612b3421409139734100c`.

## Cleanup

- Removed the 276,805-byte tracked full-response JSON. The compact CSV is 3,441
  bytes and is independently reproducible through the checked script.
- Removed `/tmp/forgepilot-call-chain-revalidation-20260920`, including the
  restart driver, browser state, duplicate response files, and generated arrays.
- Removed temporary database CSV and generation script after the repository
  verifier passed without them.
- Retained both private, verified PostgreSQL backups and all historical task,
  deployment, decision, and formal-evaluation evidence.

No application code or production data was changed by this documentation task.
