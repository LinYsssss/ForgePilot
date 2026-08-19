# ADR-007 PR ↔ Requirement 关联：解析优先，人工兜底

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §1.2 · §3.1、[ADR-004](./ADR-004-domain-cardinality.md)、[PRD.md](../PRD.md) §2 · §6 P1

## 背景

"开发者提交关联 PR" 是主流程入口：没有关联就没有 Requirement/AC 上下文，
产品定位（需求上下文增强的代码审查）不成立。此前该关联的**建立方式**未定义
（谁关联、在哪一步、失败怎么办、谁能改），被留到 Phase 5，属于 Phase 0 应冻结的范围。

## 决策

1. **自动解析优先**：SCM 同步 PR 时，从**分支名**与 **PR 标题**中解析 `REQ-<n>` 标记
   （不区分大小写，取第一个匹配），命中且该 Requirement 属于同一项目则写入
   `pull_request.requirement_id`。
2. **人工兜底**：Review/PR 页面提供下拉框修改关联，可设置也可清除。
3. **修改权限**：项目 LEADER 或该 PR 的作者（映射到本地账户时）。
4. **解析失败不阻断**：`requirement_id` 保持 NULL，PR 照常入库，
   Review 仍可创建但**标记为无需求上下文**，UI 显式提示"未关联需求"。
5. **关联变更不自动重跑 Review**：已完成的 Review 是对当时上下文的记录；
   需要重新审查由人工点击重试（沿用 ADR-003 复用同一 Review 行的语义）。
6. 跨项目关联由数据库复合外键拒绝（ADR-006），Service 不再自行校验。

## 后果与实施注记

- 实现量：一个正则解析器（`RequirementRefParser`，纯函数、可单测）+ 一个下拉框 + 一个更新接口。
  Legacy `RequirementLinkService` 的 `REQ-N` 提取思路可参考，但不迁其通用字符串 Link 模型。
- 解析在 `scm` 内完成，只产出候选编号；`requirement_id` 的写入需校验同项目——
  由复合外键保证，`scm` 不 import requirement 类型（不透明 id 模式，依赖方向不变）。
- 关联为 N:1（Requirement 1:N PR，ADR-004），下拉框允许多个 PR 指向同一需求。
- 演示脚本固定使用 `feat/REQ-<n>-<slug>` 分支命名，保证答辩现场自动路径可复现。
