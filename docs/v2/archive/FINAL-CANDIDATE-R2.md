# ForgePilot V2 FINAL-CANDIDATE R2 审查报告

日期：2026-08-19  
审查对象：`docs/v2/` 全部规划文档（基线 main @ `fe271bbf`）+ 用户裁决的 [ADR-001..005](./adr/README.md)  
方法：对照 Legacy 实际代码逐项核查方案声称 → 用户裁决 5 个设计点 → 按 ADR 完成 R2 修订 → 复检 Schema、依赖与业务一致性。  
纪律：本轮未修改任何业务代码，未开始 Phase 1，未提交实现。

## 1. 实库核查结论（方案可信度）

方案的 Legacy 事实声称与真实代码**逐项一致**：

- 规模：backend main Java 411 / test 155；`agent 115、finding 37、scm 28、language 23、ai 20、requirement 20、review 17、patch 13、assistant 10、auth 10、rag 9、knowledge 9、mq 9`，与 LEGACY-AUDIT 完全吻合。
- KEEP 白名单 7 项资产全部存在：`KnowledgeUploadValidator`、`PromptSanitizer`、`OutboundUrlPolicy`、`FindingLifecycle`、GitHub/GitLab `WebhookVerifier`、`evaluation/tools/score.py`、demo-repos（3 仓库）+ 38 例评测语料。
- 降级理由抽查属实：`DiffSplitter.java:64-66` 超过 maxFiles 后 `subList` 丢文件；`FindingDeduplicator.java:41` 路径 `toLowerCase` 会错误合并大小写敏感文件。
- Legacy `FindingLifecycle.java:26-31` 状态边与目标状态机逐边一致，KEEP 成立。

## 2. ADR 裁决 → R2 修订映射

| ADR | 修订落点 |
|---|---|
| ADR-001 无维度 vector + 延迟索引 | FINAL-CANDIDATE C5/D1/I2；PRD R3/R9/Risks；IMPLEMENTATION-PLAN Phase 1/4；矩阵 KnowledgeChunk 行 |
| ADR-002 分批产证据 + Final Synthesis | FINAL-CANDIDATE C3/H6/I2；PRD R6；IMPLEMENTATION-PLAN Phase 6；矩阵 DiffSplitter 行 |
| ADR-003 业务键 `(pull_request_id, head_sha)` | FINAL-CANDIDATE B2/D1/decision 段；PRD R6；重试复用同行语义补记 |
| ADR-004 LEADER 部分唯一索引 + Requirement 1:N PR | FINAL-CANDIDATE D1（project_member/pull_request 行）；PRD R2/R9/Risks；矩阵 ProjectMemberService/PullRequest 行 |
| ADR-005 附件 requirement-scoped 检索边界 | FINAL-CANDIDATE C5/D1（knowledge_document 行）；PRD R3；矩阵 KnowledgeDocument 行 |

另重画：Finding 状态图（A4，边列表化）、新增 Review 执行状态图（A4，mermaid）、新增 D3 数据关系图（mermaid ER，覆盖全部 14 表）。

## 3. 14 表 Schema 复检（结论：仍然足够，无新表）

逐条 ADR 对表结构的影响全部是**列/索引级**，不需要第 15 张表：

- ADR-001：`knowledge_chunk` 的 embedding 列改无维度 `vector`；补 provider/model/version/dimension 审计列。索引推迟到 Phase 4 独立 migration。
- ADR-002：分批是运行时行为；truncation manifest 与摘要仍存 `review` 行内 JSON；Batch 中间产物不落库（失败即整体 FAILED 重跑）。`ai_call_log` 天然支持一次 Review 多条调用记录（已含 review 外键）。
- ADR-003：`review` 唯一键改 `(pull_request_id, head_sha)`；engine_version/prompt_version/model 变为普通审计列。原“跨 engine_version Decision 部分唯一索引”不再需要。
- ADR-004：`project_member` 加部分唯一索引；`pull_request.requirement_id` 普通索引。无表变化。
- ADR-005：`knowledge_document` 补 `source_requirement_id` 列（source_type 原已有）。无表变化。

