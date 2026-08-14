# RepoSage 智能代码审查平台

RepoSage 以 Git Commit 的 Diff 为输入，结合项目知识库和大模型，生成结构化的代码审查报告：风险等级、问题定位、证据来源和修复建议。它适合作为「AI + 工程实践」的完整示例项目——既能用真实大模型跑通，也能在零配置（无 Key、无外部服务）下本地演示。

它包含两条能力线：一是**交互式审查**——手动针对某次 commit / PR 的 Diff 触发审查；二是**事件驱动的 PR 守门 Agent**——由 GitHub/GitLab 的 PR webhook 自动触发一条持久化、可观测、带预算护栏的 Agent 流水线，在签名沙箱里取证，产出带证据的问题与门禁裁决，并可生成经人工审批的修复补丁。

## 当前进度（2026-08-12）

当前研发基线已完成 r1-r6：CI 阻塞与沙箱链路修复、工程口径收敛、规范沉淀、后端重构以及前端 Element Plus / design tokens 升级均已归档。

- **r7 评测地基**：评测语料已扩充到 38 例（development 26 / holdout 12），安全类达到计划下限 8 例，确定性建仓、隔离栈驱动和两率判分工具已落地；`z-ai/glm-5.2` 的 32 例历史基线为漏报率 36.00%（9/25）、误报率 81.82%（72/88），扩容后的 38 例基线待按相同参数复跑，旧数字不作为当前语料结果。
- **r8 提示词调优**：R1 分层模板已实现，模板注册表、唯一组装入口和 golden 测试已提交；R2 已完成基于语料背书的清单研究，当前仅 Java / TS 清单具备足够正例依据。R2 清单注入、R3 两段式复核、R4 动态 few-shot、逐项评测门禁和终版对比尚未完成。

详细过程记录见 `.trellis/tasks/08-03-r7-eval-corpus/implement.md`、`.trellis/tasks/08-03-r7-eval-corpus/baseline-glm-2026-08-12.md` 和 `.trellis/tasks/08-03-r8-prompt-tuning/implement.md`。

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
- **可观测**：`ai_call_log` 记录每次大模型 / embedding / 模型调用的类型、模型、耗时、输入输出长度、**Token 用量**、状态和错误；并通过 Micrometer 把审查时延 / 吞吐 / Token 成本暴露到 `/actuator/prometheus`。Agent 控制面另有 `reposage.agent.runs`（created/completed/failed/recovered 计数）与 `reposage.agent.step` / `reposage.agent.tool` 时延直方图，标签仅取有界维度（状态、步骤类型、工具名），绝不用 run id / 仓库名 / 错误消息做标签以免时间序列爆炸。
- **生产级护栏**：每请求 `X-Trace-Id` 贯穿日志（MDC）；该 traceId 会随 Agent 步骤消息经 outbox → RabbitMQ 传递，消费端重新写入 MDC，使异步步骤与工具调用日志与最初的 HTTP 请求同源可关联；按用户/IP 的接口限流（超限返回 429 + `Retry-After`）；`/actuator/health` 深度探活（AI provider / 模型服务 / DB / 磁盘，细节仅鉴权可见）。
- **审查工作台**：PR 列表可直达对应 Agent Run 时间线；报告引用可定位到 diff 具体行并展开证据抽屉；Agent Run / Finding / AI 调用日志等全部长列表统一分页信封（默认 20、上限 100）。
- **带/不带知识库对比审查**：同一提交一键创建两个审查任务（关联全部已入库文档 vs 不关联），对比视图三栏呈现「仅带知识库发现 / 双方共有 / 仅不带知识库发现」，并给引用了知识文档/历史事故的条目加信号徽标。注意：mock 模式下规则引擎不读知识文档，两侧产出一致；差异对照需接真实大模型。
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

## PR 守门 Agent（事件驱动的自动审查）

除手动审查外，RepoSage 还实现了一条把「PR 事件」变成「带证据的审查结论 + 可选修复补丁」的自动流水线。整条链路以 PostgreSQL 为事实源，**模型输出一律视为不可信**，由确定性后端逐级校验后才允许影响结论。

