# ForgePilot V2 方案实库审核记录（2026-08-19）

审核人：claude（Trellis 任务 `08-19-v2-plan-review`）  
基线：main @ `fe271bbf9b3a95d52685858ba9a49d6533b432da`（docs/v2 首次提交）  
产物：本记录 + `docs/v2/adr/ADR-001..005` + `docs/v2/FINAL-CANDIDATE-R2.md` + docs/v2 R2 修订

## 1. 实库核查（全部属实）

| 方案声称 | 核查方法 | 结果 |
|---|---|---|
| backend main Java 411 / test 155 | find 计数 | 完全一致 |
| agent 115、finding 37、scm 28、language 23、ai 20、requirement 20、review 17、patch 13、assistant 10、auth 10、rag 9、knowledge 9、mq 9 | 按包 find 计数（根包实为 `com.example.codereview`） | 逐项一致 |
| KEEP 白名单 7 项资产存在 | 逐文件定位 | KnowledgeUploadValidator/PromptSanitizer/OutboundUrlPolicy/FindingLifecycle/GitHub+GitLab WebhookVerifier/score.py/demo-repos 全部在 |
| 评测 38 例语料 | manifest.json 解析 + cases 计数 | 38 例，含 corpusVersion/schemaVersion/fixedRun |
| DiffSplitter 超 maxFiles 丢文件 | `review/DiffSplitter.java:64-66` | `subList(0, maxFiles)` 属实（skipped 有计数但内容丢弃） |
| FindingDeduplicator 路径转小写 | `finding/FindingDeduplicator.java:41` | `toLowerCase(Locale.ROOT)` 属实 |
| FindingLifecycle 状态边 | `finding/FindingLifecycle.java:26-31` | OPEN→CONFIRMED\|REJECTED；CONFIRMED→IN_PROGRESS\|REJECTED；IN_PROGRESS→FIXED；FIXED→VERIFIED\|IN_PROGRESS；VERIFIED→CLOSED；终态 CLOSED/REJECTED，与目标一致 |
| ScmProviderContractTest 存在 | test 目录定位 | 在 `scm/ScmProviderContractTest.java` |

结论：方案审计层可信；技术选型全为标准件；砍到 14 表 / 8 包 / 3 页面后对毕业设计是现实可完成体量。

## 2. 审核发现的问题

### 2.1 文档不一致（已全部修复）

1. 迁移矩阵陈旧单元格与 FINAL-CANDIDATE 矛盾：`ScmConnection`、WebhookController 直调 `review.ReviewService`、`ReviewTriggerService`。
2. 矩阵四层命名残留与目标命名打架（`*.application/domain/infrastructure/web/spi`、DevelopmentContextBuilder、RequirementReviewService、review.engine）。
3. PRD 断链 `research/legacy-audit.md`、`repository-evidence.md`（仓库中不存在）。
4. `final-candidate.md` 小写引用（实际文件大写）。
5. Review 状态枚举跨文档不一致（PENDING/COMPLETED/FAILED vs 含 RUNNING）。
6. FINAL-CANDIDATE A4 Finding ASCII 图排版混乱。

### 2.2 设计缺口（已由用户裁决为 ADR）

| 缺口 | 裁决 |
|---|---|
| vector(N) 建表需固定维度 vs 维度是部署配置 | ADR-001：无维度 vector + Phase 4 独立 migration 建 HNSW expression index；禁止 placeholder 改 V1 结构 |
| 大 PR 分片后 AC verdict 跨批合并二义 | ADR-002：Batch 只产 candidate/evidence，Final Synthesis 统一产出；唯一引擎不变 |
| Review 唯一键含 engine_version 与"同 head 一次终局 Decision"冲突 | ADR-003：业务键收敛 `(pull_request_id, head_sha)`，版本仅审计元数据 |
| LEADER 恰一 / Requirement-PR 基数未落 DDL | ADR-004：LEADER 部分唯一索引 + Service 保底；Requirement 1:N PR，requirement_id 普通索引 |
| 附件是否被其他需求检索召回未定义 | ADR-005：requirement-scoped，SQL 硬过滤，共享须显式提升 |

## 3. R2 复检结论

- 14 表仍然足够：全部 ADR 影响均为列/索引级（详见 FINAL-CANDIDATE-R2 §3）。
- cycle=0 保持：ADR-004/005 采用不透明 id 模式，允许依赖列表不变（§4）。
- 无遗留业务矛盾；IMPLEMENTATION-PLAN 与 FINAL-CANDIDATE H6 的三处旧表述顺带按 ADR 改写并在 R2 报告披露（§5）。
- 残留扫描：规范性文档中旧表述清零，剩余命中均为 ADR 背景段 / R2 报告的合法历史引用。

## 4. 留给后续 Phase 的注记

- Synthesis 机制（确定性聚合 vs 一次 LLM 调用）→ Phase 6 补充 ADR。
- 距离算子与 HNSW 参数 → Phase 4 Embedding Profile migration ADR。
- PR↔Requirement 关联写入方（不透明 id 模式）→ Phase 5 设计。
- 每 Phase 补工期估算；Phase 5 需公网 Webhook 地址（cloudflared/smee）；若部署 4GB 小内存机需给 JVM/Postgres 设内存上限。

## 5. 状态

docs/v2 R2 修订全部完成，**未提交 commit**（等用户决定提交/推送）；业务代码零改动；Phase 1 未开始。
下一步：用户评审 R2 → 批准后授权 Phase 0-1。
