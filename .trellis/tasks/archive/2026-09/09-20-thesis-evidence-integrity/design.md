# 技术设计

## 1. 边界

本任务只修改 `review` 包的输出校验、`requirement` 包的质量检查分支、对应后端测试及直接受影响的文档。数据库、HTTP 路由、JSON 字段、前端实现和正式评测资产保持不变。

## 2. Diff 证据锚定

在 `ReviewOutputValidator` 内加入私有、无依赖的 unified-diff 索引逻辑：

1. 按 hunk header 解析新侧起始行。
2. 对每个 hunk 单独重建新侧连续源码：上下文行推进旧/新行号，新增行只推进新行号，删除行只推进旧行号。
3. 保存每个新侧源码行文本与真实新侧行号；忽略文件头、hunk 元数据、无换行标记及截断标记。
4. 将 evidence/excerpt 仅做 CRLF/LF 归一化后，在单个 hunk 的连续源码中查找；匹配可从行内子串开始并可跨连续多行，但不能跨 hunk。
5. 返回所有真实起始行：
   - 0 个：拒绝该项；
   - 1 个：锚定该行；
   - 多个：仅当模型行号等于某个候选时用其消歧，否则锚定成功但行号为空。

Finding 与 AC evidence 共用这一方法。现有 path/source/ac 白名单校验仍先执行。warning 只描述校验事实，不改变 Review 状态机；当所有 Finding 被过滤时仍按现有有效输出语义处理。

## 3. 需求质量超预算

`RequirementQualityService` 对脱敏后的最终 Prompt 长度只判断一次：

- 预算内：沿用现有 `AiGateway.chat`、解析与持久化路径。
- 超预算：构建只含确定性规则的 `QualityReport`，加入 `PROMPT_BUDGET_EXCEEDED`，`ai=null`，直接进入相同持久化/返回路径，完全绕过网关。

版本从 `quality-1` 升为 `quality-2`，用于区分语义变化；数据库字段和历史 JSON 不迁移。

## 4. 兼容性与风险

- 前端类型和页面已允许 `ai=null`，因此不需要接口兼容层。
- 证据校验会比当前严格，可能过滤以前被接受的模型输出；这是有意的可信性收紧，以 warning 保持可诊断性。
- unified diff 解析只覆盖系统已经接收的 provider patch 语法；未知或畸形 hunk 不猜测行号，证据无法验证时拒绝。
- 不修改 hash 规则和版本常量，避免让既有 suppression/continuity 无故失效。

## 5. 回滚

代码改动没有数据迁移。若校验器出现兼容问题，可整体回退 Validator 和 Requirement Quality Service 的提交；已保存的 `quality-2` JSON 仍能被当前兼容结构读取。正式评测与历史证据未被修改，无需数据恢复。