```text
GitHub / GitLab PR Webhook
  │  HMAC-SHA256 / X-Gitlab-Token 常时验签（对原始字节）、delivery 去重幂等
  ▼
归一化 PR 事件 ──► 创建 Agent Run（状态机 + 事务 Outbox + 预算护栏 + 重启恢复）
  │                 同一 PR 出现新 head 时，旧的活跃 Run 经状态机置为 CANCELED
  ▼
Agent 步骤（RabbitMQ 异步，traceId 全程透传）
  │   只读工具 git.diff / git.file / code.search 在【签名沙箱】里取证
  │   LangChain4j 适配大模型：结构化输出 + schema/权限/路径/大小/预算/工具白名单校验 + 引用校验（防伪造证据）
  ▼
带证据的 Findings + 置信度加权 + 门禁裁决（高危 + 有效定位 + 置信度达阈值才拦截）
  │
  └─►（可选）生成补丁候选 → 沙箱内 baseline / apply / patched 三段校验
           补丁内容【必须人工审批】，head 变更（stale-head）直接拒绝
  ▼
回写 PR 评论 / Check（GitHub）· MR note / commit status（GitLab）
       评论只含 findings 与补丁状态标签，【绝不含补丁内容】
```

**安全边界（诚实声明）：**

- 模型只能调用只读工具，**没有 `scm.publish`、也没有模型可调的 `patch.apply`**；补丁应用与结果回写都由后端确定性代码执行，不由模型决定。
- 沙箱容器：`--network none`、只读根文件系统、`--cap-drop ALL`、`no-new-privileges`、非 root（65534）、CPU/内存/PID 限额、命令白名单、工作区路径围栏；镜像按 `@sha256` 摘要固定；作业以 HMAC 签名 + nonce 防重放。
- Runner 不接收任何 SCM / LLM / 数据库密钥，只拿到「已脱敏的仓库归档引用 + 签名作业」；Prompt 与工具输出均做密钥脱敏。
- 单机 Docker Compose 面向**受控演示环境**，不构成对抗恶意多租户的安全隔离边界。

