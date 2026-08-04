# RepoSage 现实核对审计报告

- **审计基线**：`a04b518`（main，2026-08-04）
- **审计日期**：2026-08-04
- **执行环境**：Ubuntu 22.04 / Docker 29.6 可用 / Node v24.18.0（npm 12.0.1）/ 本机无 Java·Maven（经容器执行）
- **重要偏离**：`design.md` 假设"本机大概率无 Docker"，实际 **Docker 完全可用**，因此容器类声称做了真实验证，未使用"无法验证"豁免。

---

## 一、执行摘要

实测复核 + 口径比对 + 代码扫描共产出 **14 条发现**：P0 三条、P1 四条、P2 五条、P3 两条。

**最需要注意的三点：**

1. **CI 已连续红 12 天、5 次构建**，两个作业各有独立根因，其中供应链安全门禁**自建立以来一次都没有执行过**。
2. **沙箱工作区链路是断的**：后端生成的归档引用格式会被 Runner 的安全校验无条件拒绝，且归档文件本身无人写入。这条链路在真实 PR 守门流程中必然失败。
3. **README 的测试基线三项全部严重过时**，但方向是**低报**（实际远多于声称）。口径风险低，可信度风险高——数字与现实不符本身会削弱"写实"定位。

**口径整体评价：良好。** README 已有的"诚实声明"经核对**仍然准确**，沙箱安全边界 8 项声称**逐条属实**，配置项表 7/8 属实。失实项集中在**陈旧**（基线数字、Node 版本），而非**夸大**。

---

## 二、Layer 1：实测复核 README 声称基线

| 项目 | README 声称 | 实测结果 | 结论 |
| --- | --- | --- | --- |
| 后端 `mvn test` | 190 通过 + 3 跳过 | **531 运行 / 528 通过 / 0 失败 / 3 跳过**，BUILD SUCCESS | 陈旧（低报 338） |
| sandbox-runner | 37 通过 | **43 通过**，BUILD SUCCESS | 陈旧（低报 6） |
| 前端测试 + 构建 | 4 项测试 + Vite 构建通过 | **21 项测试通过 + 构建通过（29.10s，202.52 kB）** | 陈旧（低报 17） |
| model-service | *基线未列入* | **9 通过**（17.60s） | 基线缺项 |
| 演示仓库 SHA | 6 个 ref 一致 | **6/6 一致** | ✅ 属实 |
| 评测语料 | 6 个版本化用例 | **6 个**（manifest 与 cases/ 均为 6） | ✅ 属实 |
| Docker 可用性 | 依赖 Docker 项"未验证" | **Docker 可用**，容器类声称已真验 | 声明可更新 |

**执行命令（可复现）：**

```bash
# 后端（本机无 Java，走容器）
docker run --rm -v /root/reposage:/ws -v reposage-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /ws/backend maven:3.9-eclipse-temurin-17 mvn -s .mvn/settings.xml test

# sandbox-runner
docker run --rm -v /root/reposage:/ws -v reposage-m2:/root/.m2 \
  -w /ws/sandbox-runner maven:3.9-eclipse-temurin-17 mvn -B verify

# 前端（须用 Node 22；见 F-07）
docker run --rm -v /root/reposage/frontend:/ws -w /ws node:22-alpine \
  sh -c "npm ci && npm test && npm run build"

# model-service
docker run --rm -v /root/reposage/model-service:/ws -w /ws python:3.12-slim \
  sh -c "pip install -q -r requirements.txt pytest && python -m pytest tests/ -q"

# 演示仓库
bash scripts/verify-demo-repos.sh
```

> 注：后端 3 项跳过的用例即 README 所述 Testcontainers 用例（`GitHubWebhookAgentRunIntegrationTest`、`LegacySchemaMigrationIntegrationTest`、`InfrastructureIntegrationTest`）。即便挂载了 Docker socket 仍跳过，说明跳过条件不只探测 Docker 可用性——但**声称本身属实**。

---

## 三、Layer 2：README 声称逐条比对

### 3.1 沙箱安全边界（README:82-87）—— 全部属实 ✅

