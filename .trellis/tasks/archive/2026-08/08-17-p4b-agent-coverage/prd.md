# P4b Agent 链路一致性接入

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R5 后半,design §7)。
> 范围决策:/reviews /agent /pull-requests 三页迁墨境按 D3("被触碰 Phase 顺势迁移,
> P7 收尾兜底")归入 P7——本任务只做 Agent 守门链路的 coverage 接入。

## Goal

webhook 守门链路在发布结果前完成 AC 覆盖判定:复用 P4a 的 CoverageJudgeService
(同一模板/解析/证据校验),结论落 `agent_run.coverage_json`(P5 门禁扩展的输入),
SCM 回写评论附每条 AC 的三态摘要行(A5 的 webhook 路径)。

## Requirements

- R1 CoverageJudgeService 抽出通用入口 `judgeForRefs(projectId, prRef, branchName, diffText,
  shardSummaries, verify)`;交互式路径(ReviewTask)委托同一实现,两链路零分叉。
- R2 V34 `agent_run.coverage_json`;AgentRun 实体 attachCoverage/getCoverageJson。
- R3 `AgentCoverageService`(agent/orchestration):run → AgentScmContext(PR#号/base/head)
  → 绑定仓库 diff(GitCliService,backend 本地克隆)→ judgeForRefs → 落 run →
  返回人读摘要行;**任何失败静默降级**(无 coverage 行,发布主链路零影响)。
- R4 AgentPublicationService.publish 发布前调用 R3,摘要行并入 ReviewPublication notes
  (只动列表内容,不动冻结的载荷结构/Conclusion 映射);门禁语义不变(P5 才扩展)。

## Acceptance Criteria

- [ ] A1 单测:AgentCoverageService 失败路径(无 context/无仓库/diff 失败/判定失败)全部
  静默返回空行集且不抛;成功路径落 run + 摘要行格式正确。
- [ ] A2 mvn verify 全绿(webhook/publication 存量测试零回归)。

## Notes

- AgentRun 时间线的 coverage 可视化随 P7 页面迁移落地(数据已在 run 上)。
