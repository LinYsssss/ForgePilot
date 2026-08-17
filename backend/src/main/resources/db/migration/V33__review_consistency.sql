-- ForgePilot P4a(R5):一致性审查。
-- review_task.flags_json:五臂 feature flags 快照(异步消费与实验复现都要按下单时的组合执行;
-- null = 生产默认全开)。review_report.coverage_json:AC 覆盖三态结论区块(null = 无关联需求
-- 或判定失败,findings 主链路不受影响)。幂等键与既有列一律不动。
alter table review_task add column if not exists flags_json varchar(512);
alter table review_report add column if not exists coverage_json text;
