-- ForgePilot P4b(R5):Agent 守门链路的 AC 覆盖结论落 run。
-- P5 门禁扩展(acNotFoundCount/acAtRiskCount)以此为输入;null = 无关联需求或判定失败。
alter table agent_run add column if not exists coverage_json text;
