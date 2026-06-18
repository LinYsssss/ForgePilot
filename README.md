# RepoSage 智能代码审查平台

RepoSage 以 Git Commit 的 Diff 为输入，结合项目知识库和大模型，生成结构化的代码审查报告：风险等级、问题定位、证据来源和修复建议。它适合作为「AI + 工程实践」的完整示例项目——既能用真实大模型跑通，也能在零配置（无 Key、无外部服务）下本地演示。

---

## 它能做什么

- 绑定一个 Git 仓库，针对某次 commit 的改动自动审查。
- 上传项目知识库（业务规则、接口规范、数据库结构、历史 Bug、安全规范），让审查结合**项目自身的上下文**，而不是泛泛而谈。
- 输出带证据的 JSON 报告：每个问题标注严重程度、类别、文件、建议，并尽量引用知识库中的依据。
- 内置规则引擎和轻量分类模型作为兜底/辅助信号，即使不接大模型也能识别常见风险（越权、SQL 注入、空指针、业务规则破坏）。

---

## 核心特性

- **真实大模型审查**：通过 OpenAI 兼容接口接入大模型（已验证小米 MiMo），也可切换到任何 OpenAI 兼容服务。
- **全量上下文注入**：知识库不大时，直接把项目全部文档喂给大模型，**无需 embedding / 向量数据库**，审查上下文更完整、更准确。
- **大 Diff 分片审查**：改动很大时按文件拆分、分批调用大模型再合并问题，避免整体 Diff 被静默截断丢失代码。
- **可选向量检索**：知识库很大时，可切换到内存余弦检索或 PostgreSQL + pgvector 向量检索（需要 embedding API）。
- **规则引擎兜底**：纯 Java 关键词/模式规则，识别常见高危模式，不依赖任何外部服务。
- **轻量分类模型**：FastAPI + scikit-learn（TF-IDF + 逻辑回归），对 Diff 做风险预判，结果作为信号注入审查上下文。
- **异步任务**：RabbitMQ 承载耗时审查任务，含重试与死信队列；开发环境可用 inline 模式免装 MQ。
- **调用弹性**：大模型调用接入 Resilience4j 重试 + 熔断，仅对网络超时 / 5xx / 429 等瞬时故障重试，连续失败时快速失败避免雪崩。
- **安全**：登录 Token 鉴权；Git accessToken 用 AES-GCM 加密存储，接口不返回明文。
- **可观测**：`ai_call_log` 记录每次大模型 / embedding / 模型调用的类型、模型、耗时、输入输出长度、**Token 用量**、状态和错误；并通过 Micrometer 把审查时延 / 吞吐 / Token 成本暴露到 `/actuator/prometheus`。
- **生产级护栏**：每请求 `X-Trace-Id` 贯穿日志（MDC）；按用户/IP 的接口限流（超限返回 429 + `Retry-After`）；`/actuator/health` 深度探活（AI provider / 模型服务 / DB / 磁盘，细节仅鉴权可见）。
- **可部署**：提供 Docker Compose（PostgreSQL+pgvector、RabbitMQ、后端、模型服务、前端、Nginx）。

---

## 架构概览

```text
浏览器
  │
  ▼
Nginx :80 ──► Vue 前端（静态页面）
  │
  └────────► Spring Boot 后端 :8080
                  │
                  ├─► PostgreSQL（业务数据，生产可启用 pgvector）
                  ├─► RabbitMQ（异步审查任务，可选 inline 模式）
                  ├─► FastAPI 模型服务 :8000（轻量风险分类，可选）
                  ├─► 大模型 API（OpenAI 兼容，如 MiMo，可选）
                  └─► Git 仓库 / 本地演示仓库（clone & diff）
```

> 注：本项目不使用 Redis。

