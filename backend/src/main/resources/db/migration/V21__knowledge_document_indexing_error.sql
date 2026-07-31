-- Persist why a knowledge document failed to index.
--
-- Upload used to run inside one transaction that marked the document FAILED and then rethrew, so
-- the rollback took the status change with it — and the document row too. A user saw a 500 and an
-- empty list, with nothing recorded about what went wrong. The status transition now commits in
-- its own transaction, and this column carries a sanitised reason for the operator.

alter table knowledge_document add column if not exists index_error text;
