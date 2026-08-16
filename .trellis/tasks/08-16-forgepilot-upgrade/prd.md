# ForgePilot 平台升级 — 总体 PRD(父任务)

> 本任务是 ForgePilot 升级的**父任务**:持有需求基线、阶段地图与跨子任务验收标准。
> 各 Phase 进场时以 `--parent` 创建子任务承接实现;本任务自身不直接承载编码工作。
> 源方案:`ForgePilot_最终方案.md`(V1.0 冻结版)。本 PRD 吸收 2026-08-16 审阅修订与用户决策,
> **与源方案冲突处以本 PRD 为准**。

## 1. 目标与价值

把 RepoSage(交互式审查 + PR 守门 Agent)升级为 **ForgePilot 智能研发质量平台**,作为毕业设计
《基于大模型的智能研发质量平台设计与实现》的系统与实验载体。

- 产品主线:需求 → 需求质量检查 → 任务分配 → 实现 → PR 智能审查 → 缺陷闭环 → 质量门禁 → 度量。
- 论文主线:**研发上下文增强的智能代码审查方法**(Requirement + AC + 项目知识 + 代码上下文 + Diff),
  以五臂消融实验(Baseline/A/B/C/D)验证有效性。
- 范围原则(源方案 §33):新功能必须直接服务主链,否则不做。

## 2. 现状事实(2026-08-16 逐项核查)

**已建成、直接复用:**

- PR 守门链路:webhook 验签去重 → AgentRun 状态机 → Outbox/MQ → 沙箱取证 → Findings(证据+置信度)
  → 门禁(`agent/orchestration/AgentFindingPipeline.java:94` 调 `finding/GateDecisionService`)→ 回写 PR。
- Context 雏形:`context/ReviewContextService.java` + `HybridContextRanker`。
- 知识库 + RAG(全量注入 / pgvector 双模)、大 Diff 分片审查、结构化输出逐级校验(含引用校验)。
- 指标:Prometheus + `ai_call_log`(Token/耗时/成功率)。
- 评测:`evaluation/manifest.json`(38 例语料,development 26 / holdout 12,temperature=0 与镜像摘要断言)
  + `scripts/run-agent-evaluation.ps1` + 漏报/误报判分工具;r8 提示词模板注册表与唯一组装入口。
- 测试基线:backend 575 / sandbox-runner 75 / frontend 73,CI 三 job(verify / nginx-headers / supply-chain)。

**现状缺口(本项目的真实新建量):**

- 授权:项目=单人所有(`common/security/ProjectAuthorization.java:35-56` owner-only),
  无成员表、无项目角色;平台侧仅字符串 role(ADMIN 种子)。
- Finding:有 severity/confidence/evidence/feedback,**无生命周期状态机、无责任人、无修复 commit 关联**。
- Requirement 域不存在(`pullrequest/ReviewAction.getRequirementText()` 是审查意见文本,命名需避让)。
- model-service 耦合面仅 `review/ReviewProcessor.java` 一处调用(移除成本低,决策成立)。
- 前端:墨境壳 4 页(dashboard / projects / repository / ink-atelier),
  旧壳 5 页(pull-requests / knowledge / reviews / agent / ai-logs),见 `frontend/src/router.js:20-37`。
- Flyway 已至 V28,新表从 V29 递增。

## 3. 决策记录(用户已确认,2026-08-16)

