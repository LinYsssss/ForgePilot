# ForgePilot

ForgePilot 是一个面向软件研发流程的轻量级 AI 研发协作与代码审查平台。

毕业设计题目：**ForgePilot：基于需求与项目知识上下文增强的智能代码审查系统设计与实现**。

它围绕一条主线建设：

```text
项目与成员
→ 需求与验收条件
→ 指派开发
→ AI 生成实现建议
→ Pull Request
→ 需求/知识上下文增强审查
→ 人工通过或打回
→ 修复与复审
```

## 当前状态

**Phase 0–8 已于 2026-08-22 全部完成并通过退出闸门**。D017–D019 的产品补全保持有效；
2026-08-24 按 [D020](docs/v2/DECISIONS.md#d020) 增加显示名、成员目录、多角色、用户自有 SCM 多身份和项目身份绑定，未增加顶层包、一级导航、运行时依赖或第二 Review 流程。

当前形态：

| 维度 | 事实 |
|---|---|
| 后端 | Spring Boot 4.1 模块化单体，8 个顶层业务包，19 张业务表 / 8 个 Flyway 迁移，317 个测试 |
| 前端 | Vue 3 + TypeScript + Vite，6 个一级导航 / 11 条产品路由，11 个测试文件 / 35 个测试 |
| SCM | GitHub 与 GitLab 双 Provider；用户多身份、标签/用途、项目绑定与可选 Leader 审批 |
| AI | 单一 OpenAI-compatible 网关，服务需求质量检查、一次性实现建议与唯一 Review Engine |
| 知识 | PostgreSQL 15 + pgvector，按 `project_id` 与当前 Requirement 双重硬过滤 |
| 评测 | 三臂对照实验已正式运行，holdout 按约定只跑一次 |

验证使用 Testcontainers 真实 PostgreSQL 15 + pgvector；后端 317 个测试零跳过，前端 lint / typecheck / 35 个测试 / build 全绿。当前任务证据记录在对应 Trellis `result.md`。

旧版完整源码与历史工程能力保存在 [RepoSage](https://github.com/LinYsssss/reposage)，只作为 Legacy Reference，不直接复制回本仓库。

## 从这里开始

AI 或开发者进入仓库后，按以下顺序阅读：

1. [V2 文档入口与当前状态](docs/v2/README.md)
2. [产品需求](docs/v2/PRD.md)
3. [架构规范](docs/v2/ARCHITECTURE.md)
4. [实施计划与阶段结论](docs/v2/IMPLEMENTATION-PLAN.md)
5. [决策记录](docs/v2/DECISIONS.md)
6. [账户、成员与 SCM API](docs/v2/API.md)
7. [网页全链路测试指南](docs/v2/FULL-CHAIN-UI-TEST.md)
8. [答辩复现指南](docs/v2/DEFENSE-GUIDE.md)
9. [Legacy 迁移矩阵](docs/v2/LEGACY-MIGRATION-MATRIX.md)

## 仓库结构

```text
backend/       Spring Boot 模块化单体，8 个业务包 / 19 张表 / 8 个 Flyway 迁移
frontend/      Vue 3 + TypeScript + Vite 前端，6 个一级导航 / 11 条产品路由
evaluation/    论文评测与可复现实验入口，含正式三臂实验工具链与冻结配置
docs/v2/       产品与架构的唯一事实源
scripts/       空卷冷启动冒烟脚本
.trellis/      任务计划、验收证据与交接规则
```

## 快速开始

需要 Docker 与 Compose v2。空白 AI 凭据也能起栈，此时 AI 相关调用会显式失败而不是静默降级。

```bash
cp .env.example .env
# 至少替换 FORGEPILOT_DB_PASSWORD 与 FORGEPILOT_SCM_SECRET_KEY
docker compose up --build --detach --wait
```

三个服务健康后，前端在 `.env` 配置的回环地址上；后端健康契约是 `/actuator/health`，
经前端代理为 `/api/actuator/health`。完整的干净复现、构建闸门与评测重算步骤见
[答辩复现指南](docs/v2/DEFENSE-GUIDE.md)。

各自的开发命令：

```bash
cd backend  && ./mvnw -B -ntp verify        # 需要 JDK 21 与可用的 Docker（Testcontainers）
cd frontend && npm ci && npm run lint && npm run typecheck && npm run test -- --run && npm run build
```

## 核心边界

- 采用模块化单体，不以“企业级”为理由增加微服务和中间件。
- 只有一条 Review Engine；PR 自动触发和人工重试共用同一入口。
- AI 只提供需求检查、一次性实现建议和 PR 审查，不自动修改代码或改变业务状态。
- 项目知识使用 PostgreSQL + pgvector，严格按 `project_id` 隔离。
- Agent、Patch、RabbitMQ、Outbox、Risk Model、Sandbox 不进入 V2 主线。
- 旧代码只能按 `KEEP / REWRITE / REFERENCE / DROP` 决策逐项提取。

## 正式评测结论

全量 38 例，模型 `gpt-5.6-luna`、温度 `0.0`：

| 对照臂 | 精确率 | 召回率 | 需求违规召回 |
|---|---:|---:|---:|
| 仅 Diff | 12.12% | 12.90% | 0% |
| Diff + 需求/AC | 21.62% | 25.81% | 80% |
| Diff + 需求/AC + 项目知识 | **32.26%** | **32.26%** | **90%** |

holdout 仅 12 例，样本偏小，结论为描述性而非总体推断；语料为人工构造的演示缺陷，非真实企业缺陷。
配置冻结、语料清单、holdout 台账与原始输出是**不可变证据**，不得删除、覆盖或重跑。
复现步骤见[答辩复现指南](docs/v2/DEFENSE-GUIDE.md)。

## 已知缺口

如实记录，不在文档里补成“已完成”：

- **语义检索没有向量索引**，走顺序扫描的精确余弦序。这是[决策接受](docs/v2/DECISIONS.md#d019)的结果，不是遗漏：
  冻结的 `Qwen3-Embedding-8B` 是 4096 维，超过 pgvector 0.8.6 全部精确索引形态的维度上限，可建的两种形态都是有损预筛。
- **需求状态转换不单独留痕**。这是 [D013.3](docs/v2/DECISIONS.md#d013) 明确接受的 MVP 缺口，不是遗漏。
- **超限 changed-file 投递不留痕**。整条 422 拒绝，运维看不到“有 PR 因过大被拒”（[D016.1](docs/v2/DECISIONS.md#d016)）。
- **浏览器点击闭环、响应式与视觉漂移检查为人工验收**，未自动化；历次批次均如实记为部分通过。

## License

[MIT](LICENSE)
