-- ForgePilot P2(R3):需求体检报告。round 需求内递增(历史保留,论文可展示改进过程);
-- report_json 为六维结构化报告(规则层+LLM 层合并,schema 校验通过才落库)。
create table if not exists requirement_quality_report (
    id bigserial primary key,
    requirement_id bigint not null references requirement(id) on delete cascade,
    round integer not null,
    report_json text not null,
    model varchar(160) not null,
    total_tokens integer not null default 0,
    created_at timestamp(6) with time zone not null,
    constraint uq_requirement_quality_round unique (requirement_id, round)
);

create index if not exists idx_requirement_quality_requirement
    on requirement_quality_report(requirement_id, round desc);