| # | 决策 |
|---|------|
| D1 | 项目内角色收缩为 **3 角色**:负责人 LEADER / 开发 DEVELOPER / 审查 REVIEWER;验证职责并入审查人员;不做只读成员、不做独立验证人员;平台 ADMIN 沿用且不绕过项目边界 |
| D2 | 品牌两步走:UI/文档产品名切换须在 **P8 实验截图前**完成;**GitHub 仓库改名放最后**(P9),简历同步随之 |
| D3 | 前端改造纳入本计划:新页面直接按墨境风格建;旧壳 5 页在被触碰的 Phase 顺势迁移,P7 收尾兜底;沿用逐页可回退机制 |
| D4 | 实验语料线从 P2 起与开发并行,不压到 P8;每例标注成本 ≤1h,每周配额 2-4 例 |
| D5 | P6 研发助手为**可降级项**;助手不做实时沙箱工具调用(上下文预取制) |
| D6 | 论文最小可守线 = **P0–P5 + P8**;时间挤压时按 P6 → P7 简化 → P9 仅改 README 的顺序裁剪 |
| D7 | Java 包名 `com.example.codereview` 不改;改名只做产品层(UI 文案/README/文档) |
| D8 | 数据库维持 PostgreSQL(+pgvector),不换 MySQL:向量检索依赖 pgvector(`V1__baseline_schema.sql:1`),换库=重写 24 个迁移+全量回归且需额外引入向量库,纯负收益;本机开发仍走 H2 零安装 |

## 4. 需求

| ID | 需求 | 要点 |
|----|------|------|
| R1 | 成员与角色(3 角色) | 成员增删、角色设置、负责人移交;授权语义见 design §2;全部相关端点入授权矩阵测试 |
| R2 | Requirement + AC 管理 | 字段按源方案 §7;生命周期 §8 增补 CANCELED 与 READY→NEEDS_IMPROVEMENT 回退边;项目内 REQ 序号唯一递增;指派开发人员 |
| R3 | 需求质量检查(体检) | 六维(完整性/明确性/可测试性/异常覆盖/规则冲突/风险);确定性规则 + 知识检索 + LLM 结构化 + schema 校验;报告落库、需求详情内嵌展示 |
| R4 | 需求-代码关联 | 分支命名约定 + commit message 正则 + PR 引用自动提取,手动关联兜底;源方案 §10 四问均可查询 |
| R5 | 需求一致性 PR 审查 | 每条 AC 产出三态结论(已覆盖/未发现/存在风险)+ 证据引用;融合进现有审查报告与 Agent 链路;分片审查下 coverage 在合并阶段单独判定 |
| R6 | Finding 生命周期闭环 | OPEN→CONFIRMED→IN_PROGRESS→FIXED→VERIFIED→CLOSED + REJECTED;指派、修复 commit 关联;自动复审做**身份匹配**产出"建议已解决",终态由 REVIEWER 人工验证;匹配不上保持 OPEN 转人工。范围:PR 关联 Findings;临时审查结果保持报告制不入闭环 |
| R7 | 质量门禁扩展 | GateDecision 输入增加 AC 覆盖结论与 Finding 闭环状态;对外三态 PASS/WARN/BLOCK,与 SCM Conclusion 映射保持 |
| R8 | 研发助手(可降级) | 需求详情内嵌;上下文=需求+AC+知识库+预取代码;SSE 流式;明确不做自动开发/自动 commit/自动 push |
| R9 | 工作台 | 我的研发任务 / 待我处理 Finding / 待我审查 PR / 风险与最近审查;基于已迁墨境的 dashboard 改内容 |
| R10 | 研发度量 | 研发质量 / 需求质量 / 处理效率 / AI 指标四组;AI 组复用 Prometheus + ai_call_log 口径 |
| R11 | 实验与答辩材料 | 语料 38 例全量补 Requirement+AC+一致性真值(退路见 §6 风险);五臂消融;漏报/误报两率 + AC 级一致性命中率 + Token/耗时;38 例新基线在 P8 一并复跑 |
| R12 | 品牌切换 | 按 D2 两步走;README 徽章与链接随仓库改名同步修复 |

## 5. 验收标准

