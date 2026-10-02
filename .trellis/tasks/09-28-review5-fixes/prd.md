# 审查修正：review-5、详情提示与认领人规则

## Goal

修正 2026-09-27 审查发现的四项问题，避免分类偏移、误导性的未再报告提示与无法继续处理的 Finding。最小改动，不过量测试，关键边界写清中文注释。

## Background

原会话 `af1b8a97-60d1-4e7c-95e0-b746a61f86d9` 因中断未完成计划。2026-09-30 在 `9af6857` 核对代码，审批稿 `/root/.claude/plans/zany-twirling-fern.md` 已获批准；批准范围为本地实施和验证，提交、推送、部署与真实模型复测须另行确认。

## Requirements

### R1 — 恢复模型输出类型，矛盾时保留为代码质量类

`review-4` 仅按 acId 推导类型，旧会话观察到 Halo 代码质量类 Finding 数下降。两个输出 schema 恢复 type，Prompt 升到 `review-5`。类型与合法引用矛盾时保留 CODE_QUALITY、清空 AC 并记录 warning；缺失/未知类型沿用 acId 推导兼容。外来 AC、虚构证据与不合法知识引用仍拒绝。

依据：`backend/src/main/java/com/forgepilot/review/ReviewPrompts.java:61`、`ReviewOutputValidator.java:190`（计划时行号）。不把保留证据等同于问题真实，不承诺模型分类比例。

### R2 — 仅在详情中收窄未再报告提示

旧会话记录 B 轮 22 条提示中有 12 条按宽松口径重报；此为历史观察，非本次线上复测。详情排除本轮存在同类型、同路径、同位置/同 AC 候选的项。需求不能跨 requirementId 匹配，缺失位置不猜测。保留历史入口与粗粒度口径说明，不改变血缘、人工驳回继承或状态。

依据：`review/ReviewDecisionService.java:207–224`、`FindingContinuityCalculator.java:73`（计划时行号）。

### R3 — 无人认领的有限例外

成员退出清空认领后，IN_PROGRESS 可由任一本项目 DEVELOPER 标记已修复；认领人存在时仍仅认领人可操作。前后端规则一致，非认领人返回明确的 403 提示；保留操作者审计，不自动重新认领。

依据：`review/FindingLifecycleService.java:109`、`RemovedMemberClaimListener.java:27`、`frontend/src/features/review/FindingCard.vue:50`（计划时行号）。

### R4 — 纠行数只统计 Finding

从 warning 派生的 correctedLines 排除 AC evidence 纠行，文案明确包括分批候选，是纠正事件数而非最终 Finding 数。历史摘要不改写。

依据：`review/ReviewOutputValidator.java:531`、`ReviewDecisionService.java:309`（计划时行号）。

### R5 — 同步当前契约，保留历史事实

同步架构、API、角色权限、页面与交付说明。论文补记 review-4 类型比例受规则改变影响。冻结结果、旧 CSV/哈希不动；未部署或复测前不改线上版本、不编写新效果结论。

### R6 — 全仓有效性与冗余审计（2026-09-30 追加）

按用户追加要求核对整个仓库的代码、文档、入口、配置与资产用途，修复已确认的过时规范、README 命令、注释乱码和语义夸大。静态引用缺失不等于废代码，只有排除框架注册、构建/测试入口与历史证据用途后才删除。当前批准计划见 `/root/.claude/plans/zany-twirling-fern.md`，过程与结果记入 `audit.md`。不动生产库、冻结/历史证据、迁移、缓存和多平台工具设置；不把审计当作功能扩展或磁盘清理。

## Constraints / Out of Scope

- 不新增依赖、表、迁移、接口、配置；不改 key/hash 算法、RULE_VERSION、历史 Finding。
- 不动冻结评测、holdout、原始输出、旧 CSV 与已应用 Flyway 文件。
- 不做完整文件读取、多次采样、置信度校准、测试文件降噪、AC/Finding 语义一致性、分页、知识失败重试或 SFC 拆分。
- 不自动提交、推送、部署或调用真实模型，不派生代理。
- 不新建测试类、不重命名历史测试文件。后端全量 verify 一次，失败才定向检查。

## Acceptance Criteria

- [x] R6：全仓静态审计记录已完成，现行规范/入口/注释修正；重复指南去重，有用途的代码与历史材料保留，保护文件哈希和行为 token 不变，未验证边界见 audit.md。

- [x] R1：两种类型/合法引用矛盾保留 CODE_QUALITY、acId 为空并有 warning；外来引用与虚构证据仍拒绝；两份 schema 的 type 同步。
- [x] R2：同位置/同 AC 候选只影响详情提示；跨需求同名 AC、空定位不误合并，真正未报仍保留。
- [x] R3：认领人/无人认领规则与 UI 一致，非开发者不获新权限，成功流转留下实际操作者审计。
- [x] R4：混合 warning 的详情统计只计 Finding 纠行；页面注明包含分批候选。
- [x] R5：相关契约和历史限制同步，无虚构部署/复测结果。
- [x] 一次后端全量 verify、前端四道门、冻结与订正校验通过；未做的浏览器/线上验收如实标注。
