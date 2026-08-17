-- ForgePilot P1b(R2):Requirement + AcceptanceCriterion 域。
-- seq 是项目内人读编号(REQ-<seq>),事务内 max+1 取号,唯一约束兜底并发;
-- AC 随需求整体编辑(进入开发后修改需先回退状态,守卫在应用层状态机)。
-- assignee_id/created_by 不加 FK:与 project_member.user_id 同口径(用户删除策略未定)。
create table if not exists requirement (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    seq bigint not null,
    title varchar(200) not null,
    background text,
    description text,
    priority varchar(16) not null,
    assignee_id bigint,
    status varchar(32) not null,
    created_by bigint not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    constraint uq_requirement_project_seq unique (project_id, seq),
    constraint ck_requirement_priority check (priority in ('HIGH', 'MEDIUM', 'LOW')),
    constraint ck_requirement_status check (status in (
        'DRAFT', 'NEEDS_IMPROVEMENT', 'READY', 'IN_DEVELOPMENT', 'IN_REVIEW', 'DONE', 'CANCELED'))
);

create index if not exists idx_requirement_project on requirement(project_id, seq desc);
create index if not exists idx_requirement_assignee on requirement(assignee_id);

create table if not exists acceptance_criterion (
    id bigserial primary key,
    requirement_id bigint not null references requirement(id) on delete cascade,
    seq integer not null,
    text varchar(2000) not null,
    constraint uq_acceptance_criterion_seq unique (requirement_id, seq)
);

create index if not exists idx_acceptance_criterion_requirement on acceptance_criterion(requirement_id, seq);