- A1(R1) 负责人可添加成员并设角色;DEVELOPER 调项目设置类接口得 403;非成员读项目得 404/403(口径同现状);新增端点均有授权矩阵负面用例,`mvn verify` 绿。
- A2(R2) 非法状态流转(如 DRAFT→DONE)被拒;READY 可回退 NEEDS_IMPROVEMENT;REQ 编号项目内唯一递增;AC 进入 IN_DEVELOPMENT 后修改需先回退状态。
- A3(R3) 用源方案 §31 示例(订单取消库存释放)体检,返回六维结构化报告且能指出"异常场景缺失"类问题;不合 schema 的模型输出被拒不落库。
- A4(R4) 按约定命名的分支/commit 推送后需求详情自动出现关联;PR 详情可反查需求;四问各有查询入口且结果正确。
- A5(R5) 演示 PR 的审查报告含每条 AC 的三态结论与证据引用;伪造引用被现有引用校验丢弃。
- A6(R6) Finding 全状态机可走通;fix commit 推送触发自动复审并产出"建议已解决";REVIEWER 验证后 CLOSED;匹配失败的 Finding 保持 OPEN 并标注需人工。
- A7(R7) 存在未闭环高危 Finding 或 NOT_FOUND AC 时门禁非 PASS;全部闭环后 PASS;SCM 回写状态一致。
- A8(R8,若保留) 助手在需求详情内 SSE 问答,引用需求/知识上下文;系统无自动 commit/push 通路。
- A9(R9) 工作台三列表与对应详情页数据一致(同一查询口径)。
- A10(R10) 度量页四组指标可见;AI 组数值与 ai_call_log/Prometheus 抽查一致。
- A11(R11) manifest 每例含 requirement/AC/真值字段;五臂在**同一案例集**上产出两率、AC 命中率、token、耗时;temperature=0 断言保持。
- A12(R12) P8 截图与演示 UI 显示 ForgePilot;P9 后 README 徽章与链接全部有效。
- A13(全局) backend `mvn -s .mvn/settings.xml verify`、frontend `npm test` + `npm run build`、sandbox-runner 测试全绿;`verify-local.ps1 -SkipSmoke` 通过;model-service 移除后 CI 三 job 绿。

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| 语料标注量不达标(38 例 × ≤1h ≈ 28-38h) | 每周 2-4 例配额从 P2 起摊销;底线:冻结在 ≥28 例(dev 20 / holdout 8),五臂仍在同集比较,论文口径如实声明 |
| RBAC 改造回归面大(17 控制器) | 分两步:先加成员表放宽 read,再逐控制器收紧 write;每步全量授权矩阵负面用例;冻结契约按扩展不破坏方式演进(design §2) |
| Finding 跨轮匹配不准 | 保守匹配(指纹→LLM 辅助→人工);自动侧只产出建议,终态永远人工验证;匹配质量不进论文主指标 |
| 答辩现场无公网,webhook 演示失败 | 本地签名注入脚本 + 临时审查兜底 + mock AI/H2 全离线预案,P8 演示脚本固化 |
| 求职/实习并行,投入波动 | 假设 10-15h/周;按 D6 裁剪阶梯执行;每 Phase 末为可停靠点(全量验证绿 + 合批提交) |
| 前端迁移引入回归 | 沿用现有逐页迁移、路由语义零变更、可逐页回退机制 |

## 7. 明确不做

源方案 §26 全表继续有效,本轮追加:六角色体系、只读成员、独立验证人员、
助手实时沙箱工具调用、Java 包名重命名、任何新中间件(Redis/Kafka/ES)、新微服务拆分、
临时审查结果进入 Finding 闭环。

## 8. 里程碑与时间假设

- 投入假设:10-15h/周(求职/实习并行);答辩假设 2027 年 4-5 月(时点不同只调节奏与裁剪,不改结构)。
- 工作量估算(周当量):P0≈1 · P1≈3.5-4.5 · P2≈1.5-2 · P3≈1.5-2 · P4≈2-3 · P5≈2.5-3 ·
  P6≈1.5-2(可降级)· P7≈2 · P8≈3-4 · P9≈0.5 · L 线≈1.5-2(摊销),
  合计 ≈20.5-26 周当量 + 2 周缓冲 ≈ **5-6.5 个月**;若走裁剪阶梯(砍 P6、简化 P7、P9 后置)
  可压缩约 2.5-3.5 周当量。
- 关键路径:P1a(RBAC)→ P1b(需求域)→ P2 → P4 → P5 → P8;L 线与 P4 汇合于 P8。
- 最小可守线:P0–P5 + P8(D6)。

阶段定义、依赖与退出标准见 `implement.md`;技术设计见 `design.md`。
