# Frozen Contracts(跨任务常驻约束)

> Phase 0 为并行双线冻结的契约(原始决议:`docs/archive/并行实施拆分方案.md`),合流后仍然常驻:
> 它们是前后端、backend↔sandbox-runner、backend↔model-service 之间的既成事实。
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

## 4. ProjectAuthorization(`common/security/ProjectAuthorization.java`)

- 方法面固定:`requireRead(projectId, userId)` / `requireWrite(projectId, userId)`;需要实体的调用点继续用 `ProjectService.getRequired`(同一 404/403 语义的实体版)。
- 语义固定:项目不存在 → 404 `PROJECT_NOT_FOUND`,不属于当前用户 → 403 `PROJECT_FORBIDDEN`(防枚举)。
- **没有管理员旁路**;若将来需要,只能加在这个类里、带显式角色检查与负向测试(类头 Javadoc 明文)。
- 配套准入规则:新的带 id 端点必须进 `ObjectLevelAuthorizationMatrixTest`(见 [quality-guidelines.md](./quality-guidelines.md))。

## 5. Flyway 已执行迁移不可变

- 见 [database-guidelines.md](./database-guidelines.md):历史迁移零改动、新迁移接实测最大版本号(当前 V27)之后、V22–V25 预留不可占用。

## 6. REST 路径与字段名

- `/api/**` 的路径与 JSON 字段名是 SPA 契约(前端 `views/` 与 `composables/` 直接解构字段)。重命名 = 破坏性变更,必须与前端同批改。
- 兼容先例:合流期新旧形状并存靠**消费端适配器**(`unwrapPage` 双形状)与**路由层重定向**(旧 `#agent-evidence=` 外链由 `frontend/src/router.js` 转 `/agent?evidence=`),不靠后端同时维护两套端点。

## 7. MQ 载荷格式

- 队列名常量在 `config/RabbitMqConfig.java`(`agent.step.queue`、`sandbox.job.queue`、`code.review.task.queue` 等),不改名。
- `agent/queue/AgentStepMessage`:`{agentRunId, sequenceNo, attempt, traceId}`,JSON 序列化直接入队;消费端按同 record 反序列化。
- backend↔sandbox-runner:`SignedSandboxJob`/`SandboxJob` 是**两侧同构镜像**;HMAC 的规范化 JSON 由 `SandboxJobSigner.canonicalJson` **手工枚举字段**(键字典序、不走 JSON 库),字段增删必须两侧同步改 canonical 形式,否则签名/完整性静默漂移;record 布局由两侧 `SandboxJobFieldOrderTest` 快照钉死(`workspaceArchiveRef` 钉在第 2 位),任何布局改动先在测试里炸;签名兼容由两侧 `SandboxJobSignerTest` 的同一金标向量证明。改字段 = 两侧同批 + 金标测试先行。
- `WorkspaceArchiveReference` 线格式(`agent-run-{id}-{sha}.tar` / `patch-{id}-{sha}.tar`,裸文件名、无 scheme)由两侧同名测试用同一批字面量钉死;历史断链格式 `workspace://…` 在拒绝集里永久留存。背景与决策:`docs/adr/0001-工作区归档引用契约的单一事实源.md`。

## 8. backend↔model-service REST 契约

- `POST /predict` 请求 `{diffText, filePath, changeType}`、响应 `{riskType, severity, confidence, modelVersion, source}`(camelCase);消费方 `model/HttpModelRiskClient.java`,生产方 `model-service/app/main.py` 的 pydantic 模型。字段变更两侧同批。
