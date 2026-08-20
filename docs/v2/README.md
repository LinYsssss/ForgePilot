# ForgePilot V2 开发方案

ForgePilot 是围绕需求驱动 Pull Request 审查建设的轻量级 AI 研发协作平台。

状态：**R2.3 文档基线已于 2026-08-20 验收**。Phase 1 已获授权进入任务级规划；具体实现必须在 Phase 1 Trellis 任务的 `prd.md`、`design.md`、`implement.md` 经确认并执行 `task.py start` 后开始。Phase 2 及以后仍需单独授权。

## 权威文档

本目录只保留六份用于开发的权威文档。每类事实只在一个地方定义，其他文档只引用：

| 文档 | 权威内容 | 使用时机 |
|---|---|---|
| [PRD.md](./PRD.md) | 产品定位、角色权限、范围、状态与产品验收 | 判断做什么、谁能做 |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 模块边界、16 张表、数据库约束、流程与运行边界 | 判断怎么实现、不能越过什么边界 |
| [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) | Phase 顺序、授权闸门、任务级规划要求、测试与退出条件 | 安排开发和验收 |
| [DECISIONS.md](./DECISIONS.md) | D001–D011 的决策理由与不可逆后果 | 需要理解为何这样定或要提出变更 |
| [LEGACY-MIGRATION-MATRIX.md](./LEGACY-MIGRATION-MATRIX.md) | Legacy 资产的 KEEP/REWRITE/REFERENCE/DROP | 实施某模块前判断旧代码能否参考 |
| 本页 | 阅读入口、当前状态和不可违反的总边界 | 新会话或新开发者接手 |

推荐阅读顺序：本页 → `PRD.md` → `ARCHITECTURE.md` → `IMPLEMENTATION-PLAN.md` → 需要时查 `DECISIONS.md` 和迁移矩阵。

## 不可违反的总边界

- 后端是模块化单体，顶层包仅为 `common/auth/project/requirement/scm/knowledge/ai/review`。
- 首版数据模型上限为 16 张表；Finding 内聚于 `review`，只有一个 Review Engine。
- `scm` 发布 `PullRequestChanged` 事件但不依赖 `review`；AI 不直接改变业务状态或代码。
- 禁止 Agent、Patch、MQ/Outbox、第二 AI runtime、本地 clone/Git、第二 Review Pipeline、代码向量库和额外一级菜单。
- PostgreSQL 15+ 与 pgvector 是业务事实源；所有项目内引用和查询必须保持 `project_id` 隔离。
- Legacy RepoSage 只读，按迁移矩阵逐项提取，不整包复制，也不继承其迁移历史。

## 当前执行闸门

Phase 1 只允许建立最小绿地底座：Spring Boot/Vue/PostgreSQL 15+ pgvector、Flyway、Testcontainers、ArchUnit、基础 CI、前端脚手架与视觉契约、评测契约骨架和 4 GB 部署容量基线。不得实现登录、项目、需求、知识、SCM 或 Review 业务。完成 Phase 1 后必须停止并提交验收证据，等待下一阶段授权。

## 一句话主流程

负责人创建并指派带 AC 的需求，开发者获得一次性实现建议并提交关联 PR；ForgePilot 结合需求、项目知识和 Diff 生成可核验 Finding，Reviewer 退回或通过，开发者修复后复审，最后由 LEADER 确认需求完成。
