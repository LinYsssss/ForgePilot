# P2 需求质量检查(体检)

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R3/A3,design §5/§6)。

## Goal

需求体检流水线:确定性规则层(零 token)→ 知识检索 → LLM 结构化分析(schema 校验,
不合格丢弃不落库)→ `requirement_quality_report` 落库;需求详情内嵌报告 UI;
Context Builder 场景化统一入口落地 REQUIREMENT_CHECK 场景。

## Requirements

- R1 V31 `requirement_quality_report(id, requirement_id fk cascade, round, report_json,
  model, total_tokens, created_at)`;round 需求内递增;历史保留。
- R2 `context/ContextBuilder`:`build(scene, projectId, refs) → ContextBundle`;
  本阶段实现 REQUIREMENT_CHECK(requirement+AC+知识 topK 检索);PR_REVIEW/ASSISTANT 留待后续。
- R3 确定性规则层 `RequirementRuleChecker`(纯代码):背景/描述缺失、AC 为空/过少、
  AC 含模糊词(尽量/适当/等等/大概/相关)、AC 不可测(无动词/无量化)、标题过短。
- R4 LLM 层:`RequirementCheckClient` 接口 + Mock(provider=mock,确定性输出)+
  OpenAiCompatible 实现;模板 `requirement-check-v1` 入 PromptTemplateRegistry,
  经 `AgentPromptAssembler.instruction` 组装(唯一组装入口);六维枚举与严重度枚举经槽位注入
  (模板禁写死);输出 schema 校验:未知维度/严重度即整体拒绝(AI_RESPONSE_INVALID),不落库。
- R5 六维:COMPLETENESS/CLARITY/TESTABILITY/EXCEPTION_COVERAGE/RULE_CONFLICT/RISK;
  报告项 {source∈{RULE,LLM}, severity∈{HIGH,MEDIUM,LOW}, message, suggestion}。
- R6 端点:POST `/requirements/{id}/check`(LEADER/DEVELOPER 触发,手动按钮,不做自动触发)
  → 跑全流水线返回报告;GET `/requirements/{id}/check-reports`(成员)→ 历史列表。
- R7 ai_call_log:新增 REQUIREMENT_CHECK 调用类型落日志(成功/失败),复用现有表。
- R8 前端:需求详情内嵌「体检」块——触发按钮(L/D)、最新报告六维展示(维度分组、
  严重度着色、RULE/LLM 来源标记)、历史轮次计数。

## Acceptance Criteria

- [ ] A1 mock 路径:体检返回六维结构化报告且规则层能指出 AC 缺失/模糊词;REVIEWER 触发 403。
- [ ] A2 模板 golden 测试:注册表可加载、槽位注入后无残留 %s、枚举来自服务端同源注入。
- [ ] A3 schema 校验:未知维度的模型输出被拒(AI_RESPONSE_INVALID)且不落库。
- [ ] A4 阶段末 mvn verify + npm test/build 全绿。

## Notes

- 真模型路径(A3 双路径的另一半)由用户部署后实测;代码路径与 mock 共用同一解析/校验。
- L 线首批标注不在本任务(内容工作,按周配额推进)。
