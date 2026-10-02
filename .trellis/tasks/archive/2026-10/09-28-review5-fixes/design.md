# review-5 技术设计

## 边界

只有既有 Review Engine。改动局限于输出校验、详情查询与人工权限；没有新 API、表、依赖或配置。`finding_key/evidence_hash/basis_hash` 算法与 `FindingKeys.RULE_VERSION` 不动。

## R1 类型与引用

两份 finding schema 恢复必填 type（REQUIREMENT/CODE_QUALITY），`ReviewPrompts.VERSION=review-5`；指令明确代码质量问题不要硬挂 AC。保留中文散文、逐字证据、不可信输入要求。综合阶段复用已有 `appendCandidates`，不新建数据通道。

| type | acId | 结果 |
|---|---|---|
| REQUIREMENT | 当前修订合法 AC | REQUIREMENT，保留 AC |
| CODE_QUALITY | null | CODE_QUALITY |
| CODE_QUALITY | 当前修订合法 AC | CODE_QUALITY，清空 AC，warning |
| REQUIREMENT | null | CODE_QUALITY，warning |
| 缺失/未知 | null 或合法 AC | 按 acId 推导兜底，warning |
| 任意 | 外来 AC | 整条拒绝 |

先验证 AC 归属，再做类型修正，不能先删引用绕过拒绝。复用现有解析、锚定和 FindingKeys；修正后的 type/criterion 同时参与 candidate、key、basis hash。父 Review 的 requirementId/revisionId 不变。warning 以 kept 开头，不计作丢弃。

不修改 explanation/suggestion/category；保留锚定证据不证明语义真实。恢复字段不保证分类比例或精度。

## R2 展示层比对

复用 `continuity.notReported` 按 key 得到候选，复用 `acKeysOf` 批量读取两份列表的 AC 业务键，然后在 `ReviewDecisionService.detail` 过滤。

私有 `sameProblem` 规则：同类型、同非空大小写敏感 path；REQUIREMENT 必须同非空 requirementId、同非空 ac_key；CODE_QUALITY 必须同非空行号。类型变化、缺少 AC/定位不猜测。只处理 COMPLETED。

不改 FindingContinuityCalculator，不触碰血缘、抑制和人工状态。此为粗粒度提示去重，不是语义等价：同 AC 可有不同缺陷，类型/行号变化也可漏匹配。页面明示口径，仍可回看上一轮。

## R3 认领规则

沿用角色矩阵、状态条件更新与 finding_event。MARK_FIXED 在认领人非空且不是调用人时拒绝，403/forbidden，中文消息“只有认领人可以标记已修复。”。认领已清空时仍须有 DEVELOPER 角色；不自动认领，审计记录实际操作者。

`RemovedMemberClaimListener` 已有清空路径，不动数据库与监听器。不扩展到认领人仍在项目但角色被撤销时的重新指派。

前端复用 `availableMoves` 后过滤 MARK_FIXED，账户取 `useSession`；无登录账户不提供该操作。与后端一样支持多角色能力并集。

## R4 warning 统计

保留 correctedLines 字段、历史 warning 原文，`correctsLine` 仅匹配 `corrected the line of a `，排除 `corrected the line of AC evidence`。计数是 Finding 纠行事件（含分批候选），可大于最终 Finding 数。API 注释和页面同步。

## R6 全仓收尾审计

按入口/实现/文档/测试/证据/生成物分类跟踪文件，扫描符号引用、本地链接与锚点、语法、结构计数和配置映射，再人工核实候选。不用无引用 grep 结果删除 Spring Controller、Listener、接口实现或本文件使用的类型。修复当前规范中旧 Phase 1 占位描述，历史阶段记录保持原样。只改注释时对比源码去注释 token，复用此前行为未变的测试结果；本轮不重跑完整后端以制造验证数量。

## 兼容性与上线

不回填历史行、不改旧实验文件，历史详情会采用新的派生提示/计数。Prompt 版本只用于新报告审计；类型变化本身可能改变新 Finding 的 key，但不是算法升级，不为此提高 RULE_VERSION。

部署另获批准后先保留旧镜像、pg_dump，再串行构建/更新应用；不删除卷。回退旧应用镜像，不默认恢复数据库或删除新报告。真实模型复测需单独批准，使用新轮次和新数据文件，不能把随机输出比例列为代码验收门槛。
