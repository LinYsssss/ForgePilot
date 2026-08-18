# ForgePilot repository migration

## Summary

- Migration date: **2026-08-18**
- New primary repository: `LinYsssss/ForgePilot`
- Preserved legacy repository: `LinYsssss/reposage`
- Legacy main: `ce83abfbc764ae88a4a62e46a02446ea130382ee`
- Rewritten ForgePilot main before this migration-record commit: `2e8a16e8d8e22d905f8f10d701b3b43099889505`
- Rewritten historical commits: **381**
- Migrated branches: **11**
- Migrated tags: **0**

## Identity rewrite

Only the ForgePilot copy was rewritten. Every historical commit now has:

```text
Author:    LinYsssss <153968692+LinYsssss@users.noreply.github.com>
Committer: LinYsssss <153968692+LinYsssss@users.noreply.github.com>
```

All historical commit SHAs changed. The original RepoSage repository was not force-pushed, renamed, archived, or deleted. Original signatures are no longer valid in the rewritten copy.

## Preservation verification

The migration generated an old-to-new commit map for all 381 commits and verified every mapped commit:

- tree object unchanged
- full commit message unchanged
- author and committer timestamps unchanged
- parent topology preserved through the commit map
- no commit dropped
- Author and Committer identity fully normalized

Verification result: **381/381 passed, 0 errors**.

Evidence is stored under `.trellis/tasks/08-17-p9-brand-finalization/migration/`.

## Branch note

The local and old-remote versions of `fix/track-b-boundary` had different tips. The old remote tip retains the original branch name; the local tip is preserved as `local-only/fix/track-b-boundary`, so neither line of work was lost.

## Future development

Use a fresh ForgePilot clone for new development because the existing RepoSage working copy retains the original SHA history:

```bash
git clone https://github.com/LinYsssss/ForgePilot.git
cd ForgePilot
```
