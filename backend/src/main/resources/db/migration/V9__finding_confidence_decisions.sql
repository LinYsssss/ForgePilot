-- Versioned, explainable confidence decisions. Patch persistence moves to V10.

create table if not exists finding_decision (
    id bigserial primary key,
    finding_id bigint not null references agent_finding(id) on delete cascade,
    weight_version varchar(80) not null,
    threshold double precision not null,
    confidence double precision not null,
    blocking boolean not null,
    reason varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_finding_decision_threshold check (threshold >= 0 and threshold <= 1),
    constraint ck_finding_decision_confidence check (confidence >= 0 and confidence <= 1)
);

create index if not exists idx_finding_decision_finding on finding_decision(finding_id, id);

create table if not exists finding_score_contribution (
    id bigserial primary key,
    decision_id bigint not null references finding_decision(id) on delete cascade,
    factor varchar(40) not null,
    weight double precision not null,
    signal double precision not null,
    contribution double precision not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_finding_contribution_signal check (signal >= 0 and signal <= 1)
);

create index if not exists idx_finding_contribution_decision
    on finding_score_contribution(decision_id, id);