| 声称 | 实现锚点 | 结论 |
| --- | --- | --- |
| `--network none` | `ContainerPolicy.java:40` | ✅ 属实 |
| 只读根文件系统 | `ContainerPolicy.java:41` | ✅ 属实 |
| `--cap-drop ALL` | `ContainerPolicy.java:42` | ✅ 属实 |
| `no-new-privileges` | `ContainerPolicy.java:43` | ✅ 属实 |
| 非 root（65534） | `ContainerPolicy.java:44` | ✅ 属实 |
| CPU / 内存 / PID 限额 | `ContainerPolicy.java:45-47` | ✅ 属实 |
| 命令白名单 | `RepositoryReadCommandHandler.java:11`、`*CommandCatalog` | ✅ 属实 |
| 工作区路径围栏 | `WorkspaceArchiveResolver.java:32-38`、`ContainerPolicy.java:73` | ✅ 属实 |
| 镜像 `@sha256` 摘要固定 | `EvaluationCorpusService.java:26` 强校验 | ⚠️ 机制属实，**部署未落实**（见 F-05） |
| 无 `scm.publish`、无模型可调 `patch.apply` | 工具目录中确无该工具 | ✅ 属实 |
| HMAC 签名 + nonce 防重放 | `SandboxJobSigner`、`SandboxReplayGuard` | ✅ 属实 |
| 单机 Compose 非多租户隔离边界 | `docker-compose.yml:73-75` 注释一致 | ✅ 属实 |

### 3.2 配置项表（README:191-210）—— 7/8 属实

| 变量 | README 声称默认 | `application.yml` 实际 | 结论 |
| --- | --- | --- | --- |
| `REVIEW_INLINE` | `true` | `${REVIEW_INLINE:true}` | ✅ |
| `MODEL_SERVICE_ENABLED` | `false` | `${MODEL_SERVICE_ENABLED:false}` | ✅ |
| `RATE_LIMIT_ENABLED` | `true` | `${RATE_LIMIT_ENABLED:true}` | ✅ |
| `RATE_LIMIT_REQUESTS` | `120` | `${RATE_LIMIT_REQUESTS:120}` | ✅ |
| `REVIEW_MAX_DIFF_CHARS` | `20000` | `${REVIEW_MAX_DIFF_CHARS:20000}` | ✅ |
| `REVIEW_MAX_FILES` | `40` | `${REVIEW_MAX_FILES:40}` | ✅ |
| `RAG_FULL_CONTEXT` / `RAG_MODE` / `RAG_TOP_K` | `false` / `memory` / `5` | 一致 | ✅ |
| `EMBEDDING_PROVIDER` | `mock` | `${EMBEDDING_PROVIDER:${AI_PROVIDER:mock}}` | ⚠️ **部分属实**（见 F-08） |

### 3.3 核心特性清单（README:20-33）—— 均可定位到实现 ✅

| 声称 | 实现锚点 |
| --- | --- |
| 真实大模型审查 | `OpenAiCompatibleReviewClient.java` |
| 全量上下文注入 | `RagService.java:buildFullContext` |
| 大 Diff 分片审查 | `ReviewProcessor.java`（`maxDiffChars` 打包） |
| 可选向量检索 | `RagService.java:108`（pgvector 分支） |
| 规则引擎兜底 / 轻量分类模型 | 规则类 + `model-service/` |
| 异步任务（重试 + 死信） | RabbitMQ 配置 + `AgentOutbox*` |
| 调用弹性（Resilience4j） | `OpenAiCompatibleReviewClient.java`（`CircuitBreaker`） |
| AES-GCM 加密存储 | `CryptoService.java` |
| 限流 429 + `Retry-After` | `RateLimitFilter.java` |
| 分页信封 默认 20 / 上限 100 | `PageResponse.java:16-17`，`Math.min(requested, MAX_SIZE)` |
| SARIF 导出 | `ReviewReportExporter.java` |
| 钉钉通知 | `DingTalkNotifier.java` |
| Webhook HMAC 验签 | `WebhookSignatures.java` |

