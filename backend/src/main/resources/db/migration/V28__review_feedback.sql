-- 反馈闭环(生产加固 R3):人工对 Agent 审查结果的裁定落库,作为 r8 提示词回灌(人工策展)的输入。
-- 为什么单独建表而不复用旧 feedback 表:旧表挂在 review_issue(旧 UI 审查报告)上,
-- 而这里裁定的对象是 Agent Run 与 agent_finding,且多出「漏报补录」这一无 finding 可挂的形态。
-- 三种类型共用一张表:误报/确认必须挂 finding_id;漏报(MISS_REPORT)没有 finding,
-- 用 file_path+line_number+category+note 描述遗漏位置。组合语义由 check 约束在库层钉死,
-- 应用层校验失效时坏数据也进不来(反馈是语料输入,脏行会污染回灌)。
-- 版本号说明:V22-V25 为预留段(见 .trellis/spec/backend/database-guidelines.md),接 V27 之后用 V28。
create table if not exists review_feedback (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    finding_id bigint references agent_finding(id) on delete cascade,
    feedback_type varchar(32) not null,
    file_path varchar(1024),
    line_number integer,
    category varchar(160),
    note varchar(2000) not null,
    reporter_id bigint not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_review_feedback_target check (
        (feedback_type in ('FINDING_FALSE_POSITIVE', 'FINDING_CONFIRMED') and finding_id is not null)
        or (feedback_type = 'MISS_REPORT' and finding_id is null and file_path is not null)
    ),
    -- 行号正数与 V8 的 ck_agent_finding_lines 同口径;同时保护下方 MISS_REPORT 唯一索引的
    -- -1 哨兵值:合法数据永远取不到 -1,哨兵不会与真实行号撞键。
    constraint ck_review_feedback_line check (line_number is null or line_number > 0)
);

create index if not exists idx_review_feedback_run on review_feedback(agent_run_id, id);

-- 应用层 upsert(同幂等键重复提交覆盖旧内容)的并发安全网:find-then-save 在并发下会双双
-- 未命中然后各插一行,唯一索引让后到者失败而不是留下重复行(反馈是语料输入,重复行会污染回灌)。
-- 误报/确认的幂等键 = reporter+finding+type:
create unique index if not exists uq_review_feedback_finding_reporter_type
    on review_feedback(finding_id, reporter_id, feedback_type) where finding_id is not null;

-- 漏报的幂等键退化为 run+reporter+path+line(finding_id 恒为空)。line_number 可空,直接用列
-- 钉不住(唯一索引对 null 视为互不相同),用 coalesce(line_number, -1) 表达式把「未填行号」
-- 归一为哨兵值参与唯一判定——语义与应用层 Objects.equals 的内存匹配完全一致。
create unique index if not exists uq_review_feedback_miss_location
    on review_feedback(agent_run_id, reporter_id, file_path, coalesce(line_number, -1))
    where feedback_type = 'MISS_REPORT';
