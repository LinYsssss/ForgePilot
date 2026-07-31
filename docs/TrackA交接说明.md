# Track A 交接说明（给 Track B / 集成负责人）

> 分支：`fix/track-a-core`（已推送，与远程同步）
> 基线：`fix/defense-hardening` @ `aae3446`（Phase 0 完成点）
> 截至：2026-07-28
> 测试：**410 tests / 0 failures / 0 errors / 3 skipped**（基线为 304）

本文件是 Track A 的完整交接。**第二节和第七节是 Track B 必须先读的部分**，其余可按需查阅。

---

## 一、Track A 完成了什么

十一个提交，`git log --oneline main..fix/track-a-core`：

| 提交 | 内容 | 关闭项 |
|---|---|---|
| `84166b3` | 按所有权拆分 app 配置 | Phase 0 |
| `aae3446` | 冻结四份跨线契约 | Phase 0 |
| `9be72b3` | Outbox 调度器 + 认领租约 + 终态 + Broker Confirm | **P0-01/02/03** |
| `a4ca720` | Step 三段执行 + 心跳续租 + 周期恢复 | **P0-04**、P1-04 |
| `ebfebb8` | MQ 日志 IDOR + 分页 | **P0-05** |
| `9a964f2` | Knowledge 事务拆分 + 上传输入边界 | P1-05、P1-15 |
| `062c9ac` | 数据库约束盘点与预检脚本 | P1-10 前置 |
| `313f0b6` | 反向授权测试矩阵（29 用例） | P1 授权 |
| `d5f6e0d` | Review Task/Report、Knowledge Document 分页 | P1-11 |
| `55eaf54` | 37 处错误码精确化（状态不变） | P1-12 |
| `31ae9d5` | 6xxx 族状态码语义修正 | P1-12 |

**Track A 的全部 P0 已关闭。**

---

## 二、Track B 必须知道的六件事

### 2.1 `ErrorCode` 枚举里已经有你的码，直接用

`common/api/ErrorCode.java` 在 Phase 0 就把 A、B 双方的码一次性定全并冻结了。B 需要的至少包括：

```
AUTH_REQUIRED  AUTH_INVALID_CREDENTIALS  AUTH_RATE_LIMITED  CSRF_TOKEN_INVALID
OUTBOUND_URL_REJECTED  GIT_COMMAND_FAILED  GIT_REPOSITORY_TOO_LARGE
WEBHOOK_SIGNATURE_INVALID  WEBHOOK_INSTALLATION_UNKNOWN  WEBHOOK_EVENT_UNSUPPORTED
SCM_PUBLISH_FAILED  CREDENTIAL_DECRYPT_FAILED
SANDBOX_SIGNATURE_INVALID  SANDBOX_REPLAY_REJECTED  MODEL_SERVICE_UNAVAILABLE
```

**该文件归 A 独占。** 若确实缺码，在 `docs/跨线协商.md` 追加一条，由 A 补，不要自己改 —— 这是唯一一个双方都要读的枚举，同时改必冲突。

**新增码时要守住一个不变量**：`ErrorCode` 的 `httpStatus` 与 `BusinessException` 解析出的状态不能矛盾（4xx 的码不能配 5xx 的状态）。`SharedApiContractTest.derivedErrorCodeNeverContradictsTheResolvedHttpStatus` 会守着它。

### 2.2 四个端点已改成分页信封，前端要适配

```
GET /api/mq/logs
GET /api/projects/{id}/reviews/tasks
GET /api/projects/{id}/reviews/reports
GET /api/projects/{id}/knowledge/documents
```

响应从裸数组变成：

```json
{ "items": [...], "page": 0, "size": 20, "totalElements": 25, "totalPages": 2 }
```

默认 20、硬上限 100、负数回落默认值、越界页返回空数组而非报错。契约自 Phase 0 冻结（`common/api/PageResponse.java`），**B9 无需等 A**。但在 B9 完成前，这四个页面会显示异常 —— 这是预期内的，不是 bug。

### 2.3 `app.security.csrf.enabled` 合入时必须回落 false