### 3.4 API 速查表（README:305-321）—— 所列条目全部属实，但不完整

逐行比对 README 表格与 `*Controller.java` 的实际 `@RequestMapping` / `@*Mapping` 注解，**表中列出的每一条路径与方法均存在且一致**，包括最易漂移的 SSE 端点：

| README 声称 | 实现锚点 | 结论 |
| --- | --- | --- |
| `GET /api/agent-runs/{id}/events`（SSE） | `AgentRunController.java:69` `@GetMapping(value="/{id}/events", produces=TEXT_EVENT_STREAM_VALUE)` | ✅ 属实 |
| Agent Run 其余 5 条 | `AgentRunController.java:39,44,54,59,64` | ✅ 属实 |
| 认证 / 项目 / 仓库 / PR / 知识库 / 审查 / 反馈 / MQ 日志 / AI 日志 / 补丁审批 / Webhook / SCM 安装 / Findings / 报告导出 | 各对应 Controller | ✅ 属实 |

反向核对（实际有、README 未列）见 F-14。

### 3.5 已有"诚实声明"复核 —— 仍然准确 ✅

README 中"mock 模式下两侧产出一致"、"单机 Compose 非多租户边界"、"缺 Docker 时容器安全属未验证"三条声明经核对**依然与代码一致**，未发现需要撤回或加强的情形。唯一可更新的是第三条：本机 Docker 实际可用，容器类声称已可真验。

---

## 四、发现清单

### P0 —— 阻断演示 / 安全门禁失效

---

#### F-01 · 缺陷 · 供应链安全门禁自建立起从未执行过一次

**证据**

```yaml
# .github/workflows/ci.yml:81
uses: aquasecurity/trivy-action@0.28.0
```

该 action 的发布 tag **全部带 `v` 前缀**（`v0.26.0` … `v0.36.0`），`0.28.0` 不存在。CI 日志逐字印证：

```
##[error]Unable to resolve action `aquasecurity/trivy-action@0.28.0`, unable to find version `0.28.0`
```

作业 `supply-chain` 从 `03:22:18` 到 `03:22:20`——**2 秒死在 "Set up job"**，连 checkout 都未执行。

**影响**：`ci.yml:72` 注释声明该门禁"把 Maven、Python 与四个业务镜像补齐"，实际 Maven / Python 依赖扫描与四个镜像的 HIGH/CRITICAL 扫描**全程空白**。这是一个"看起来存在、实际从未运行"的安全门禁。

**建议修法**：`@0.28.0` → `@v0.28.0`。工作量：**小**（一字符）。修复后应确认扫描真的产出结果，而非再次静默通过。

---

#### F-02 · 缺陷 · 单元测试向文件系统根目录写入，导致 CI 连红 12 天

**证据**

```java
// sandbox-runner/.../SandboxRunnerApplication.java:60
@Value("${app.sandbox.archive-root:/app/archives}") String archiveRoot

// sandbox-runner/.../WorkspaceArchiveResolver.java:14（构造函数）
Files.createDirectories(archiveRoot);
```

`SandboxRunnerApplicationTest` 未覆盖 `app.sandbox.archive-root`，测试期间该 Bean 试图在真实文件系统创建 `/app/archives`。CI 日志：

```
Factory method 'workspaceArchiveResolver' threw exception with message: /app
Caused by: java.nio.file.AccessDeniedException: /app
```

**为什么本地绿、CI 红**：本机容器内以 root 运行 → 建目录成功；GitHub runner 以非 root `runner` 运行 → `AccessDeniedException`。实测本机 43 项全过，CI 同一命令失败。

**影响**：`main` 自 `7ff9265`（2026-07-23）起连续 5 次构建全红，跨度 12 天。且因 `verify` 作业在此步骤中断，其后的前端测试、前端构建、model-service 测试**全部 skipped，从未在 CI 中执行过**。

**建议修法**：测试类加 `@TestPropertySource(properties = "app.sandbox.archive-root=${java.io.tmpdir}/...")`，或改用 `@TempDir`。工作量：**小**。

---

