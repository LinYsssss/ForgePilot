-- ReviewContext includes the provider-reported pull-request title. Batch 2 used
-- the title while associating a PR with a requirement, but did not retain it in
-- the authoritative snapshot, which made a later human-triggered Review unable
-- to build the immutable context required by ARCHITECTURE.md 4.2.
--
-- The empty default is only a compatibility value for rows created before this
-- migration (and for low-level constraint fixtures that deliberately bypass the
-- SCM service). Every provider-synchronised row writes the current title.
ALTER TABLE pull_request
    ADD COLUMN title VARCHAR(512) NOT NULL DEFAULT '';
