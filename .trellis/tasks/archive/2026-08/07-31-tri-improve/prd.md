# PRD：三线改进（清理 + 前端重构美化 + 功能增强）

> 父任务。拥有需求源、子任务地图与跨子任务验收；实现工作在子任务中进行。
> 计划全文：/root/.claude/plans/graceful-beaming-pascal.md（2026-07-31 用户已批准）

## 目标与价值

在安全整改（Track A/B）合流完成的基线上，把 RepoSage 推进到"架构可维护、界面出彩、演示有说服力"的状态。用户决策：

- 无明确 deadline，按**质量最优**推进（答辩方案"非目标"约束解除）。
- 前端美化 = 现有 Observatory 体系打磨升级 + **现代创意视觉用于加分**。
- 功能 = 文档既有 backlog + **带/不带知识库对比审查**演示功能。
- `deploy/.env.bak.*` 密钥备份移出仓库另存。

## 子任务地图（按执行顺序）

| 顺序 | 任务 | 类型 | 核心交付 |
|---|---|---|---|
| 1 | 07-31-cleanup-baseline | 轻量 | 干净的仓库基线：密钥外移、杂物入 ignore、工作流文件入库、docs 归档、verify-local.sh、环境探测记录 |
| 2 | 07-31-frontend-split | 复杂 | App.vue(1540 行) → views + 领域 composables + AppShell + vue-router；行为不变；composable 行为测试 |
| 3 | 07-31-frontend-visual | 复杂 | Observatory 升级：aurora 背景、玻璃拟态、微交互、图表/diff 质感、响应式与可达性 |
| 4 | 07-31-feature-enhance | 复杂 | PR 直达 Run、citation 定位+证据抽屉、虚拟滚动、Agent Run/AI 日志分页收尾、对比审查视图 |

依赖：2 依赖 1（干净基线）；3 依赖 2（先拆再美化）；4 依赖 2（新功能落在拆分后结构），其中后端分页项可独立穿插。

## 跨子任务约束（全部子任务必须遵守）

- 不改冻结契约（ErrorCode/PageResponse/ProjectAuthorization 签名）与已执行 Flyway 迁移；新迁移接当前最大版本号之后（动手前实测确认）。
- 新/改 ID 型端点必须纳入 ObjectLevelAuthorizationMatrixTest 反向授权矩阵。
- 新增前端依赖必须过 `npm audit --audit-level=high` + trivy 门禁。
- YAML 改动后跑 yaml.safe_load 校验；`@Transactional` 不做同类自调用。
- demo-repos 及其知识文档必须保留。
- 工作分支：`integration/track-ab` 小步提交；commit 风格沿用现有历史（type(scope): 中文摘要）。

## 跨子任务验收

1. 全量测试保持绿：backend `mvn -s .mvn/settings.xml verify`、sandbox-runner `mvn test`、frontend `npm test && npm run build`（环境缺失项如实标注"未验证"）。
2. 端到端演示流可走通：登录→建项目→绑 demo 仓库→传知识库→审查→报告→对比审查→PR 闭环→Agent 工作台（dev profile，H2+mock AI）。
3. README / docs 与最终实际状态一致（诚实口径）。