#### F-03 · 缺陷 · 跨服务契约冲突：归档引用格式被 Runner 无条件拒绝

**证据**

```java
// 后端产出（RepositoryArchiveRefResolver.java）
return "workspace://agent-run-" + agentRunId + "-" + headSha.toLowerCase() + ".tar";

// Runner 校验（WorkspaceArchiveResolver.java:26-28）
if (reference.contains(":") || reference.contains("\\")) {
    throw new SecurityException("workspace archive reference scheme is not allowed");
}
```

`workspace://…` 含 `:`，**必然命中拒绝分支**。

**完整链路**（两侧 `SandboxJob` record 已逐字段比对，`workspaceArchiveRef` 均为第 2 位，映射无误）：

```
RepositoryArchiveRefResolver → RabbitSandboxToolGateway.java:56 → MQ
  → RepositoryCommandExecutor.java:44  archives.resolve(job.workspaceArchiveRef())
  → WorkspaceArchiveResolver.java:26   throw SecurityException
```

后端侧 `InputValidation.requireArchive`（`InputValidation.java:8-13`）只校验 null / 长度 / `\` / `..` / 前导 `-`，**不检查 scheme**，因此放行。

**为什么测试测不到**：Runner 的 14 个测试用例全部使用裸文件名（`RepositoryCommandExecutorTest.java:22` 用 `"repo.zip"`），从未构造过后端真实产出的 `workspace://` 形式。

**影响**：PR 守门 Agent 的沙箱取证步骤在真实链路中必然失败。

**建议修法**：择一并补契约测试——(a) Runner 识别并剥离 `workspace://` scheme；(b) 后端改为产出裸文件名；(c) 双方共用一个引用编解码器。**必须新增一个用后端真实产出格式驱动 Runner 的契约测试**，否则同类漂移会再次发生。工作量：**中**。

---

### P1 —— 实际缺陷 / 部署阻断

---

#### F-04 · 缺陷 · 归档卷只读挂载且无任何写入方

**证据**

```yaml
# deploy/docker-compose.yml:108
- sandbox_archives:/app/archives:ro
```

`sandbox_archives` 仅被 sandbox-runner 以 `:ro` 挂载，**backend 完全未挂载该卷**，仓库内亦无向该卷写入的代码路径。

**影响**：即使 F-03 的 scheme 冲突修复，`WorkspaceArchiveResolver.java:36` 的 `Files.isRegularFile(real)` 仍会失败——归档文件根本不存在。F-03 与 F-04 是同一条断链的两截，**必须一并修复**。

**建议修法**：明确归档的生产者与移交方式（backend 写入共享卷并去掉 `:ro`，或改为对象存储引用）。工作量：**中**。

---

#### F-05 · 缺陷 · `SANDBOX_TOOL_IMAGE` 未配置，沙箱工具调用软失败

**证据**

```yaml
# backend/src/main/resources/config/app-boundary.yml:55
tool-image: ${SANDBOX_TOOL_IMAGE:}          # 默认空串
```

`deploy/.env` 中**无此项**（`deploy/.env.example` 有）。运行时后果：

```java
// RabbitSandboxToolGateway.java:51-53
if (... || imageDigest == null || imageDigest.isBlank()) {
    return ToolResult.failure("ENVIRONMENT_INCOMPLETE: sandbox signing secret or image is not configured");
}
```

**影响**：软失败——返回 failure 而非抛异常，`/actuator/health` 照常为 UP，因此该缺口在健康检查中完全不可见。同时使 README:85"镜像按 `@sha256` 摘要固定"在当前部署中无从体现。

**建议修法**：`.env` 补 `SANDBOX_TOOL_IMAGE=<镜像>@sha256:<digest>`（`EvaluationCorpusService.java:26` 强制 digest 固定格式）；并考虑将其纳入 `ProdSecretValidator` 的启动期校验，让缺失变成快速失败而非静默降级。工作量：**小**。

---

#### F-06 · 缺陷 · 前端 lockfile 94% 的包指向第三方镜像源

**证据**：`frontend/package-lock.json`（lockfileVersion 3）中 resolved 主机分布——

