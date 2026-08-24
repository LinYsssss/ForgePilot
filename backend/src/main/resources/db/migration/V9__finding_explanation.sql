-- T-010: an AI Review Finding carried no readable problem statement, no
-- remediation advice and no recorded model confidence. The root cause sat one
-- step further upstream than the report suggested -- the model output schema in
-- ReviewPrompts never asked for any of them -- so this is prose that was never
-- produced, not prose that was produced and never shown.
--
-- All four columns are nullable, and that is a statement of fact rather than
-- leniency: the findings written before this migration genuinely carry no
-- explanation. A NOT NULL column with an empty default would forge an empty
-- explanation onto the existing rows and make "the model said nothing"
-- indistinguishable from "the model said the empty string".
--
-- None of these four may ever reach finding_key, evidence_hash or basis_hash.
-- V6 states that rule for the two hashes and it is unchanged here. category is
-- already an input to finding_key; this migration does not touch how it is
-- computed there, it only stops discarding the value afterwards. The prose and
-- the confidence band are the only outputs in this table allowed to move with
-- the model's wording, which is precisely why the hashes must not see them: a
-- suppression that drifts with wording is a suppression that has quietly
-- stopped working, and it stays invisible until the next review round.
ALTER TABLE finding
    ADD COLUMN category    VARCHAR(32),
    ADD COLUMN explanation TEXT,
    ADD COLUMN suggestion  TEXT,
    ADD COLUMN confidence  VARCHAR(16);

-- Both vocabularies are closed, and both tolerate NULL so the rows above stay
-- legal. These CHECKs are the last line of defence, not the first:
-- ReviewOutputValidator maps the model's answer onto both vocabularies before
-- the insert, because a value that reaches the constraint aborts the entire
-- batch insert rather than the single offending row -- the same trap V6
-- documents for ck_finding_matches_review_context.
--
-- The consequence is that a CHECK list disagreeing with the enum declared in
-- ReviewPrompts is worse than no CHECK at all: it would reject a legitimate
-- category and take every other finding in that batch down with it. The two
-- lists are asserted equal in ReviewPipelineIntegrationTest.
ALTER TABLE finding
    ADD CONSTRAINT ck_finding_category CHECK (category IS NULL OR category IN (
        'CORRECTNESS', 'SECURITY', 'ERROR_HANDLING', 'CONCURRENCY', 'PERFORMANCE',
        'API_CONTRACT', 'TEST_COVERAGE', 'MAINTAINABILITY', 'REQUIREMENT_GAP')),
    ADD CONSTRAINT ck_finding_confidence CHECK (confidence IS NULL OR confidence IN (
        'HIGH', 'MEDIUM', 'LOW'));
