-- Stable fingerprints and rejected-candidate audit data. Patch persistence moves to V11.

alter table agent_finding add column if not exists fingerprint varchar(64);
alter table agent_finding add column if not exists rejection_reason text;

create index if not exists idx_agent_finding_fingerprint on agent_finding(fingerprint);
create index if not exists idx_agent_finding_status on agent_finding(status, agent_run_id);
