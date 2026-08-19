# ForgePilot 平台升级 — 总体技术设计

> 父任务级设计:定架构原则、领域模型、关键算法与各模块契约走向。
> 子任务实现时在此基线上细化,偏离须回写本文件。

## 1. 架构原则

- 不新增中间件(无 Redis/Kafka/ES),不拆新微服务;PostgreSQL 唯一事实源,异步走 RabbitMQ + 事务 Outbox(现状沿用)。
- 模型输出一律"先校验后采信":schema → 权限 → 路径 → 预算 → 引用校验,复用现有两级防御
  (`.trellis/spec/backend/agent-model-contracts.md`)。
- 冻结契约(ErrorCode / PageResponse / ProjectAuthorization / Flyway 不可变迁移 / REST 与 MQ 载荷)
  只做**扩展式演进**,变更回写 `.trellis/spec/backend/frozen-contracts.md`。
- 新域按现有领域分包落位:新增 `member`、`requirement` 包;`finding`、`context`、`agent` 包内扩展。
  命名避让:Requirement 域类不与 `pullrequest.ReviewAction#getRequirementText`(审查意见文本)混用词根。

## 2. 成员与授权(R1,P1a)

**数据模型** `project_member(id, project_id, user_id, role, created_at)`,
`unique(project_id, user_id)`;`role ∈ {LEADER, DEVELOPER, REVIEWER}`。
项目创建者自动写入 LEADER 行;`ProjectEntity.ownerId` 保留(负责人移交 = 单事务内改 ownerId + 成员表同步)。

**授权语义演进**(`ProjectAuthorization` 是冻结契约,采用"保签名、扩语义、加新方法"):

- `requireRead(projectId, userId)`:owner-only → **任意成员**。
- `requireWrite(projectId, userId)`:保持"最高权限动作"语义 = LEADER(过渡期等价现状,因 owner 即 LEADER)。
- 新增 `requireRole(projectId, userId, Set<Role>)`:细粒度动作检查。
- 无 admin bypass(维持类注释既有决定)。404/403 口径不变:项目不存在 404,非成员 403。

**动作-角色矩阵**(子任务可微调,新增端点必须同步授权矩阵负面用例):

| 动作域 | LEADER | DEVELOPER | REVIEWER |
|---|---|---|---|
| 项目设置/成员管理/仓库与规则配置 | ✓ | – | – |
| 需求创建/编辑/指派/READY 推进 | ✓ | – | – |
| 需求开发态流转(IN_DEVELOPMENT→IN_REVIEW) | ✓ | ✓(被指派者) | – |
| 体检触发 / 临时审查触发 | ✓ | ✓ | – |
| Finding 确认/驳回/验证关闭 | ✓ | – | ✓ |
| Finding 修复态流转(IN_PROGRESS→FIXED) | ✓ | ✓(被指派者) | – |
| 知识库上传 | ✓ | ✓ | – |
| 知识库删除 | ✓ | – | – |
| 只读查询(全部页面) | ✓ | ✓ | ✓ |

**迁移策略**:两步走——V29 建表 + `requireRead` 放宽为成员(行为对单人项目零变化);
再按控制器组引入 `requireRole` 收紧写路径,每组带负面用例后合入。17 个 Controller 逐组过,不一次性大爆炸。

## 3. Requirement 域(R2,P1b)

- `requirement(id, project_id, seq, title, background, description, priority, assignee_id,
  status, created_by, created_at, updated_at)`;`unique(project_id, seq)`,seq 项目内递增
  (取号在事务内 `select max+1` 并靠唯一约束兜底重试,演示规模无争用问题)。
- `acceptance_criterion(id, requirement_id, seq, text)`。约束:需求进入 IN_DEVELOPMENT 后
  修改 AC 必须先回退状态(保证审查与实验口径稳定)。
- 状态机:`DRAFT → NEEDS_IMPROVEMENT ⇄ READY → IN_DEVELOPMENT → IN_REVIEW → DONE`,
  任意态可 → CANCELED;非法流转抛 BusinessException(新增 ErrorCode 入词汇表)。
  守卫:→IN_DEVELOPMENT 需 assignee 非空;→DONE 仅当关联 PR 门禁 PASS(P5 后启用,此前留人工推进)。

## 4. 需求-代码关联(R4,P3)

`requirement_link(id, requirement_id, type ∈ {BRANCH, COMMIT, PULL_REQUEST}, ref, source ∈ {AUTO, MANUAL},
created_at)`,`unique(requirement_id, type, ref)`,幂等 upsert。

提取器(全部有界正则 `\bREQ-(\d+)\b`):
- 分支:约定 `REQ-<seq>-*`,在仓库同步(现有 clone/fetch 路径)时扫描分支列表。
- Commit:同步时扫描新 commit message(限定默认分支与活跃分支,数量有界)。
- PR:webhook 归一化事件处解析 title/body;交互式 PR 列表同样解析。
- 手动关联:REST 端点 + 需求详情 UI 兜底。

