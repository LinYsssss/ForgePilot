-- ForgePilot P3(R4):需求-代码关联。project_id 冗余存储供反查(ref→需求)免 join;
-- unique(requirement_id, type, ref) 让重复提取幂等(upsert 以约束兜底)。
create table if not exists requirement_link (
    id bigserial primary key,
    project_id bigint not null,
    requirement_id bigint not null references requirement(id) on delete cascade,
    link_type varchar(32) not null,
    ref varchar(512) not null,
    source varchar(16) not null,
    created_at timestamp(6) with time zone not null,
    constraint uq_requirement_link unique (requirement_id, link_type, ref),
    constraint ck_requirement_link_type check (link_type in ('BRANCH', 'COMMIT', 'PULL_REQUEST')),
    constraint ck_requirement_link_source check (source in ('AUTO', 'MANUAL'))
);

create index if not exists idx_requirement_link_requirement on requirement_link(requirement_id);
create index if not exists idx_requirement_link_lookup on requirement_link(project_id, link_type, ref);
