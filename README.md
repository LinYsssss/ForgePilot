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

## 当前形态

| 维度 | 事实 |
|---|---|
| 后端 | Spring Boot 4.1 模块化单体，8 个顶层业务包，20 张业务表 / 10 个 Flyway 迁移 |
| 前端 | Vue 3 + TypeScript + Vite，6 个一级导航 / 11 条产品路由 |
| SCM | GitHub 与 GitLab 双 Provider；用户多身份、标签与用途、项目绑定与可选 Leader 审批 |
| AI | 单一 OpenAI-compatible 网关，服务需求质量检查、一次性实现建议与唯一 Review Engine |
| 知识 | PostgreSQL 15 + pgvector，按 `project_id` 与当前 Requirement 双重硬过滤 |
| 评测 | 三臂对照实验，holdout 只跑一次 |

验证使用 Testcontainers 真实 PostgreSQL 15 + pgvector：后端测试零跳过，前端 lint / typecheck / test / build 全绿。

## 从这里开始

```text
1. docs/v2/README.md        文档入口与总边界
2. docs/v2/PRD.md           产品定位、角色权限、范围与业务状态
3. docs/v2/ARCHITECTURE.md  模块边界、数据模型、流程契约与运行边界
4. docs/v2/API.md           账户、成员与 SCM 身份接口契约
5. docs/v2/DEFENSE-GUIDE.md 部署、构建闸门与评测复现
```

## 仓库结构

```text
backend/       Spring Boot 模块化单体，8 个业务包 / 20 张表 / 10 个 Flyway 迁移
frontend/      Vue 3 + TypeScript + Vite 前端，6 个一级导航 / 11 条产品路由
evaluation/    评测工具链、确定性打分器与冻结配置
docs/v2/       产品与架构的唯一事实源
scripts/       空卷冷启动冒烟脚本
.trellis/      任务计划与工程规范
```

## 快速开始

需要 Docker 与 Compose v2。空白 AI 凭据也能起栈，此时 AI 相关调用会显式失败而不是静默降级。

```bash
cp .env.example .env
# 至少替换 FORGEPILOT_DB_PASSWORD 与 FORGEPILOT_SCM_SECRET_KEY
docker compose up --build --detach --wait
```

三个服务健康后，前端在 `.env` 配置的回环地址上；后端健康契约是 `/actuator/health`，经前端代理为 `/api/actuator/health`。完整的干净复现、构建闸门与评测重算步骤见[答辩复现指南](docs/v2/DEFENSE-GUIDE.md)。

各自的开发命令：

```bash
cd backend  && ./mvnw -B -ntp verify        # 需要 JDK 21 与可用的 Docker（Testcontainers）
cd frontend && npm ci && npm run lint && npm run typecheck && npm run test -- --run && npm run build
```

## 核心边界

- 采用模块化单体，不以"企业级"为理由增加微服务和中间件。
- 只有一条 Review Engine；PR 自动触发和人工重试共用同一入口。
- AI 只提供需求检查、一次性实现建议和 PR 审查，不自动修改代码或改变业务状态。
- 项目知识使用 PostgreSQL + pgvector，严格按 `project_id` 隔离。
- Agent、Patch、RabbitMQ、Outbox、Risk Model、Sandbox 不进入主线。

## 正式评测结论

全量 38 例，模型 `gpt-5.6-luna`、温度 `0.0`：

| 对照臂 | 精确率 | 召回率 | 需求违规召回 |
|---|---:|---:|---:|
| 仅 Diff | 12.12% | 12.90% | 0% |
| Diff + 需求/AC | 21.62% | 25.81% | 80% |
| Diff + 需求/AC + 项目知识 | **32.26%** | **32.26%** | **90%** |

这三臂证明的是「把需求与项目知识放进上下文有用」，而不是「某条检索管线有效」——评测工具链不经过后端。

holdout 仅 12 例，样本偏小，结论为描述性而非总体推断；语料为人工构造的演示缺陷，非真实企业缺陷。配置冻结、语料清单、holdout 台账与原始输出是**不可变证据**，不得删除、覆盖或重跑。复现步骤见[答辩复现指南](docs/v2/DEFENSE-GUIDE.md)。

## 已知限制

如实记录，不在文档里补成"已完成"：

- **语义检索没有向量索引**，走顺序扫描的精确余弦序。冻结的 `Qwen3-Embedding-8B` 是 4096 维，超过 pgvector 0.8.6 全部精确索引形态的维度上限，可建的两种形态都是有损预筛，因此选择不建。
- **需求状态转换不单独留痕**（`DRAFT→READY`、指派、`CANCELED`、`DONE`）。
- **超限 changed-file 投递不留痕**：整条按 422 拒绝。
- **浏览器点击闭环、响应式与视觉漂移检查为人工验收**，未自动化。

## License

[MIT](LICENSE)