---

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5、Spring Security、Spring Data JPA |
| 前端 | Vue 3、Vite |
| 数据库 | H2（开发）、PostgreSQL（生产，可选 pgvector 扩展） |
| 消息队列 | RabbitMQ |
| 大模型接口 | OpenAI 兼容 Chat API（MiMo 等），或内置 Mock |
| 检索 | 全量注入（默认推荐）／内存余弦／pgvector（可选） |
| 轻量模型 | FastAPI、scikit-learn、joblib |
| 部署 | Docker Compose、Nginx |

---

## 快速开始（本地，零配置）

开发环境默认使用 H2 内存库、Mock AI、inline 审查，**不需要安装 PostgreSQL / RabbitMQ，也不需要任何大模型 Key**，可直接跑通完整流程。

### 1. 启动后端

```text
cd backend
mvn -s .mvn/settings.xml spring-boot:run
```

后端启动在 `http://localhost:8080`。

### 2. 启动前端

```text
cd frontend
npm install --cache .npm-cache
npm run dev
```

打开 `http://localhost:5173`。

### 3.（可选）训练并启动模型服务

```text
cd model-service
python scripts/train_model.py --version local-demo-v1
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

模型状态：`GET http://localhost:8000/model/status`。
模型服务未启动时，后端会自动跳过模型预判，不影响审查。

---

## 接入真实大模型（以 MiMo 为例）

默认 `AI_PROVIDER=mock`（规则引擎模拟审查）。要用真实大模型，配置环境变量后重启后端即可，**不需要改任何代码**。

### 1. 确认你的模型名

用你的 Key 列出可用模型：

```text
curl https://token-plan-cn.xiaomimimo.com/v1/models -H "Authorization: Bearer <你的KEY>"
```

选一个对话模型，例如 `mimo-v2.5-pro`。

### 2. 配置环境变量

本地可在启动前设置环境变量；服务器部署见下文 Docker 部分（写入 `deploy/.env`）。关键项：

```text
AI_PROVIDER=openai-compatible
LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1
LLM_API_KEY=<你的KEY>
LLM_CHAT_MODEL=mimo-v2.5-pro
EMBEDDING_PROVIDER=mock
RAG_MODE=memory
RAG_FULL_CONTEXT=true
```

这样：对话审查走真实 MiMo；检索走**全量注入**（把项目全部文档喂给模型，不依赖 embedding）；文档上传时的向量用本地 mock 占位，不参与审查决策。

> **MiMo 没有 embedding 接口**，所以这里必须用全量注入而不是向量检索。这正是 `RAG_FULL_CONTEXT=true` 的用途。

---

## 配置项说明

所有配置通过环境变量注入，默认值见 `backend/src/main/resources/application.yml`，部署模板见 `deploy/.env.example`。

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `AI_PROVIDER` | `mock` | 审查提供方：`mock`（规则模拟）/ `openai-compatible`（真实大模型） |
| `LLM_BASE_URL` | 空 | 大模型 OpenAI 兼容地址，以 `/v1` 结尾 |
| `LLM_API_KEY` | 空 | 大模型 API Key |
| `LLM_CHAT_MODEL` | `mock-reviewer` | 对话模型名，如 `mimo-v2.5-pro` |
| `RAG_FULL_CONTEXT` | `false` | **`true` 时启用全量注入**，把项目全部知识库文档拼进 Prompt，不走向量检索 |
| `RAG_MAX_CONTEXT_CHARS` | `6000` | 全量注入时上下文最大字符数，超出截断，防止 Prompt 过长 |
| `RAG_MODE` | `memory` | 向量存储/检索模式：`memory`（内存余弦）/ `pgvector`（数据库向量） |
| `RAG_TOP_K` | `5` | 向量检索时返回的片段数（全量注入模式下不生效） |
| `EMBEDDING_PROVIDER` | `mock` | 向量化提供方：`mock`（本地占位）/ `openai-compatible`（真实 embedding API） |
| `REVIEW_INLINE` | `true` | `true` 同步审查（免 MQ）；`false` 走 RabbitMQ 异步 |
| `REVIEW_MAX_DIFF_CHARS` | `20000` | 单个 AI 调用的 Diff 字符预算（分片审查按此打包文件） |
| `REVIEW_MAX_TOTAL_DIFF_CHARS` | `200000` | 单任务存储的 Diff 总上限（安全边界，超出才截断） |
| `REVIEW_MAX_FILES` | `40` | 单任务最多审查的文件数，超出的文件跳过并在报告中说明 |
| `MODEL_SERVICE_ENABLED` | `false` | 是否启用 FastAPI 轻量模型预判 |
| `MODEL_SERVICE_URL` | 空 | 模型服务地址，如 `http://model-service:8000` |
| `RATE_LIMIT_ENABLED` | `true` | 是否启用 `/api/**` 接口限流 |
| `RATE_LIMIT_REQUESTS` | `120` | 单个时间窗内每用户/IP 允许的请求数，超出返回 429 |
| `RATE_LIMIT_WINDOW_SECONDS` | `60` | 限流时间窗长度（秒） |