```
  80  registry.npmmirror.com
   5  registry.npmjs.org
```

**实测**：本机 npm 12.0.1 直接拒绝安装（无任何 `.npmrc`，registry 为官方默认）：

```
npm error code EALLOWREMOTE
npm error Fetching packages of type "remote" have been disabled
npm error Refusing to fetch "vue@https://registry.npmmirror.com/vue/-/vue-3.5.35.tgz"
```

加 `--omit-lockfile-registry-resolved` 亦无效。改用 Node 22 容器（npm 10，与 CI 一致）则安装成功、21 项测试通过、构建通过。

**影响**：(a) **供应链**——94% 依赖的完整性锚定在第三方镜像而非官方源；(b) **前向兼容**——npm ≥ 12 环境无法 `npm ci`，本机当前 Node v24 即属此列。CI 用 Node 22（npm 10）暂不受影响。

**建议修法**：在官方源下重新生成 lockfile（`rm package-lock.json && npm install --registry=https://registry.npmjs.org/`），并在 CI 加一道 lockfile 源一致性检查。工作量：**小**。

---

#### F-07 · 口径失实 · README 测试基线三项全部严重过时

**证据**：README:91-92 声称"后端 190 项通过，3 项跳过 / Sandbox Runner 37 项 / 前端 4 项"，实测为 **528 / 43 / 21**（详见第二节）。model-service 的 9 项测试在基线中完全未列。

**影响**：方向是**低报**而非夸大，口径风险低。但对一个以"写实不造假"为原则的简历项目，基线数字与现实相差 338 项会直接削弱可信度——评审者若照着跑一遍，第一反应是"文档没维护"。

**建议修法**：更新为实测数字，并注明测量日期与命令；或改为引用 CI 产物，避免手工数字再次腐化。工作量：**小**。

---

### P2 —— 契约漂移 / 文档陈旧 / 工程一致性

---

#### F-08 · 口径失实 · `EMBEDDING_PROVIDER` 默认值文档与实现不符

**证据**：README:201 声称默认 `mock`，实际为 `${EMBEDDING_PROVIDER:${AI_PROVIDER:mock}}`（`app-agent.yml:97`）——默认**继承 `AI_PROVIDER`**，仅在两者都未设时才是 `mock`。

**影响**：用户照 README"接入真实大模型"一节设 `AI_PROVIDER=openai-compatible` 而未显式设 `EMBEDDING_PROVIDER` 时，embedding 会**静默切到真实 API**，产生非预期调用费用；若该端点无 embedding 路由则直接报错。这是一个有实际代价的文档陷阱。

**建议修法**：README 默认值列改为"继承 `AI_PROVIDER`，二者皆空时为 `mock`"。工作量：**小**。

---

#### F-09 · 口径失实 · README 工程基线 Node 版本与实际三处不符

**证据**

| 位置 | 版本 |
| --- | --- |
| README:367 工程基线表 | **Node.js 20 LTS** |
| `frontend/package.json` engines | `>=22 <23` |
| `frontend/Dockerfile:3` | `node:22-alpine` |
| `.github/workflows/ci.yml:27` | `node-version: "22"` |

三处实际实现一致为 22，仅 README 停留在 20。

**建议修法**：README 改为 22。工作量：**小**。

---

#### F-10 · 缺陷 · 演示仓库校验脚本使用裸 `python`，在现代系统上误报失败

**证据**

```bash
# scripts/verify-demo-repos.sh:61
if python -c "$PY_SYNTAX_CHECK" "$DEMO/tenant-user-center/src" ...
```

同仓库 `scripts/verify-local.sh:37-38` 使用的是 `python3`。实测输出：

```
FAIL: tenant-user-center: python syntax
scripts/verify-demo-repos.sh: line 61: python: command not found
```

**影响**：Ubuntu 22.04+ 等默认不提供 `python` 别名的系统上，该检查恒定误报 FAIL——SHA 校验 6/6 全过，却因环境原因让整份校验看起来是失败的。

