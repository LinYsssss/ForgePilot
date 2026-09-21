# 论文可信性修复与文档同步

## Goal

以最小实现补上两处已经由真实代码与运行证据确认的可信性缺口，使论文可以准确主张“模型输出经过确定性证据验证”，同时不扩展产品边界、不改写既有正式实验资产。

## Background

- 当前 Review Prompt 要求 `evidence` / AC `excerpt` 逐字来自 patch，但 `ReviewOutputValidator` 只校验非空、路径白名单和模型行号是否位于新侧，没有验证引用文本真实存在，也不会按真实引用纠正行号。
- 现有真实演练的 57 条 Finding 中引用文本都可在源码中找到，但 10 条模型行号存在偏移；这是适合论文展示的已观察问题。
- 当前需求质量检查在 Prompt 超预算时先生成 `PROMPT_BUDGET_EXCEEDED`，随后仍调用 `AiGateway`，最终会由网关抛出 `ai_prompt_too_large`，与“返回确定性规则结果”的预期矛盾。
- 本科系统设计与实现论文的数据已经足够；本任务不追求新增大功能或美化、覆盖旧实验结果，而是加强结果的可验证性与文档一致性。

## Requirements

### R1 — Review 证据必须锚定到 diff 新侧

- Finding `evidence` 与批次 AC `excerpt` 必须逐字存在于各自 changed file 的合法 unified-diff hunk 新侧源码中。
- 新侧源码只包括上下文行与新增行；删除行、diff 元数据和截断标记不得作为证据。
- 引用不得跨越两个不连续 hunk；只允许归一化 CRLF/LF，不得裁剪或折叠空格、缩进。
- 无命中时丢弃对应 Finding 或 AC evidence，并保留明确 validator warning。
- 唯一命中时使用真实起始行；模型行号为空或错误时自动纠正并记录 warning。
- 多处命中时，模型行号能唯一消歧则保留该真实行；否则保留已验证引用但将行号置空并记录 warning。
- `finding_key` 使用锚定后的行号；`evidence_hash` 继续基于原始逐字引用及现有换行归一化。
- Finding 与 AC evidence 复用同一私有锚定逻辑；不新增数据库列、公开 API、前端字段或依赖。

### R2 — 超预算质量检查返回确定性结果

- 需求质量 Prompt 未超预算时保持现有一次结构化 AI 调用。
- 超预算时生成 `PROMPT_BUDGET_EXCEEDED`，明确说明 AI 分析已跳过，不调用 `AiGateway`，不产生 AI 调用记录。
- 超预算报告正常保存并返回，`ai=null`；质量检查不得改变需求工作流状态。
- `QUALITY_VERSION` 升级为 `quality-2`，历史质量结果不重写。
- 保持现有 JSON 结构；前端已经支持 `ai=null`，不增加前端产品改动。

### R3 — 同步全部直接受影响的文档

- 更新 `docs/v2/ARCHITECTURE.md`、`API.md`、`PRD.md`、`TESTING-GUIDE.md`、`frontend/MANUAL-ACCEPTANCE.md` 与 `docs/deliverables/README.md`。
- 更新 `MANUAL.html`、`SECURITY.html`、`WALKTHROUGH.html`、`SRS.html`、`TEST-REPORT.html`。
- 历史日期、旧 CI 数字、生产复验数据和正式实验结论必须原样保留；新增内容以 2026-09-20 补充说明或本次实际门禁结果呈现。
- `OPERATIONS.html`、正式 holdout、冻结清单、原始输出、生产复验 CSV/Markdown 和历史 Review 数据不修改。

### R4 — 验证范围保持克制但覆盖高风险行为

- 只补少量定向后端测试，不新增前端测试。
- 必须验证虚构引用拒绝、唯一命中纠行、多处命中不伪造精确行号、AC 引用同规则，以及超预算时 `ai=null`、不调用 provider、规则结果持久化和版本升级。
- 最终运行现有后端完整门禁和前端现有门禁；测试报告中的数量只能填写本次真实输出。

## Acceptance Criteria

- [x] 虚构 Finding evidence 与虚构 AC excerpt 不会进入成功输出，并留下可诊断 warning。
- [x] 唯一真实引用能将错误或空行号纠正到 diff 新侧真实起始行。
- [x] 重复引用在无法由模型行号消歧时不会产生伪精确行号。
- [x] 删除行、元数据或跨 hunk 拼接不能被接受为新侧证据。
- [x] 超预算质量检查保存并返回 `quality-2` 的确定性报告，`ai=null`，且 provider 零调用。
- [x] 未超预算路径继续执行一次结构化 AI 调用，公开响应结构与前端兼容。
- [x] 所有直接受影响的 Markdown 与 HTML 描述和实现一致，五份 HTML 可被标准解析器解析。
- [x] 不修改数据库、路由、前端数据结构、正式评测资产或历史生产证据。
- [x] 定向测试、后端 `verify`、前端 lint/typecheck/test/build、文档搜索、HTML 解析和 `git diff --check` 全部通过。

## Out of Scope

- 不处理需求质量检查期间并发编辑导致旧结果覆盖新文本的已知竞态。
- 不调整 Finding 跨轮 key/hash 算法，不修改 Prompt/schema 或正式评测工具链。
- 不新增论文实验、不重跑 holdout、不部署生产、不重跑真实 PR 审查。
- 不增加 Agent、自动修复、消息队列、微服务、通用检索评测或新前端功能。

## Constraints

- `ReviewPrompts.VERSION` 与 `FindingKeys.RULE_VERSION` 保持不变：本次不改变 Prompt/schema，也不改变确定性 hash 组成。
- 实现优先内聚在现有 Validator 与 Requirement Quality Service 内，避免为一次性逻辑引入公共抽象。
- 任何测试或文档修订不得将历史快照数字伪装成本轮结果。