---

## 检索模式怎么选

审查时需要把「项目上下文」喂给大模型。两种方式：

| 模式 | 配置 | 适合 | 是否需要 embedding API |
| --- | --- | --- | --- |
| **全量注入（推荐）** | `RAG_FULL_CONTEXT=true` | 知识库总量不大（约几千字到几万字） | **不需要** |
| 向量检索 | `RAG_FULL_CONTEXT=false` + `RAG_MODE=memory`/`pgvector` | 知识库很大，需要先筛选相关片段 | 需要（除非用 mock 占位） |

**建议**：先用全量注入。它把完整项目规则交给大模型，审查更全面，且完全不依赖 embedding。等单个项目的知识库涨到接近 `RAG_MAX_CONTEXT_CHARS`（默认 6000 字）再考虑向量检索。

> 知识库太大怎么办？优先**精炼文档**（写成可校验的规则条目，而非长篇叙述），按 `docType` 分类、按项目隔离、删除过时内容。信息密度比体量更重要。

---

## 使用流程

1. 注册 / 登录。
2. 创建项目。
3. 绑定 Git 仓库（演示可用 `demo-repos/mall-order-service`）。
4. 上传知识库文档（Markdown / TXT），如 `security-policy.md`、`order-flow.md`。
5. 查询 Commit，选择要审查的提交。
6. 触发审查任务。
7. 查看审查报告：风险等级、问题列表、证据来源、修复建议。
8. 对问题提交人工反馈（确认 / 误报 / 备注）。
9. 在 PR 工作流中登记 Pull Request，基于 PR 触发审查，并按报告执行“通过 / 打回修改 / 风险豁免”。
10. 查看 AI 调用日志与 MQ 日志。

**验证大模型与全量注入是否生效**：报告中的问题应能引用到你上传文档里的具体内容（例如发货问题引用 `order-flow.md` 的支付校验规则），说明完整项目上下文确实进入了 Prompt。

---

## API 速查

统一前缀 `/api`，除注册登录外均需请求头 `Authorization: Bearer <token>`。

| 模块 | 方法与路径 |
| --- | --- |
| 认证 | `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/me` |
| 项目 | `POST /api/projects`、`GET /api/projects`、`GET/PUT/DELETE /api/projects/{projectId}` |
| 仓库 | `POST/GET/DELETE /api/projects/{projectId}/repository`、`GET .../commits`、`GET .../commits/{commitId}/diff` |
| PR 工作流 | `POST/GET/PUT /api/projects/{projectId}/pull-requests`、`POST .../pull-requests/{pullRequestId}/review-task`、`POST/GET .../pull-requests/{pullRequestId}/actions` |
| 知识库 | `POST/GET /api/projects/{projectId}/knowledge/documents`、`DELETE .../documents/{documentId}`、`POST .../knowledge/search` |
| 审查 | `POST/GET /api/projects/{projectId}/reviews/tasks`、`GET .../tasks/{taskId}`、`GET .../reviews/reports`、`GET .../reports/{reportId}` |
| 反馈 | `POST/GET /api/review-issues/{issueId}/feedback` |
| MQ 日志 | `GET /api/mq/logs` |
| AI 日志 | `GET /api/ai/logs` |