**建议修法**：改为 `python3`，与 `verify-local.sh` 统一；或加 `command -v` 探测后跳过并明确标注"未验证"。工作量：**小**。

---

#### F-11 · 改进 · CI 中两个 Maven 模块调用方式不对称

**证据**

```yaml
# .github/workflows/ci.yml:33
run: mvn -s .mvn/settings.xml verify     # backend：指定 settings，无 -B
# .github/workflows/ci.yml:39
run: mvn -B verify                        # sandbox-runner：无 settings，有 -B
```

`sandbox-runner/` 下无 `.mvn/` 目录，而 `backend/.mvn/settings.xml` 的注释明确说明其用途是"覆盖宿主机全局镜像、强制 HTTPS Central"。两个模块因此走不同的仓库解析路径。

**影响**：本次并非 CI 失败的直接原因（真因是 F-02），但构成环境不一致隐患——一旦宿主机存在全局 Maven 镜像，两模块行为将出现分歧。

**建议修法**：统一为 `mvn -B -s <settings> verify`，settings 提到仓库根共用。工作量：**小**。

---

#### F-12 · 配置 · 部署环境的 RAG 检索被配置完全绕过

**证据**：`deploy/.env` 中 `RAG_FULL_CONTEXT=true`（yml 默认为 `false`，此处为显式覆盖）。`RagService.buildContext()` 在该开关下直接走 `buildFullContext()`，**向量检索代码路径从不执行**；叠加 `RAG_MODE=memory`，而 `RagService.java:108` 的 pgvector 分支要求 `mode=pgvector`。

数据库侧**已就绪**：`vector v0.8.5` 扩展已安装。

**说明**：这**不算失实**——README:220-223 明确推荐全量注入为默认选择，声明与配置一致。仅作为"当前部署实际能力边界"记录：向量检索与 pgvector 路径在演示环境中未被行使，若要展示该能力需切换配置。

**建议修法**：无需修复。若需演示向量检索，见第六节配置清单。工作量：**小**（仅配置）。

---

### P3 —— 流程清理

---

#### F-13 · 流程 · Draft PR #3 已被 main 取代，应关闭

**证据**：PR #3「Fix prod-profile integration tests broken by secret-length validation」，open + draft，`mergeable_state: dirty`，`diverged`，落后 main **111** 个提交、领先 2 个。

其欲修复的问题（`ProdSecretValidator` 要求 ≥16 字符导致 prod profile 集成测试无法启动）**main 上已修复且实现更好**：

```java
// main 现状（有说明注释，命名点明用途，28 字符）
private static final String TEST_DB_PASSWORD = "it-only-db-password-not-prod";
// PR #3（18 字符，无注释）
private static final String POSTGRES_PASSWORD = "test-password-1234";
```

**建议**：直接关闭，**无需重新基于 main 实现**——无可抢救内容。工作量：**小**。

---

#### F-14 · 改进 · API 速查表遗漏 6 个已实现接口

**证据**：README:305-321 的表格中所列条目全部属实（见 3.4），但以下 6 个实际存在的接口未被收录——

| 方法与路径 | 实现锚点 |
| --- | --- |
| `GET /api/auth/csrf` | `AuthController` |
| `POST /api/auth/logout` | `AuthController` |
| `POST /api/projects/{projectId}/knowledge/reindex` | `KnowledgeController` |
| `DELETE /api/projects/{projectId}/reviews/tasks/{taskId}` | `ReviewController` |
| `POST /api/projects/{projectId}/reviews/tasks/{taskId}/cancel` | `ReviewController` |
| `DELETE /api/projects/{projectId}/reviews/reports/{reportId}` | `ReviewController` |

**说明**：这是**不完整**而非**失实**——README 没有做出任何虚假声称，只是漏列。但 `/api/auth/csrf` 与 `/api/auth/logout` 属于前端接入必需的接口（`be59ed8` 提交专门处理过 SPA CSRF cookie 保活），漏列会影响他人接入。

**建议修法**：补入表格。工作量：**小**。

---

## 五、修复与改进路线图

