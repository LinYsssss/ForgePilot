-- Generated patches are immutable, head-bound, scope validated, and require explicit approval.
create table if not exists patch_candidate (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    head_sha varchar(80) not null,
    generator_model varchar(160) not null,
    prompt_version varchar(80) not null,
    patch_content text not null,
    patch_hash varchar(64) not null,
    status varchar(32) not null,
    validation_reason text,
    file_count integer not null,
    changed_lines integer not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_patch_candidate_counts check (file_count >= 0 and changed_lines >= 0)
);
create index if not exists idx_patch_candidate_run on patch_candidate(agent_run_id, id);
create unique index if not exists uq_patch_candidate_run_hash on patch_candidate(agent_run_id, patch_hash);

create table if not exists patch_candidate_finding (
    patch_candidate_id bigint not null references patch_candidate(id) on delete cascade,
    finding_id bigint not null references agent_finding(id) on delete cascade,
    primary key (patch_candidate_id, finding_id)
);

create table if not exists approval_request (
    id bigserial primary key,
    patch_candidate_id bigint not null references patch_candidate(id) on delete cascade,
    approver_id bigint,
    decision varchar(24) not null,
    patch_hash varchar(64) not null,
    head_sha varchar(80) not null,
    comment text,
    decided_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null
);
create index if not exists idx_approval_patch on approval_request(patch_candidate_id, id);
