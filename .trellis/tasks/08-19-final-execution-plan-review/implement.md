# Review procedure

This file is a review checklist, not an application implementation plan.

## Completed preparation

- [x] Run official Trellis initialization for Claude Code, Codex, and Pi.
- [x] Initialize developer identity `LinYsssss`.
- [x] Restore ForgePilot-specific instructions outside the Trellis-managed block.
- [x] Add a Claude Code pointer to the shared project rules.
- [x] Disable Trellis automatic commits and default Codex to inline work.
- [x] Consolidate the final execution candidate.
- [x] Change project status from “Phase 1 may start” to “awaiting user review”.

## User review

- [ ] Review the 12 decisions in `docs/v2/FINAL-EXECUTION-PLAN.md`.
- [ ] Record requested revisions, if any, in the authoritative PRD/Architecture/ADR first.
- [ ] Regenerate the candidate version only if a decision changes.
- [ ] Obtain explicit user approval.

## After approval only

- [ ] Archive this review task with the approved version noted.
- [ ] Create a new, separate Phase 1 Trellis task.
- [ ] Plan Phase 1 at task level and obtain its final implementation confirmation.
- [ ] Start Phase 1; stop again at the Phase 1 exit gate.

## Validation before presenting for review

```powershell
trellis platforms
python -X utf8 .\.trellis\scripts\get_context.py
python -X utf8 .\.trellis\scripts\task.py validate 08-19-final-execution-plan-review
git diff --check
git status --short
```

The final status must show no application source code or build manifests added.