位于 `config/app-boundary.yml`（B 独占）。B 可在自有分支置 true 做测试，但**合入集成分支时必须改回 false**。提前打开会让 A 的全部 MockMvc 非 GET 测试立刻 403，被误判成 A 的缺陷。由合流阶段统一打开并跑全量回归。

### 2.4 Flyway 版本段有调整

| 段 | 归属 | 实际占用 |
|---|---|---|
| V19 | A | agent_outbox 投递护栏 |
| V20 | A | agent_step 执行租约 |
| V21 | A | **knowledge_document.index_error**（原计划给引用完整性，已挪用） |
| V22~V24 | A | 预留给完整性/CHECK/索引，**尚未编写** |
| V25~V29 | **B** | 不变 |

B 的段没受影响。

### 2.5 `UQ_SCM_DELIVERY_PROVIDER_DELIVERY` 唯一索引已经存在

在 `scm_webhook_delivery(provider, delivery_id)` 上。B5 做 Webhook `ON CONFLICT` 原子幂等时**大概率不需要新建唯一键**，动手前先确认既有索引是否满足需求，别盲目再加一条。`scm_installation(provider, external_installation_id)` 的唯一索引同样已存在。

### 2.6 本机 Node 是 v24.13.0

`package.json` 的 engines 要求 `>=20 <23` —— P0-07 那个版本冲突不只在 Docker 里，**开发机上同样存在**。B9 把三处（engines / Dockerfile / CI）统一到 Node 22 时，本机大概也要装一个 22。

---

## 三、A 侧新增的可复用设施

| 文件 | 用途 | 归属 |
|---|---|---|
| `common/api/ErrorCode.java` | 全局错误码词表，含 `fromLegacy(int)` 机械映射 | A 独占，B 只读 |
| `common/api/PageResponse.java` | 分页信封 + `sanitizePage/sanitizeSize` | A 独占，B 只读 |
| `common/security/ProjectAuthorization.java` | `requireRead/requireWrite`，404 不存在 / 403 非所有者 | Phase 0 冻结，签名不变 |
| `common/api/ApiResponse.java` | 新增 `errorCode`、`traceId` 字段（加法，未动 `code`） | A 独占 |

`ProjectAuthorization` **刻意没有管理员绕过** —— 加了会一次性放宽所有已采用它的端点。若将来需要，应在这一处加，并配独立的反向测试。

---

## 四、三个可以直接抄的模式

A 侧解决的问题 B 大概率会再遇到，写下来省得重新踩：

**1. 认领令牌 + CAS 回写**（`AgentOutboxRepository` / `AgentStepRepository`）
外部调用必须在事务外进行，于是回写时可能已经失去所有权。所有状态转换都做成条件更新 `where id = ? and token = ? and status = ?`，返回 0 行即表示"我已经不是持有者，结果丢弃"。B8 的 ReplayGuard、B5 的 Webhook 幂等都是同构问题。

**2. 心跳续租而非固定租约**（`AgentStepLeaseHeartbeat`）
固定租约是两难：够长则崩溃后卡很久，够短则误杀慢任务。续租把两者解耦，未续上的租约才是真实的存活信号。

**3. 后台调度默认关闭**（`AgentSchedulingConfig`）
`@EnableScheduling` 挂在条件化的配置类上，由 `app.agent.scheduling.enabled` 控制，默认 false、prod true。这样测试上下文完全没有后台线程与断言竞态，也不会在没有 Broker 时每秒刷一次连接失败。B 若要加后台任务，复用同一个开关模式。

---

## 五、两个踩过的坑，B 大概率也会遇到

### 5.1 `@Transactional` 加在同类方法上不生效

自绑定调用绕过 Spring 代理，注解**静默失效** —— 表面上事务拆分做了，实际没做。A4 中招过，改用显式 `TransactionTemplate` 解决。B2/B5/B6 都有拆事务的需求，注意这一点。

### 5.2 YAML 静默损坏

一次误删换行把两行 YAML 合并，导致 **102 个测试因 Spring 上下文加载失败而报错**，而根因信息埋在 surefire 报告的 `Caused by: mapping values are not allowed here` 里，从测试列表完全看不出来。

配置是这次并行拆分的核心资产。**每次改配置后建议跑一次**：

