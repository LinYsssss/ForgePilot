# Frozen Contracts(跨任务常驻约束)

> Phase 0 为并行双线冻结的契约(原始决议:`docs/archive/并行实施拆分方案.md`),合流后仍然常驻:
> 它们是前后端、backend↔sandbox-runner 之间的既成事实。
> **默认不可改**;确需变更时必须在任务 PRD 里显式列出、两侧同批改、契约测试同步(见 `.trellis/spec/guides/contract-testing.md`)。

---

## 1. ErrorCode(`common/api/ErrorCode.java`)

- 枚举常量名即响应体 `errorCode` 字符串,客户端按它分支 → **改名/删除 = 破坏契约**。
- `legacyCode` 数字被存量前端按 `code !== 0` 读取,不可改值;`fromLegacy` 的回退映射(6001–6006、500–599 段)不可动。
- 允许:**新增**条目(按 Track 分组追加,给相邻工作流打个招呼即可——类头 Javadoc 即此约定)。

## 2. PageResponse(`common/api/PageResponse.java`)

- 形状钉死:`{items, page, size, totalElements, totalPages}`;`DEFAULT_SIZE=20`、`MAX_SIZE=100`,分页参数一律过 `sanitizeSize/sanitizePage`。
- 消费方锚点:前端 `frontend/src/api/page.js` 的 `unwrapPage` 同时兼容裸数组与该信封——新增列表端点直接返回 `PageResponse.from(page)`,不得发明第三种形状。

## 3. ApiResponse 信封(`common/api/ApiResponse.java`)

- `{code, errorCode, message, traceId, data}` 五字段;数字 `code` 是存量前端的分支依据,在有消费者期间不得移除。
- 绕开信封的端点(报告/语料导出等直接返回正文的)必须**显式定字符集**:按 UTF-8 取字节再把 charset 写进 `Content-Type`(`ReviewController` 报告导出、`ReviewFeedbackController.export` 同口径)。`MediaType.APPLICATION_NDJSON` / `text/markdown` 这类常量本身不带 charset,直接返回 `String` 会落到 `StringHttpMessageConverter` 的兜底编码,中文正文整片变成 `?` ——且响应仍是 200,只有断言到非 ASCII 正文的用例才照得出来。导出类端点的测试必须至少断言一处中文,否则这条缺陷不可见。

## 4. ProjectAuthorization(`common/security/ProjectAuthorization.java`)

- 方法面:`requireRead(projectId, userId)` / `requireWrite(projectId, userId)` 签名固定;
  P1a(2026-08-16)按扩展式演进加入 `requireRole(projectId, userId, Set<ProjectRole>)` 与 `roleOf`。
- 语义(P1a 起):`requireRead` = 任意项目成员(`project_member`,owner 恒兜底为 LEADER);
  `requireWrite` = LEADER(最高权限动作);细粒度动作走 `requireRole`。
  `ProjectService.getRequired` 收敛为**读语义**实体版;写路径必须显式调用 requireWrite/requireRole。
- 口径固定:项目不存在 → 404 `PROJECT_NOT_FOUND`,非成员 → 403 `PROJECT_FORBIDDEN`(防枚举)。
- **没有管理员旁路**;若将来需要,只能加在这个类里、带显式角色检查与负向测试(类头 Javadoc 明文)。
- 配套准入规则:新的带 id 端点必须进 `ObjectLevelAuthorizationMatrixTest`(见 [quality-guidelines.md](./quality-guidelines.md))。

## 5. Flyway 已执行迁移不可变

- 见 [database-guidelines.md](./database-guidelines.md):历史迁移零改动、新迁移接实测最大版本号(当前 V28)之后、V22–V25 预留不可占用。

## 6. REST 路径与字段名

- `/api/**` 的路径与 JSON 字段名是 SPA 契约(前端 `views/` 与 `composables/` 直接解构字段)。重命名 = 破坏性变更,必须与前端同批改。
- 兼容先例:合流期新旧形状并存靠**消费端适配器**(`unwrapPage` 双形状)与**路由层重定向**(旧 `#agent-evidence=` 外链由 `frontend/src/router.js` 转 `/agent?evidence=`),不靠后端同时维护两套端点。

## 7. MQ 载荷格式

- 队列名常量在 `config/RabbitMqConfig.java`(`agent.step.queue`、`sandbox.job.queue`、`code.review.task.queue` 等),不改名。
- `agent/queue/AgentStepMessage`:`{agentRunId, sequenceNo, attempt, traceId}`,JSON 序列化直接入队;消费端按同 record 反序列化。
- backend↔sandbox-runner:`SignedSandboxJob`/`SandboxJob` 是**两侧同构镜像**;HMAC 的规范化 JSON 由 `SandboxJobSigner.canonicalJson` **手工枚举字段**(键字典序、不走 JSON 库),字段增删必须两侧同步改 canonical 形式,否则签名/完整性静默漂移;record 布局由两侧 `SandboxJobFieldOrderTest` 快照钉死(`workspaceArchiveRef` 钉在第 2 位),任何布局改动先在测试里炸;签名兼容由两侧 `SandboxJobSignerTest` 的同一金标向量证明。改字段 = 两侧同批 + 金标测试先行。
- `WorkspaceArchiveReference` 线格式(`agent-run-{id}-{sha}.tar` / `patch-{id}-{sha}.tar`,裸文件名、无 scheme)由两侧同名测试用同一批字面量钉死;历史断链格式 `workspace://…` 在拒绝集里永久留存。背景与决策:`docs/adr/0001-工作区归档引用契约的单一事实源.md`。