## 4. 循环依赖复检（结论：cycle=0 保持）

- ADR-005 / PR 关联（ADR-004）均采用**不透明 id 模式**：`knowledge` 与 `scm` 只把 `source_requirement_id` / `requirement_id` 当作调用方传入的 scope id 存储与过滤，不 import requirement 类型；DB 层 FK 保证引用完整性。E3 允许依赖列表不变，`requirement → knowledge`、`review → *` 方向不变，`scm ↛ review`、`knowledge ↛ requirement` 依旧成立。
- ADR-002 的 Batcher/Synthesis 全部位于 `review` 包内，AI 只经 `AiGateway`，不产生新包也不产生新方向。
- ADR-001/003 是纯 schema/约束变化，不涉及包依赖。

## 5. 业务矛盾复检（结论：无遗留矛盾；两处旧表述已顺带修复）

- “同一 head 只允许一次终局 Decision”与多 engine_version Review 的冲突：**已消除**（ADR-003 后 Decision 唯一性由 Review 唯一性天然保证）。
- “修复后按新 head SHA 产生新 Review、保留前后结果”与 retry 语义：**不冲突**——新 head 新行，同 head 重试复用同行（已写入 B2/ADR-003）。
- AC verdict 跨批二义性：**已消除**（ADR-002 Batch 不产 verdict）；无证据 AC 由 Validator 补 `NOT_FOUND` 的规则保持。
- 附件召回污染其他需求 Review：**已消除**（ADR-005 SQL 硬过滤）；跨项目隔离仍先行生效。
- 顺带修复的残余矛盾：IMPLEMENTATION-PLAN Phase 6“一次返回”/Phase 4“单配置（无索引说明）”与 FINAL-CANDIDATE H6“一次输出”三处旧表述与 ADR-001/002 冲突，已按 ADR 改写（超出任务 2 点名的三个文件，特此披露）。

## 6. 一并修复的文档不一致（2026-08-19 审核发现）

1. 矩阵陈旧单元格：`ScmConnection`（已并回 `scm_repository`）、WebhookController 直调 `review.ReviewService`（改为发布事件）、`ReviewTriggerService`（改为事件监听 → `requestReview`）。
2. 矩阵四层命名残留（`*.application/*.domain/*.infrastructure/*.web/*.spi`）全部改为 package-by-feature 目标名；`DevelopmentContextBuilder→ReviewContextBuilder`、`RequirementReviewService→RequirementQualityService`、`review.engine.ReviewEngine→review.ReviewEngine` 统一。
3. PRD 断链 `research/legacy-audit.md`/`repository-evidence.md` → 指向 `LEGACY-AUDIT.md`/`LEGACY-MIGRATION-MATRIX.md`；IMPLEMENTATION-PLAN/矩阵的 `final-candidate.md` 大小写修正。
4. Review 执行状态跨文档统一为 `PENDING → RUNNING → COMPLETED | FAILED`。
5. A4 Finding ASCII 乱图重画；README 阅读顺序纳入 ADR 与本报告。

## 7. 留待实施阶段的注记（不阻塞评审）

- ADR-002 的 Final Merge/Synthesis 采用确定性证据聚合还是一次 synthesis LLM 调用，Phase 6 设计时以补充 ADR 记录。
- ADR-001 的距离算子（cosine/L2）与 HNSW 参数，随 Phase 4 Embedding Profile 的 migration ADR 一并确定。
- PR↔Requirement 关联的写入方（Controller 经 requirement facade 校验同项目后调 scm 写入，或由 review 编排）在 Phase 5 设计时确定；沿用不透明 id 模式即可，不改变依赖方向。
- 每 Phase 建议补粗略工期估算（毕业设计进度风险）；Phase 5 需要公网可达的 Webhook 地址（cloudflared tunnel / smee.io）。

## 8. 结论

R2 修订后方案**内部自洽、与 Legacy 事实一致、14 表与 8 包边界成立，无阻塞问题**。
等待用户对 R2 做人工评审；批准后首个实施授权仍只覆盖 Phase 0-1（先冻结契约与绿地骨架，Phase 1 产物过评审再放行 Phase 2）。
