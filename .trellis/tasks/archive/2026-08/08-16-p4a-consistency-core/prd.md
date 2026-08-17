# P4a 需求一致性审查核心

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R5/A5,design §5/§7)。
> P4 拆两半:本任务做**交互式审查链路**的一致性判定核心与实验 flags 预埋;
> P4b 做 Agent 链路 coverage 步骤与 /reviews /agent /pull-requests 三页迁墨境。

## Goal

分片审查合并阶段的 AC 覆盖判定:每条 AC 产出三态结论(COVERED/NOT_FOUND/AT_RISK)+
证据引用(伪造引用丢弃并降级 AT_RISK);五臂 feature flags 预埋(生产与实验同一代码路径);
报告新增 coverage 区块。

## Requirements

- R1 `ReviewFeatureFlags{knowledge, requirementContext, evidenceVerification}`:
  默认全开(生产);`CreateReviewTaskRequest` 增可选 flags,V33 `review_task.flags_json`
  持久化(异步消费时可复现);五臂 = Baseline(全关)/A(+knowledge)/B(+req&ac)/C(A+B)/
  D(C+verify)。knowledge=false 时跳过 RAG 注入(Baseline 臂纯 diff)。
- R2 需求解析:task.pullRequestId → PR#号 → requirement_link 反查;无 PR 则 branchName →
  BRANCH link;命中多条取第一条(记日志);无关联需求 → 纯质量审查(现状,向后兼容)。
- R3 合并阶段单独一次判定调用(不逐分片注入需求上下文——token 放大 + 全局视角,design §5):
  输入 = requirement + AC + 分片结论摘要 + 有界 diff 片段(默认 8000 字符);
  模板 `coverage-judge-v1` 入注册表,枚举槽位同源注入。
- R4 输出 schema `coverage[{acId, verdict, evidence[{filePath,lineStart,lineEnd,note}], rationale}]`:
  未知 acId/verdict 整体拒绝(AI_RESPONSE_INVALID);evidenceVerification 开启时
  evidence.filePath 不在 diff 文件集内 → 丢该条证据,COVERED 失去全部证据 → 降级 AT_RISK
  并在 rationale 标注;AC 全量补齐(模型漏掉的 AC 记 NOT_FOUND + 标注)。
- R5 客户端:Mock(确定性:AC 关键词命中 diff → COVERED,模糊词 AC → AT_RISK)+
  OpenAiCompatible;共用解析校验;ai_call_log 新增 COVERAGE_JUDGE 类型。
- R6 持久化与响应:V33 `review_report.coverage_json`;ReviewReportDetail 增 coverage 字段;
  判定失败不阻塞报告落库(coverage 缺席 + 日志,findings 主链路优先)。
- R7 前端:旧壳 ReviewsView 报告详情增 coverage 区块(AC 三态徽章 + 证据 + 理由);
  迁墨境随 P4b。

## Acceptance Criteria

- [ ] A1 flags 单测覆盖五臂组合语义(解析/默认/组合正确)。
- [ ] A2 coverage golden:模板加载无残留槽;未知 acId 拒绝;伪造引用丢弃且 COVERED→AT_RISK;
  漏判 AC 补 NOT_FOUND。
- [ ] A3 mvn verify + npm test/build 全绿(存量审查行为零回归:flags 缺省=全开,
  无关联需求路径与现状逐字节一致)。

## Notes

- Agent 链路(webhook 守门)的 coverage 步骤与时间线展示在 P4b;两条链路共用判定服务。
