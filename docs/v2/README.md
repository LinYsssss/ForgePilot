# ForgePilot V2 开发方案

本目录是 ForgePilot V2 绿地开发的权威规划入口。

## 阅读顺序

1. [最终方案候选版](./FINAL-CANDIDATE.md)
2. [产品需求文档](./PRD.md)
3. [实施蓝图](./IMPLEMENTATION-PLAN.md)
4. [第二轮架构交叉审查](./ARCHITECTURE-REVIEW.md)
5. [Legacy 迁移矩阵](./LEGACY-MIGRATION-MATRIX.md)
6. [Legacy 实库审计](./LEGACY-AUDIT.md)

## 当前状态

- Legacy 代码仅作为技术参考，不在旧架构上继续重构。
- V2 定位为“基于需求与项目知识上下文增强的 AI 代码审查系统”。
- 本目录提交的是开发方案，不代表已经生成或实施 V2 代码。
- 开发时以 `FINAL-CANDIDATE.md` 和 `PRD.md` 为产品与架构依据。

## 一句话主流程

> 负责人创建并指派带 AC 的需求，开发者提交关联 PR 后，ForgePilot 结合需求、项目知识与 Diff 生成可核验 Finding，Reviewer 据此退回或通过，开发者修复后复审闭环。