四问查询即 `requirement_link` 正反向 join,不建冗余表。

## 5. Context Builder(R3/R5/R8 公共层,P2 起)

在 `context` 包基础上扩展统一入口:

```
buildContext(scene, projectId, refs) → ContextBundle
scene ∈ {REQUIREMENT_CHECK, PR_REVIEW, ASSISTANT}
ContextBundle = {requirement?, acs[], knowledgeSnippets[], codeSlices[], diff?, historyFindings[], budgetReport}
```

- 组装走 r8 唯一组装入口与模板注册表,新场景=新模板注册,遵守 prompt-management 五规则
  (宁精勿多、评测门禁、退役、禁承诺红线)。
- 预算:沿用现有 token 记账;`ContextBundle` 记录各段截断情况,供实验分析。
- **与分片审查的融合**(关键决策):大 Diff 分片时,per-shard 调用照旧产出 findings;
  **AC 覆盖判定在合并阶段单独一次调用**(输入 = Requirement + AC + 分片结论摘要 + 关键 diff 片段),
  避免每片重复注入需求上下文的 token 放大,也保证覆盖判定的全局视角。

## 6. 需求质量检查(R3,P2)

流水线:字段与确定性规则检查(纯代码,零 token)→ 知识检索(现有 RAG 双模)→
LLM 结构化分析(scene=REQUIREMENT_CHECK)→ schema 校验 → `requirement_quality_report` 落库。

- 报告表:`requirement_quality_report(id, requirement_id, round, report_json, model, tokens, created_at)`,
  round 递增,历史保留(论文可展示改进过程)。
- 输出 schema:六维各含 `items[{severity, message, suggestion}]`;校验不过丢弃不落库(现有防御姿态)。
- 触发:需求详情手动按钮(LEADER/DEVELOPER);不做保存自动触发(控制 token 成本)。

## 7. 需求一致性审查(R5,P4)

- 输出 schema:`coverage[{acId, verdict ∈ {COVERED, NOT_FOUND, AT_RISK}, evidence[], rationale}]`,
  证据结构复用现有 Finding 证据格式,过引用校验(伪造引用丢弃,该 AC 降级 AT_RISK 并标注)。
- 注入:PR → requirement_link 解析需求;无关联需求的 PR 走纯质量审查(现状行为,向后兼容)。
- 报告融合:审查报告新增 coverage 区块,与 findings 并列;AgentRun 时间线增加 coverage 步骤。
- **实验预埋**:`ContextBundle` 按 feature flags 组装
  (`+knowledge`、`+requirement/ac`、`+evidenceVerification`),五臂 = flag 组合,
  生产链路与实验共用同一代码路径(论文可信度关键)。

## 8. Finding 生命周期与身份匹配(R6,P5)

**实体扩展**(Flyway 新增列,存量行回填默认值):
`status`(默认 OPEN)、`assignee_id`、`fix_commit_sha`、`verified_by`、`verified_at`、
`requirement_id`(经 PR 反查冗余存储,加速工作台查询)、`identity_fingerprint`。

**状态机**:`OPEN → CONFIRMED → IN_PROGRESS → FIXED → VERIFIED → CLOSED`,
`OPEN/CONFIRMED → REJECTED(误报)`。确认/驳回/验证 = REVIEWER 或 LEADER;修复态流转 = 被指派者。

**身份匹配算法**(自动复审的核心,保守设计):
- 建档:`fingerprint = sha256(normalizedPath | issueType | normalizedTitleTokens)`,创建时计算。
- 复审匹配序:(a) 指纹精确命中 → 候选"已解决/仍存在";
  (b) 同文件同类型但指纹漂移 → LLM 辅助比对(输入新旧 finding + 证据,有界一次调用);
  (c) 无命中 → 保持原状态并标注 `needsManualCheck`。
- **自动侧只产出 RESOLVED_SUGGESTED / STILL_PRESENT / UNKNOWN 三档建议,永不自动 CLOSE**;
  VERIFIED 必须 REVIEWER 操作。算法风险全部兜在人工环内,匹配质量不进论文主指标。
- 范围:仅 PR 关联的 findings 进入生命周期;临时审查(commit 手动审查)保持报告制。

## 9. 质量门禁扩展(R7,P5)

`GateDecisionService` 输入扩展:现有信号 + `acNotFoundCount`、`acAtRiskCount`、
`openHighRiskFindings`、`unverifiedFixedCount`。
输出对外三态 `PASS / WARN / BLOCK`,规则表配置在项目质量规则(阈值可调,默认保守);
与 `scm/ReviewPublication.Conclusion` 的映射保持现状语义(回写内容不含补丁,沿用)。

