-- ForgePilot P5(R6/R7):Finding 生命周期闭环与 run 级门禁三态。
-- lifecycle_status 与既有 status(candidate/verified/rejected 校验轴)正交:
-- 校验轴回答"这条发现可信吗",生命周期轴回答"处理到哪一步了"。存量行回填 OPEN。
-- fingerprint(dedup 指纹)复用为跨轮身份指纹,不另建列。
alter table agent_finding add column if not exists lifecycle_status varchar(32) not null default 'OPEN';
alter table agent_finding add column if not exists assignee_id bigint;
alter table agent_finding add column if not exists fix_commit_sha varchar(80);
alter table agent_finding add column if not exists verified_by bigint;
alter table agent_finding add column if not exists verified_at timestamp(6) with time zone;
-- 自动复审建议(RESOLVED_SUGGESTED/STILL_PRESENT):自动侧只写这里,终态永远人工。
alter table agent_finding add column if not exists resolution_suggestion varchar(32);

create index if not exists idx_agent_finding_lifecycle on agent_finding(lifecycle_status);
create index if not exists idx_agent_finding_run_status_lifecycle
    on agent_finding(agent_run_id, status, lifecycle_status);
create index if not exists idx_agent_scm_context_pr_history
    on agent_scm_context(installation_id, pull_request_number, created_at, agent_run_id);

-- run 级门禁三态(PASS/WARN/BLOCK),发布时计算落库;SCM Conclusion 映射保持现状。
alter table agent_run add column if not exists gate_verdict varchar(16);
