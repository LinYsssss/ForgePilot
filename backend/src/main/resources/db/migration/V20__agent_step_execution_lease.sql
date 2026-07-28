-- Execution lease for Agent steps.
--
-- Step execution used to run inside one long transaction that also held a pessimistic row lock,
-- so a Git clone or an LLM call kept a database connection and a lock for as long as it took.
-- Splitting that into claim / execute / complete means the row is unlocked while the external
-- call runs, which in turn means a second worker can legitimately take the step over. The
-- execution token is what lets the completing worker prove it is still the rightful holder.
--
-- worker_id is diagnostic only: it answers "which process was holding this" when a lease expires.

alter table agent_step add column if not exists execution_token varchar(64);
alter table agent_step add column if not exists worker_id varchar(120);
alter table agent_step add column if not exists lease_expires_at timestamp(6) with time zone;

-- Drives the watchdog: RUNNING steps whose lease is no longer being renewed.
create index if not exists idx_agent_step_lease
    on agent_step (status, lease_expires_at);