**工程测试基线**（数据来源：main 分支 CI run [31310489195](https://github.com/LinYsssss/reposage/actions/runs/31310489195) 与本地容器化全量运行，2026-08-09）：

- 后端 `mvn verify`：575 项测试在 CI 全部执行并通过（含 3 项 Testcontainers 集成用例；本地容器化运行中这 3 项明确跳过，其余 572 项通过）。
- Sandbox Runner：75 项通过；前端：21 项测试通过 + 生产 Vite 构建通过；model-service：9 项通过。
- 依赖 Docker 的沙箱链路已实测：2026-08-09 在 Docker 环境把 PR 守门 Agent 全链路（webhook → 沙箱取证 → 门禁裁决）端到端跑至 COMPLETED。

**r7 真实模型评测历史基线**（隔离栈，2026-08-12，扩容前 32 例）：

- 扩容前 32 个版本化用例全部跑成，判分工具按 `d3-v1` 口径独立计算漏报率和误报率；未跑成用例数为 0。
- 模型为 `z-ai/glm-5.2`，temperature 为 `0.0`；全量漏报率为 **36.00%（9/25）**，误报率为 **81.82%（72/88）**。
- 该基线用于 r8 各轮提示词改动的可比参照；后端 `EvaluationMetrics` 的 `falsePositiveRate` 与此处误报率定义不同，不能直接混用。
- 当前语料为 38 例，安全类已补齐到 8 例（含 Java 越权、SQL 注入、CSRF 与路径穿越）；38 例新基线尚未生成，因此本节数字只代表扩容前历史基线。

Demo 与运维细节见 `docs/PR守门Agent SCM与Sandbox运维验收.md`；Agent 运行时间线可在前端「审查工作台」查看，或经下方 `/api/agent-runs/**` 接口访问。

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
| PR 守门 Agent | SCM Webhook（GitHub/GitLab）、Agent 状态机 + 事务 Outbox、LangChain4j 适配大模型、签名 Docker 沙箱（独立 `sandbox-runner` 模块） |
| 可观测 | Micrometer / Prometheus、OpenTelemetry、traceId（MDC）全链路关联 |
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
>
> **注意**：`EMBEDDING_PROVIDER` 未显式设置时会**继承 `AI_PROVIDER`**（二者皆空才是 `mock`）。只设 `AI_PROVIDER=openai-compatible` 而漏设它，embedding 会静默切到真实 API——要么产生计费调用，要么在端点无 embedding 路由时直接报错。请像上面一样显式写出。

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
| `EMBEDDING_PROVIDER` | 继承 `AI_PROVIDER`，二者皆空时 `mock` | 向量化提供方：`mock`（本地占位）/ `openai-compatible`（真实 embedding API）。接真实大模型时请显式设置，避免 embedding 静默跟随 `AI_PROVIDER` 走真实 API |
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

1. 登录（已关闭公开注册，首个管理员由 `SEED_ADMIN_*` 环境变量种子创建）。
2. 创建项目。
3. 绑定 Git 仓库（演示可用 `demo-repos/mall-order-service`）。
4. 上传知识库文档（Markdown / TXT），如 `security-policy.md`、`order-flow.md`。
5. 查询 Commit，选择要审查的提交。
6. 触发审查任务。
7. （可选）一键「对比审查」：对同一提交自动创建带/不带知识库两个任务，在对比视图查看知识库带来的 Finding 差异（mock 模式两侧一致，差异对照需真实大模型）。
8. 查看审查报告：风险等级、问题列表、证据来源、修复建议；引用可定位到 diff 行并展开证据抽屉。
9. 对问题提交人工反馈（确认 / 误报 / 备注）。
10. 在 PR 工作流中登记 Pull Request，基于 PR 触发审查，并按报告执行“通过 / 打回修改 / 风险豁免”；PR 行可直达对应的 Agent Run 时间线。
11. 查看 AI 调用日志与 MQ 日志。

**验证大模型与全量注入是否生效**：报告中的问题应能引用到你上传文档里的具体内容（例如发货问题引用 `order-flow.md` 的支付校验规则），说明完整项目上下文确实进入了 Prompt。

### 接入自动 PR 守门（Webhook）

手动流程（第 10 步）不需要任何配置。若要让 GitHub / GitLab 的 PR 事件**自动**触发守门 Agent，按下面三步接线：

1. **平台需公网可达**——webhook 是 SCM 主动回调；内网部署可用 `cloudflared` / `ngrok` 之类隧道暴露 nginx 的 80 端口。
2. **注册 SCM 安装**（管理员）——webhook 的验签密钥与项目绑定都存在 `scm_installation` 表，未注册的事件会被 `NO_INSTALLATION` 忽略：

   ```bash
   curl -X POST http://<host>/api/scm/installations \
     -H "Authorization: Bearer <管理员 token>" -H 'Content-Type: application/json' \
     -d '{
           "provider": "GITHUB",
           "externalInstallationId": "<GitHub App installation id / GitLab 项目 id>",
           "webhookSecret": "<与 SCM 侧填写的同一个密钥>",
           "projectId": <平台项目 id>,
           "credential": "<可选：GitHub App 私钥 / GitLab access token>"
         }'
   ```

   - `repositoryId` 留空会自动取该项目已绑定的仓库。
   - 该端点按**平台管理员**作用域工作（`hasRole("ADMIN")`），管理员**无需是目标项目的 owner**。`externalInstallationId` 会自动去除首尾空格，重复注册即更新。
   - **`credential` 留空 = 只产出报告、不回写 PR**；需要把结论/评论回写到 PR 时才填。
   - 密钥与凭据一律加密入库，接口只回 `secretConfigured` / `credentialConfigured` 布尔位，不回显明文。
   - 同一 `(provider, externalInstallationId)` 重复注册为更新（并重新激活）；`DELETE /api/scm/installations/{id}` 停用。

3. **在 SCM 侧配置 webhook**：
   - GitHub：Payload URL 填 `https://<host>/api/webhooks/scm/github`，Content type `application/json`，Secret 填上一步的 `webhookSecret`，事件只勾 **Pull requests**。
   - GitLab：URL 填 `https://<host>/api/webhooks/scm/gitlab`，Secret token 填 `webhookSecret`，触发器勾 **Merge request events**。

接好后开 / 更新 PR 即会自动建 Agent Run（`opened` / `reopened` / `synchronize` / `ready_for_review` 才审），在「Agent 审批」页可看时间线、门禁裁决与待审批补丁。

### 审查结论通知（钉钉）

审查完成后可推送结论摘要到钉钉群。默认关闭，开启需在 `deploy/.env` 配置：

```env
DINGTALK_ENABLED=true
DINGTALK_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=<你的 token>
DINGTALK_SECRET=<机器人「加签」密钥>        # 安全设置选「加签」时填
DINGTALK_KEYWORD=RepoSage                 # 安全设置选「自定义关键词」时改填这个
DINGTALK_MIN_RISK=LOW                     # 低于该等级不打扰:NONE < LOW < MEDIUM < HIGH
NOTIFY_BASE_URL=https://<你的域名>         # 可选,通知里附「查看完整报告」链接
```

在钉钉群「群设置 → 智能群助手 → 添加机器人 → 自定义」创建机器人，安全设置三选一（加签 / 自定义关键词 / IP 白名单），把 Webhook 地址填到上面。

- 只推**结论摘要**（项目、风险等级、问题数、摘要），**不推 diff 或证据原文**，避免代码内容外泄。
- 通知在事务提交后发送；发送失败只记日志，**不影响审查结果写入**。

### 导出报告

报告详情页可导出 **Markdown**（人读 / 传阅）或 **SARIF 2.1.0**（可上传 GitHub Code Scanning）：
`GET /api/projects/{projectId}/reviews/reports/{reportId}/export?format=markdown|sarif`

---

## API 速查

统一前缀 `/api`，除登录、CSRF 引导（`/api/auth/csrf`）与 SCM webhook 外均需请求头 `Authorization: Bearer <token>`（webhook 改用 HMAC/Token 验签）。

| 模块 | 方法与路径 |
| --- | --- |
| 认证 | `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`GET /api/auth/csrf`（SPA 的 CSRF 引导，未登录可访问；无公开注册，首个管理员由 `SEED_ADMIN_*` 种子创建） |
| 项目 | `POST /api/projects`、`GET /api/projects`、`GET/PUT/DELETE /api/projects/{projectId}` |
| 仓库 | `POST/GET/DELETE /api/projects/{projectId}/repository`、`GET .../commits`、`GET .../commits/{commitId}/diff` |
| PR 工作流 | `POST/GET/PUT /api/projects/{projectId}/pull-requests`、`POST .../pull-requests/{pullRequestId}/review-task`、`POST/GET .../pull-requests/{pullRequestId}/actions` |
| 知识库 | `POST/GET /api/projects/{projectId}/knowledge/documents`、`DELETE .../documents/{documentId}`、`POST .../knowledge/search`、`POST .../knowledge/reindex`（重建过期 embedding 的文档索引） |
| 审查 | `POST/GET /api/projects/{projectId}/reviews/tasks`、`GET .../tasks/{taskId}`、`POST .../tasks/{taskId}/cancel`（取消排队/执行中任务）、`DELETE .../tasks/{taskId}`、`GET .../reviews/reports`、`GET/DELETE .../reports/{reportId}` |
| 反馈 | `POST/GET /api/review-issues/{issueId}/feedback` |
| MQ 日志 | `GET /api/mq/logs` |
| AI 日志 | `GET /api/ai/logs` |
| Agent Run | `GET /api/agent-runs/{id}`、`GET .../{id}/timeline`、`POST .../{id}/cancel`、`POST .../{id}/retry`、`GET .../{id}/events`（SSE）、`GET /api/agent-runs/project/{projectId}` |
| 补丁审批 | `GET /api/projects/{projectId}/agent-runs/{agentRunId}/patches`、`POST .../patches/{patchId}/approval`（人工审批，仅项目 owner） |
| SCM Webhook | `POST /api/webhooks/scm/github`、`POST /api/webhooks/scm/gitlab`（HMAC/Token 验签，无需 Bearer） |
| SCM 安装管理 | `POST /api/scm/installations`、`GET /api/scm/installations`、`DELETE /api/scm/installations/{id}`（仅 ADMIN） |
| Agent Findings | `GET /api/projects/{projectId}/agent-runs/{agentRunId}/findings`（含证据链与门禁裁决） |
| 报告导出 | `GET /api/projects/{projectId}/reviews/reports/{reportId}/export?format=markdown\|sarif` |

接口字段细节见 `docs/03_接口设计文档.md`。

---

## Docker 部署

```text
cd deploy
cp .env.example .env
# 编辑 .env：填入真实 LLM_API_KEY，并改掉 DB_PASSWORD、JWT_SECRET、
# TOKEN_ENCRYPT_KEY、SANDBOX_SIGNING_SECRET 等默认值
docker compose up -d --build
```

如果是从 GitHub 新 clone 到服务器，先初始化演示仓库（让后端能 clone & diff）：

```text
bash scripts/init-demo-repos.sh --verify
```

该脚本重建 `demo-repos/` 下的三个演示仓库（`mall-order-service`、`payment-settlement-service`、
`tenant-user-center`）及各自的 PR 分支；`--verify` 会把 6 个 ref 的 SHA 与
`scripts/demo-repos-expected-sha.txt` 逐条比对。PowerShell 用
`pwsh -File scripts/init-demo-repos.ps1 -Verify`。素材说明见 `demo-repos/README.md`。

启动的服务：PostgreSQL + pgvector、RabbitMQ、Spring Boot 后端、Sandbox Runner、FastAPI 模型服务、Vue 前端、Nginx。
对外入口：前端 `http://服务器IP/`，健康检查 `http://服务器IP/actuator/health`，RabbitMQ 管理台 `http://服务器IP:15672`。

Sandbox Runner 没有对外 HTTP 端口，只通过专用 RabbitMQ 队列接收签名任务。单机 Docker Compose
方案面向受控演示环境，不构成恶意多租户安全边界；Docker Socket 仅挂载给受信任 Runner，Runner
启动的仓库分析容器不得继承该挂载，也不得接收 SCM、LLM 或数据库密钥。

生产环境由 Flyway 按版本执行 `backend/src/main/resources/db/migration/` 中的迁移，并以 `ddl-auto=validate` 校验实体结构。`deploy/init.sql` 只负责启用 pgvector 扩展，不再维护业务表结构。详见 `docs/12_服务器部署与演示手册.md`。

---

## 构建与测试

### 工程基线

| 组件 | 支持基线 |
| --- | --- |
| Java | 17 |
| Maven | 3.9+ |
| Node.js | 22 LTS（`frontend/package.json` engines 要求 `>=22 <23`，与 Dockerfile、CI 一致） |
| PostgreSQL | 16 + pgvector |
| RabbitMQ | 3.13 |
| Docker Compose | v2 |

需要执行 PostgreSQL、RabbitMQ 集成测试或完整生产联调时，必须安装 Docker Desktop/Engine，并确保 `docker compose version` 可用。未安装 Docker 时，Testcontainers 用例会明确跳过，不能视为生产基础设施验证通过。

数据库结构统一由 Flyway 管理。任何结构调整都必须新增不可变的 `V<N>__description.sql`；已经发布或被共享环境执行过的迁移禁止修改，只能通过更高版本迁移向前修正。

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

一键本地验收（后端测试 + 前端测试与构建 + 模型服务检查 + 后端冒烟 + Docker 可用性检查）：

```text
.\scripts\verify-local.ps1
```

只执行可重复的构建与测试、不启动后端冒烟服务：

```text
.\scripts\verify-local.ps1 -SkipSmoke
```

首次运行时，脚本会根据 `model-service/requirements.txt` 将 Python 依赖安装到被 Git 忽略的本地目录。

后端冒烟（启动后端后执行，跑通登录→建项目→绑仓库→传知识库→审查→报告→反馈全链路）：

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

**对比审查里两侧结果为什么一样？**
mock 模式（默认，未配置 `AI_PROVIDER=openai-compatible`）的规则引擎不读知识文档，带/不带知识库产出相同——这是设计内行为，对比视图此时验证的是流程与展示。接入真实大模型后，两侧差异与文档引用才有对照意义（对照 `docs/演示素材与缺陷对照表.md` 第五节）。

**质量门（langchain4j shadow 对比）有详情页吗？**
未实施。langchain4j 运行时的 shadow / legacy 对比数据目前仅落在 `ai_call_log` 与 Prometheus 指标中，无独立展示页；评测数据在本机不可得，该页面明确降级为「未实施」而非隐藏承诺。

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
