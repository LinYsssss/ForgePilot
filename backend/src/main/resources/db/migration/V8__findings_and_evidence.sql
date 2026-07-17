-- Phase 4 finding candidates and their versioned, bounded evidence.
-- V8 is used because V7 is already occupied by SCM webhook persistence.

create table if not exists agent_finding (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    severity varchar(24) not null,
    category varchar(160) not null,
    title varchar(255) not null,
    description text not null,
    file_path varchar(1024),
    line_start integer,
    line_end integer,
    symbol varchar(512),
    status varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_agent_finding_lines check (
        (line_start is null and line_end is null)
        or (line_start > 0 and line_end >= line_start and file_path is not null)
    )
);

create index if not exists idx_agent_finding_run on agent_finding(agent_run_id, id);
create index if not exists idx_agent_finding_category on agent_finding(category, severity);

create table if not exists finding_evidence (
    id bigserial primary key,
    finding_id bigint not null references agent_finding(id) on delete cascade,
    evidence_type varchar(40) not null,
    source_version varchar(160) not null,
    file_path varchar(1024),
    line_start integer,
    line_end integer,
    excerpt varchar(2048) not null,
    score double precision not null,
    content_hash varchar(64) not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_finding_evidence_score check (score >= 0 and score <= 1),
    constraint ck_finding_evidence_lines check (
        (line_start is null and line_end is null)
        or (line_start > 0 and line_end >= line_start and file_path is not null)
    )
);

create index if not exists idx_finding_evidence_finding on finding_evidence(finding_id, id);
create index if not exists idx_finding_evidence_hash on finding_evidence(content_hash);
