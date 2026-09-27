# 审查质量改进：类型推导、温度、校验可见性与评测效度

## Goal

按 2026-09-24 全盘审查与改进清单做一轮**最小改动**：减少引擎自身造成的 Finding 丢失与跨轮漂移，让校验器的删改对审查人可见，修掉两处权限/交互缺口，同步全部过期文档；再用两组实验如实量化审查能力与评测效度，写成论文材料。

## Requirements

### R1 — Finding 类型由 acId 推导（`review-4`）

- 两个输出 schema 去掉 `type`；校验器按 acId 推导：acId 为空 → `CODE_QUALITY`；acId 属于本次修订 → `REQUIREMENT`；acId 不属于本次修订 → 仍整条丢弃（引用虚构，原行为）。
- 指令改为"涉及某条验收条件就给出它的 acId，否则为 null"。输出 schema 变了，`ReviewPrompts.VERSION` → `review-4`；`FindingKeys` 与 `RULE_VERSION` 不动。
- 依据：Halo 两轮各有 9/7 条"CODE_QUALITY 却带 acId"被整条删除；正式评测里第三臂未匹配的预测多数只是类型标签不同。

### R2 — AI 网关

- `forgepilot.ai.temperature`，默认 `0`，留空则不发送该字段；compose 透传 `FORGEPILOT_AI_TEMPERATURE` 与已有但未透传的 `FORGEPILOT_AI_TIMEOUT`。
- 429 后先等待 `forgepilot.ai.rate-limit-backoff`（默认 5s）再做那一次重试；重试次数不变。

### R3 — 审查详情的可见性

- 详情返回校验摘要：被丢弃的 Finding 数、被纠正的行号数（由 `summary_json.warnings` 按校验器自己的措辞归类）。
- 详情返回"上一轮报告、本轮未再报告"的 Finding（接入现有 `FindingContinuityCalculator.notReported`），页面明确"未再报告 ≠ 已修复"。

### R4 — 权限与交互

- `IN_PROGRESS → FIXED` 仅限该 Finding 的认领人。
- 需求详情页的终态流转（已完成 / 已取消）先确认。
- 新建项目时项目名去首尾空白；线上已有的一条带前导空格的项目名顺手修正。

### R5 — 清理

- 删除无调用方的 `ProjectDeletionRecordRepository.findByProjectIdAndResourceTypeOrderByIdAsc`、`PullRequestRequirementEventRepository.findByProjectIdAndPullRequestIdOrderByIdAsc`、前端 `projectSettingsRoute`；ArchUnit 与 ARCHITECTURE 去掉不存在的 `ai.openai` 子包。
- 修正 `ReviewPipeline` 错位的 Javadoc 与 `ReviewPrompts` 的孤立 Javadoc；本轮改到的文件里的英文注释改中文。

### R6 — 文档同步

- 09-24 审查列出的全部过期表述：README / DEFENSE-GUIDE 部署版本；TESTING-GUIDE 的 G1、G8 SQL 与 E10、D3、J2 预期；ARCHITECTURE 依赖表、common 描述、子包、review 示例、§4.1 签名、断引用与版本叙述；PRD 自引用与 §7 连续性、PR 状态两条限制；SECURITY 端点数与响应头；MANUAL / WALKTHROUGH 的 Prompt 版本；deliverables README 补充段；LIVE-EXERCISES 三处；spec / lint / `.env.example` 的 Phase 叙述。
- 补记 09-23 部署；本次部署写进本任务 `deployment.md`。

### R7 — 评测效度（论文）

- 如实披露：38 例中有真值的 31 例，需求背景与 AC 写出了缺陷类别，AC 还写出文件与行号；用例 ID（在所有臂的提示词里）与需求标题/描述点名缺陷。
- 冻结结果的事后分析：复用冻结评分器，分别去掉"类型"与"类型 + 类别"两个条件重新匹配；不调用模型、不改冻结文件。
- 去提示敏感性实验：同一 38 例、同一冻结 runner / 模型 / 温度，只把上述提示换成中性文本；新实验身份 `sensitivity-deleaked-38-v1`，运行一次、不调参，结果只作敏感性分析，不与正式口径合并分母。
- 产物：`docs/thesis/EVALUATION-SENSITIVITY.md`、逐例 CSV（不透明编号，不含 holdout ID）、标准库复算脚本；README 与 DEFENSE-GUIDE 链接。

### R8 — 稳定性复测

- 部署 R1–R4 后，对 37 个开放 PR（演示仓 14 + Halo 23）推两次空提交，产生两轮 `review-4`；以第二轮相对第一轮的 key 延续率与问题级重合度衡量稳定性，与此前两轮对照（后者换过 Prompt，如实标注）。
- 产物写进 `docs/thesis/`，复算脚本同上。

## Out of Scope

- 给模型看完整文件内容（会改变 Review 输入身份，非最小改动）；`category` 移出 `finding_key`；PR 开关状态；AC 裁定与 Finding 一致性规则；全仓英文注释翻译；JaCoCo、分页、知识 FAILED 重试。

## Constraints

- 冻结评测的 9 个文件、Flyway 迁移、holdout 台账与原始输出一字不动；私有语料只读。
- 只补承重测试：类型推导、认领人限制、温度随请求发出；其余同步既有断言。

## Acceptance Criteria

- [x] R1：CODE_QUALITY 带本修订 acId 的回答落为 REQUIREMENT；无 acId 落为 CODE_QUALITY；外来 acId 仍被丢弃。
- [x] R2：chat 请求体带 `temperature: 0`；429 后等待再重试。
- [x] R3：详情页可见丢弃数、纠行数与未再报告列表。
- [x] R4：非认领人把 IN_PROGRESS 标为 FIXED 被拒；终态流转有确认。
- [x] R6：上述文档条目全部同步；TESTING-GUIDE 全部 SQL 在线上库可执行。
- [x] R7、R8：数据集哈希固定，复算脚本通过。
- [x] 受影响测试类、前端四道门、后端全量 verify 通过；`verify-freeze` 仍通过。
