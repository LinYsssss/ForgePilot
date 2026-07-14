-- Project completed Agent Runs into the legacy review_report table so the existing frontend keeps
-- working. Agent-produced reports have no legacy review_task, so task_id becomes nullable and a
-- unique agent_run_id is the idempotent projection key: one legacy report per Agent Run.
alter table review_report add column if not exists agent_run_id bigint;
alter table review_report alter column task_id drop not null;
create unique index if not exists uq_review_report_agent_run on review_report(agent_run_id);