```bash
python -c "
import io,yaml
for p in ['config/app-agent.yml','config/app-boundary.yml','application.yml','application-prod.yml','application-dev.yml']:
    yaml.safe_load(io.open(p,encoding='utf-8').read()); print('OK',p)
"
```

还可以顺带校验两个归属文件的叶子键零交叠 —— 这是拆分不变量。当前状态：app-agent 64 个叶子键，app-boundary 23 个，**交叠为空**。

---

## 六、Track A 尚未完成的

| 项 | 状态 | 说明 |
|---|---|---|
| A5 数据库完整性 V22~V24 | **阻塞** | 需可执行 Flyway 的 PostgreSQL。前置盘点与预检 SQL 已交付于 `docs/数据库完整性预检与约束盘点.md`，放行条件见其第七节 |
| 剩余 11 处通用 400 | 未做 | 都是参数校验，语义本就是 `BAD_REQUEST`，价值低 |
| Agent Run / Finding / AI Call Log 分页 | 未做 | AI Call Log 已有 limit 上限 200，风险低；另两个增长受单次运行约束 |

---

## 七、必须如实写进验收材料的三条

计划要求"任何 skipped、未安装 Docker、未运行扫描器的项目都必须明确记为**未验证**，不能写成通过"。以下三条属于此列：

### 7.1 三个 Testcontainers 测试仍是 skipped

A1 的"真实 RabbitMQ 完成发布与消费"、A2 的"进程级 Worker 崩溃后租约恢复"目前**只有单元/组件级证据**，没有真实 Broker 与真实进程崩溃的验证。

### 7.2 V19 / V20 / V21 从未被任何数据库执行过

这一条容易被 410 个绿色测试掩盖，必须点明：

- dev/test 用 H2 + `ddl-auto=create-drop` **从实体建表**，`spring.flyway.enabled=false`，完全绕过 Flyway；
- 唯一会执行迁移的 `LegacySchemaMigrationIntegrationTest` 标了 `@Testcontainers(disabledWithoutDocker = true)`；
- `V1__baseline_schema.sql` 第一行是 `create extension if not exists vector`，H2 也跑不了。

所以这三个迁移目前只有"语法上看着对"的把握。**B 新增 V25/V26 时面对完全相同的情况。**

### 7.3 错误路径的测试覆盖比总数看起来薄

`31ae9d5` 把 6xxx 族的 HTTP 状态从 400 改成 404/503，**没有打断任何一个测试** —— 说明此前没有任何测试断言过这些错误路径的状态码。410 这个数字掩盖了这一点。JaCoCo 的 branch 覆盖率（基线 51.9%）也指向同一结论。

验收时应区分"单元/组件测试通过"与"该路径有断言"，不要用总数代替。

---

## 八、合流建议

顺序不变（见 `docs/并行实施拆分方案.md` 第六节）：

1. **A 先合入** `fix/defense-hardening` —— 内核是地基，且 A 拥有 `GlobalExceptionHandler` 与两个 common 契约文件；
2. B 再合入，跑全量：`mvn verify` + `sandbox-runner mvn test` + 前端 `npm ci && npm test && npm run build`；
3. **打开 CSRF 开关**跑全量回归。此时会暴露 A 遗留的未带 CSRF 的 MockMvc 写测试 —— 这是**预期内的一次性集中修复**，不是返工；
4. 跑完整授权反向矩阵，确认 A、B 双方的 ID 型资源都被覆盖；
5. 进入 Phase 7 动态验收。

### 装 Docker 是共同的解锁点

A5、三个 skipped 测试、迁移链验证、Compose smoke、镜像扫描、备份恢复 —— 全部指向同一个环境依赖，而这也正是 B10 需要的。建议在两条线合流前把它准备好，届时可以一次性把这些从"未验证"转成真实证据。

---

## 九、当前分支状态

```
main                     9fba7d4   两份方案文档 + 演示素材
fix/defense-hardening    aae3446   Phase 0（配置拆分 + 四份契约）
fix/track-a-core         31ae9d5   A1~A4、A6，11 个提交，已推送
fix/track-b-boundary     aae3446   从 Phase 0 完成点切出
```

`fix/track-a-core` 工作区干净，与远程同步。四组未跟踪 Demo 文件已在 `3096a51` 单独提交，未混入任何功能提交。
