# P5 Finding 闭环与门禁扩展

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R6/R7,design §8/§9)。

## Goal

agent_finding 增生命周期闭环(与既有 pipeline 校验态 status 正交,新列 lifecycle_status);
指派与 fix commit 关联;自动复审**只产建议**(指纹匹配,永不自动 CLOSE);
run 级门禁三态 PASS/WARN/BLOCK(coverage + 闭环状态为新输入,SCM Conclusion 映射不变);
前端 `/quality` 质量中心(墨境新页)。

## Requirements

- R1 V35:agent_finding 增 lifecycle_status(默认 OPEN 回填)/assignee_id/fix_commit_sha/
  verified_by/verified_at/resolution_suggestion;agent_run 增 gate_verdict。
  既有 status(candidate/verified/rejected 校验轴)与 fingerprint(dedup 指纹,即身份指纹)不动。
- R2 生命周期:OPEN→CONFIRMED→IN_PROGRESS→FIXED→VERIFIED→CLOSED;OPEN/CONFIRMED→REJECTED;
  FIXED→IN_PROGRESS(验证打回)。角色:确认/驳回/验证/关闭 = REVIEWER 或 LEADER;
  开始修复/标记已修复 = 被指派人或 LEADER(FIXED 时可带 fix_commit_sha);指派 = LEADER,
  对象须项目成员。范围:仅 agent(PR)findings 入闭环;交互式报告保持报告制。
- R3 自动复审建议(FindingResolutionSuggester):新 run 发布时,按同 PR(installationId+
  PR#号,经 AgentScmContext)取历史 run 的活跃 findings(lifecycle ∉ {REJECTED, CLOSED}),
  指纹在新 run 的 verified findings 中存在 → STILL_PRESENT,不存在 → RESOLVED_SUGGESTED;
  只写 resolution_suggestion,终态永远人工;best-effort 静默。
- R4 run 级门禁 RunGateVerdictService:BLOCK = 存在 blocking 裁决且 lifecycle 未驳回/关闭;
  WARN = coverage 有 NOT_FOUND/AT_RISK,或存在未闭环 HIGH/CRITICAL,或 FIXED 未 VERIFIED;
  否则 PASS。发布时落 agent_run.gate_verdict 并在回写 notes 加一行;
  Conclusion 映射保持现状(BLOCK→ACTION_REQUIRED,PASS/WARN→SUCCESS)。
- R5 端点:GET /api/projects/{pid}/findings?lifecycle=&page=(成员,项目全量分页);
  POST /findings/{id}/lifecycle {action, fixCommitSha?};POST /findings/{id}/assign {userId};
  AgentFindingResponse 增生命周期字段(只加字段)。新 ErrorCode:FINDING_TRANSITION_ILLEGAL(409)。
- R6 前端:/quality 墨境新页(导航「质量中心」):生命周期过滤列表 + 详情行内动作
  (按角色裁剪)+ 指派下拉(成员名册)+ fix sha 输入 + 复审建议徽章。

## Acceptance Criteria

- [ ] A1 状态机全链路可走通;非法流转 409;角色负面用例(DEVELOPER 确认 403、
  非指派人标记 FIXED 403);REJECTED/CLOSED 终态。
- [ ] A2 建议器单测:指纹命中→STILL_PRESENT、未命中→RESOLVED_SUGGESTED、
  失败路径静默;永不改 lifecycle。
- [ ] A3 门禁单测:三态判定矩阵(blocking→BLOCK;coverage NOT_FOUND→WARN;全闭环→PASS)。
- [ ] A4 mvn verify + npm test/build 全绿。

## Notes

- finding.requirement_id 冗余列(工作台加速)随 P7 工作台一并评估,本任务不加。