接口字段细节见 `docs/03_接口设计文档.md`。

---

## Docker 部署

```text
cd deploy
cp .env.example .env
# 编辑 .env：填入真实 LLM_API_KEY、改掉 DB_PASSWORD/JWT_SECRET/TOKEN_ENCRYPT_KEY 等默认值
docker compose up -d --build
```

如果是从 GitHub 新 clone 到服务器，先初始化演示仓库（让后端能 clone & diff）：

```text
bash scripts/init-demo-repo.sh
```

启动的服务：PostgreSQL + pgvector、RabbitMQ、Spring Boot 后端、FastAPI 模型服务、Vue 前端、Nginx。
对外入口：前端 `http://服务器IP/`，健康检查 `http://服务器IP/actuator/health`，RabbitMQ 管理台 `http://服务器IP:15672`。

生产环境启动时执行 `backend/src/main/resources/db/schema-postgres.sql` 初始化表与 pgvector 表，并以 `ddl-auto=validate` 校验实体结构。详见 `docs/12_服务器部署与演示手册.md`。

---

## 构建与测试

后端测试：

```text
cd backend
mvn -s .mvn/settings.xml test
```

前端构建：

```text
cd frontend
npm run build
```

一键本地验收（后端测试 + 前端构建 + 模型服务检查 + 后端冒烟 + Docker 可用性检查）：

```text
.\scripts\verify-local.ps1
```

后端冒烟（启动后端后执行，跑通注册→建项目→绑仓库→传知识库→审查→报告→反馈全链路）：

```text
.\scripts\smoke-backend.ps1
```

---

## 常见问题

**没有 embedding API 怎么办？**
用全量注入（`RAG_FULL_CONTEXT=true`），完全不需要 embedding。MiMo 等只提供对话接口的厂商正适用这种方式。

**知识库太大、超过上下文怎么办？**
先精炼文档（规则条目化、按类型分、删冗余）。仍然过大时切换到向量检索（`RAG_FULL_CONTEXT=false` + `RAG_MODE=pgvector`），但这需要 embedding API。

**API Key 会不会被提交到 Git？**
不会。`.env` 和 `deploy/.env` 已在 `.gitignore` 中；仓库只跟踪不含真实 Key 的 `deploy/.env.example`。请把真实 Key 填进 `deploy/.env`，切勿写进 `.env.example`。

**RabbitMQ 日志为空？**
开发环境默认 `REVIEW_INLINE=true`，审查同步执行、不经过 MQ，所以 MQ 日志为空。设 `REVIEW_INLINE=false` 后才走 RabbitMQ。

---

## 文档索引

| 文档 | 说明 |
| --- | --- |
| `docs/README.md` | 文档总索引 |
| `docs/01_系统架构设计说明书.md` | 系统架构、模块与部署结构 |
| `docs/02_数据库设计说明书.md` | 表结构与数据关系 |
| `docs/03_接口设计文档.md` | API 字段与约定 |
| `docs/04_MQ与异步任务设计.md` | RabbitMQ、重试、死信、幂等 |
| `docs/05_RAG与AI审查设计.md` | 检索、Prompt、AI JSON 输出 |
| `docs/08_部署环境与配置清单.md` | 环境变量与配置 |
| `docs/11_本地开发与联调手册.md` | 本地启动与联调 |
| `docs/12_服务器部署与演示手册.md` | 服务器部署与演示 |
| `代码仓库智能审查平台_需求规格说明书.md` | 需求规格说明 |