## 8. backend↔model-service REST 契约(已失效)

- **2026-08-16 P0 架构清理后失效**:model-service 下线,`model/HttpModelRiskClient.java` 已删除,本条不再约束新代码。条目保留仅作历史记录。

## 9. Agent PR Finding 闭环与 Run Gate(P5,2026-08-17)

### 1. Scope / Trigger

- 触发:新增项目级 Finding 生命周期 API、`agent_finding` 生命周期列、`agent_run.gate_verdict` 与 `/quality` 前端消费。
- 仅 `AgentScmContext` 存在的 PR Agent run 进入闭环;交互式/临时审查仍是报告制。
- **身份真源警告**:`WebhookAgentRunService` 创建的 `AgentRun.pullRequestId` 当前为 `null`;PR 身份位于 `AgentScmContext(installationId,pullRequestNumber,agentRunId)`。不得用 `pullRequestId != null` 判断是否为 Agent PR,否则列表与 mutation 会把全部 webhook PR Finding 错报 404。

### 2. Signatures

- `GET /api/projects/{projectId}/findings?lifecycle=&page=&size=`
- `POST /api/projects/{projectId}/findings/{findingId}/lifecycle`
  - body:`{"action":"CONFIRMED|REJECTED|IN_PROGRESS|FIXED|VERIFIED|CLOSED","fixCommitSha":"..."?}`
- `POST /api/projects/{projectId}/findings/{findingId}/assign`
  - body:`{"userId":123}`
- DB:
  - `agent_finding.lifecycle_status default 'OPEN'`
  - `assignee_id`,`fix_commit_sha`,`verified_by`,`verified_at`,`resolution_suggestion`
  - `agent_run.gate_verdict = PASS|WARN|BLOCK`

### 3. Contracts

- 既有 `Finding.status(candidate|verified|rejected)` 是 pipeline 校验轴,不得改名/复用;`lifecycle_status` 是人工处置轴。
- `AgentFindingResponse` 只能在尾部追加:`lifecycle,assigneeId,fixCommitSha,verifiedBy,verifiedAt,resolutionSuggestion`。
- 项目列表继续返回 `ApiResponse<PageResponse<AgentFindingResponse>>`;前端通过 `unwrapPage/pageMeta` 消费冻结信封。
- 自动复审按 `AgentScmContext.installationId + pullRequestNumber` 查更早 run 的**verified、fingerprint 非空、生命周期活跃** Finding;只写 `STILL_PRESENT/RESOLVED_SUGGESTED`,绝不改 lifecycle 或自动 CLOSED。
- 门禁仅用当前 run 的 pipeline `verified` Findings 做生命周期判断:BLOCK 优先;PASS/WARN 仍映射 SCM `SUCCESS`,BLOCK 映射 `ACTION_REQUIRED`。

### 4. Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| 项目不存在 | `PROJECT_NOT_FOUND` / 404 |
| 用户不是项目成员 | `PROJECT_FORBIDDEN` / 403 |
| Finding 不属于该项目或没有 AgentScmContext | `FINDING_NOT_FOUND` / 404 |
| action/userId 缺失或生命周期名未知 | `BAD_REQUEST` / 400 |
| 非法边、终态再流转/指派 | `FINDING_TRANSITION_ILLEGAL` / 409 |
| 确认/驳回/验证/关闭/验证打回 | REVIEWER 或 LEADER |
| 开始修复/标记 FIXED | assignee 或 LEADER |
| 指派 | LEADER;目标须项目成员(owner 按 LEADER 兜底) |

### 5. Good / Base / Bad Cases

- Good:通过 `AgentScmContext` join 获取项目 PR Findings;历史建议查询显式排除 currentRunId,并处理相同 createdAt 的稳定排序。
- Base:无 coverage 或无 Findings 时 gate 为 PASS;建议器失败时发布继续。
- Bad:用 `AgentRun.pullRequestId != null` 识别 webhook PR;把 pipeline rejected 的 HIGH Finding 因默认 OPEN 算成 WARN;用标题/文件路径替代 fingerprint 做自动关闭。

### 6. Tests Required

- `ObjectLevelAuthorizationMatrixTest`:新带 id 端点必须证明匿名 401、陌生人永不 2xx。
- 生命周期:完整主链、终态、FIXED 打回、DEVELOPER 确认 403、非 assignee FIXED 403、跨项目 404。
- 建议器:hit/miss/失败静默/不改 lifecycle。
- 门禁:blocking→BLOCK、coverage NOT_FOUND→WARN、verified HIGH/CRITICAL 或 FIXED→WARN、全部闭环→PASS、pipeline rejected HIGH 不触发 WARN。
- 契约:后端 test-compile + 前端 production build;完整测试在任务最终验证阶段执行。

### 7. Wrong vs Correct

#### Wrong

```java
// webhook PR 的 pullRequestId 当前为 null,会误杀全部合法 Finding
if (run.getPullRequestId() == null) throw new BusinessException(ErrorCode.FINDING_NOT_FOUND);
```

#### Correct

```java
// PR Agent 身份以一对一 AgentScmContext 为准
if (scmContexts.findByAgentRunId(run.getId()).isEmpty()) {
    throw new BusinessException(ErrorCode.FINDING_NOT_FOUND);
}
```