## 10. 研发助手(R8,P6,可降级)

- 无实时沙箱工具调用(往返延迟秒级-十秒级,与交互体验矛盾;决策 D5)。
- 上下文预取:requirement + AC + 知识检索 topK + requirement_link 关联的 diff/文件摘要(有界)。
- SSE 流式输出,前端遵守 `.trellis/spec/frontend/state-management.md` 的 401/SSE 硬规则。
- 服务端无会话表:对话历史由前端会话内存持有随请求回传(有界条数),调用记录落 `ai_call_log`。
- 红线:助手无写操作通路(无 commit/push/apply 工具),prompt 层与工具层双重不存在。

## 11. model-service 移除(P0)

- 删除调用点:`review/ReviewProcessor.java` 对 `ModelRiskClient` 的引用与降级逻辑。
- 删除 `model` 包 4 类(ModelRiskClient / Http / Noop / ModelRiskSignal)及配置项。
- 同步清理:`deploy/docker-compose` 服务、CI 中 model-service 测试 job、
  `docs/08_部署环境与配置清单.md` 相关条目、README 架构图与模块表。
- `model-service/` 目录与 Git 历史保留(论文历史对照,源方案 §20);
  `.trellis/spec/model-service/` 标注 archived。
- 回滚点:独立合批提交,revert 即恢复。

## 12. 前端信息架构与迁移(D3,贯穿)

目标八区(源方案 §4)与现有路由(`frontend/src/router.js:20-37`)映射:

| 目标一级区 | 现有路由 | 处置 | Phase |
|---|---|---|---|
| 工作台 | `/dashboard`(墨境✓) | 内容改造(我的任务/待办 Finding/待审 PR) | P7 |
| 项目 | `/projects`(墨境✓) | 项目设置内加成员管理 | P1a |
| 研发任务 | — | **新建** `/requirements`(列表+详情;详情内嵌体检/关联/助手) | P1b 起 |
| 代码仓库 | `/repository`(墨境✓)+ `/pull-requests`(旧壳) | PR 列表迁墨境并入仓库区 | P4 |
| 智能审查 | `/reviews` + `/agent`(旧壳) | 迁墨境合并为审查区(报告 + AgentRun 时间线 + coverage) | P4 |
| 质量中心 | — | **新建** `/quality`(Finding 列表/详情/流转/指派) | P5 |
| 知识库 | `/knowledge`(旧壳) | 迁墨境;若存在独立聊天入口则移除(§18) | P7 |
| 研发度量 | `/ai-logs`(旧壳) | **新建** `/metrics` 四组指标,ai-logs 并入 AI 区 | P7 |

- 新页面直接墨境风格;迁移沿用现有机制:路由语义零变更、逐页可回退。
- `/ink`(InkAtelierPage)与 `/dashboard` 的归一在 P7 明确(二选一保留,另一个跳转)。
- 组件与状态遵守 frontend spec(SFC 写法、模块级单例 composable、node --test)。

## 13. 实验设计落地(R11,L 线 + P8)

- `evaluation/manifest.json` schema 扩展:每 case 增
  `requirement{title, background, description}`、`acceptanceCriteria[{id, text}]`、
  `consistencyTruth[{acId, verdict}]`、缺陷标注沿用现有格式;`schemaVersion` 递增。
- 判分工具新增 AC 级一致性命中率(predicted verdict vs truth 的 per-AC P/R);
  两率工具沿用;`falsePositiveRate` 与 README 口径差异继续显式区分。
- 五臂 = §7 的 feature flags 组合:Baseline(diff)/ A(+knowledge)/ B(+req&ac)/
  C(A+B)/ D(C + 现有证据验证链开启)。同一案例集、temperature=0(脚本已断言)、
  同一模型与镜像摘要,token 与耗时自动记账。
- 38 例新基线(README 声明尚未复跑)在 P8 与五臂一并产出,历史 32 例基线仅作趋势参考。

## 14. 数据迁移与兼容

- Flyway V29 起递增,不可变迁移纪律不变;存量数据为演示库,允许默认值回填
  (finding.status=OPEN、成员表回填 owner→LEADER)。
- REST 冻结契约:既有端点响应只加字段不改语义;分页信封与 ErrorCode 规范沿用,
  新 ErrorCode 进词汇表。MQ 载荷若增字段走 `.trellis/spec/guides/contract-testing.md` 双向金标流程。

## 15. 演示与部署预案(P8)

- 本地 webhook 注入脚本:用部署配置的测试 secret 对构造 payload 正常 HMAC 签名后 POST
  (不开验签后门);离线兜底:mock AI + H2 + inline 审查 + 临时审查路径。
- 答辩演示脚本 = 源方案 §31 场景固化为 step list,双路径(在线 webhook / 离线注入)。
- docker compose 减 model-service,其余不变。