| 序 | 编号 | 类别 | 事项 | 工作量 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| 1 | F-01 | 缺陷 | trivy-action tag 补 `v` 前缀，让供应链门禁真正运行 | 小 | — |
| 2 | F-02 | 缺陷 | `SandboxRunnerApplicationTest` 隔离归档路径，恢复 CI 绿 | 小 | — |
| 3 | F-13 | 流程 | 关闭 Draft PR #3 | 小 | — |
| 4 | F-03 + F-04 | 缺陷 | 修复归档引用契约 + 明确归档生产者，补契约测试 | 中 | 二者须同批 |
| 5 | F-05 | 缺陷 | 补 `SANDBOX_TOOL_IMAGE`，并纳入启动期校验 | 小 | — |
| 6 | F-06 | 缺陷 | 官方源重建 lockfile + CI 加源一致性检查 | 小 | — |
| 7 | F-07 | 口径 | 更新 README 三项测试基线为实测数字 | 小 | 建议在 1、2 完成后取 CI 数字 |
| 8 | F-08 | 口径 | 修正 `EMBEDDING_PROVIDER` 默认值描述 | 小 | — |
| 9 | F-09 | 口径 | README 工程基线 Node 20 → 22 | 小 | — |
| 10 | F-10 | 缺陷 | `verify-demo-repos.sh` 的 `python` → `python3` | 小 | — |
| 11 | F-11 | 改进 | 统一两模块的 Maven 调用与 settings | 小 | — |
| 12 | F-14 | 改进 | API 速查表补入 6 个遗漏接口 | 小 | — |

**建议的批次划分：**

- **第一批（解除阻塞）**：序 1、2、3。三项都是小改动，完成后 CI 首次转绿，供应链门禁首次运行。**注意**：F-02 修好后，`verify` 作业才会首次真正执行前端与 model-service 步骤——这些步骤从未在 CI 中跑过，可能暴露新问题，需预留观察窗口。
- **第二批（修复断链）**：序 4、5。这是 PR 守门 Agent 端到端可用的前提，也是本次审计技术含量最高的一项。
- **第三批（口径与工程一致性）**：序 6–12。均为小改动，可一次性合并。

---

## 六、附录：若要展示完整 AI / RAG 能力所需的配置

当前部署栈 9/9 健康，但 AI 与检索均处于降级模式。开全需要：

```bash
AI_RUNTIME=langchain4j
EMBEDDING_PROVIDER=openai-compatible
LLM_EMBEDDING_MODEL=<真实 embedding 模型名>
EMBEDDING_DIMENSIONS=<该模型维度>
RAG_FULL_CONTEXT=false
RAG_MODE=pgvector
SANDBOX_TOOL_IMAGE=<镜像>@sha256:<digest>
```

⚠️ **`AI_RUNTIME=langchain4j` 是全有或全无的开关**。`LangChain4jRuntimeValidator.java:55` 在非 langchain4j 运行时直接 return，一旦切换则转为严格校验：base-url / api-key / chat-model / embedding-base-url / embedding-api-key / embedding-model 缺一不可，且 chat-model 不得为 `mock-reviewer`、embedding-model 不得为 `mock-embedding`，否则**后端启动直接失败**。不能只翻一半。

`EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` 在 yml 中有 fallback（继承 `LLM_*`），但前提是该 MiMo 端点确实提供 embedding 接口——此项**无法从代码验证**，需实际调用一次确认。

---

## 七、审计边界声明

- **未修改任何产品代码、文档或配置**，唯一写入点为本任务目录。
- 实测产生的 `target/`、`node_modules/`、`dist/` 均在 gitignore 内。
- 演示素材中的**故意预埋缺陷**（`docs/演示素材与缺陷对照表.md` 登记的 T13 `OPS_API_KEY` 硬编码等）已识别并**排除**在发现清单之外。
- `AgentPromptAssemblerTest.java:96` 中的 `ghp_abcd…` 为验证 prompt 打码逻辑的编造测试数据，非真实凭据，已排除。
- 本次审计**未覆盖**：前端组件级实现细节、demo-repos 内部代码质量（仅做 SHA 一致性校验）、生产环境联调。
