-- Delivery guards for the Agent outbox.
--
-- Before this migration a claim only stamped claimed_at, so a worker that died mid-publish left
-- the row PROCESSING forever with nothing able to reclaim it, and any late worker could overwrite
-- a newer claim's result. The claim token turns every write-back into a compare-and-set, and the
-- lease gives a reaper something to expire.
--
-- failed_at backs the new FAILED terminal state: without it an event with a permanently
-- unroutable routing key would be retried until the end of time.

alter table agent_outbox_event add column if not exists claim_token varchar(64);
alter table agent_outbox_event add column if not exists lease_expires_at timestamp(6) with time zone;
alter table agent_outbox_event add column if not exists failed_at timestamp(6) with time zone;

-- Drives the reaper query: PROCESSING rows whose lease has run out.
create index if not exists idx_agent_outbox_lease
    on agent_outbox_event (status, lease_expires_at);
