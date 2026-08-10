# Database Guidelines

> Spring Data JPA + PostgreSQL(pgvector) + Flyway。表名/列名 snake_case(`agent_run`、`code_repository`、`scm_installation`)。

---

## Flyway 迁移纪律(冻结约束)

迁移文件在 `backend/src/main/resources/db/migration/`,命名 `V<n>__<snake_case_描述>.sql`。

- **已执行过的迁移一个字节都不许改。** 生产库与 dev 库已跑过 V1–V21、V26、V27;修改历史迁移会导致 checksum 校验失败,库直接起不来。要改结构,永远新增迁移。
- **新迁移接在实测最大版本号之后。** 当前最大是 `V27__review_task_doc_set_key.sql`;V22–V25 已被预约(V22 约束批次见 `docs/数据库完整性预检与约束盘点.md`,V25 归 SCM webhook 唯一键),不要占用这些号。
- **迁移头部注释写清 why 与失败关闭理由。** 范例 `V26__reject_non_v1_credentials.sql`:说明背景(CryptoService 改为非 v1 即拒)、为什么置空而不是就地加密(密钥只在应用进程,SQL 做不了 AES-GCM)、以及生产库实测影响面("三列均为 0 条非 v1 记录")。涉及存量数据的迁移必须附实测数据量。

---

## 事务边界

- **写路径的事务注解打在 service 方法上。** `ProjectService.create/update/delete` 是标准样式:`@Transactional` + 实体加载/修改/保存都在方法内;纯读方法不加注解。
- **审计与状态标记用 `REQUIRES_NEW`,且必须放在独立 bean 里。** 失败路径的状态落库(`markFailed`)和调用日志必须在业务事务回滚后仍然存在,所以走独立事务:
  - `review/ReviewTaskStatusService`(markRunning/markFailed/markDead/isCanceled)
  - `ai/AiCallLogService`、`agent/model/AgentModelCallAuditService`
- **`@Transactional` 不自调用。** Spring 事务靠代理生效,`this.method()` 内部调用不会开新事务——这正是上面"独立 bean"模式存在的原因:`ReviewProcessor` 注入 `ReviewTaskStatusService` 再调用,而不是在自己类里写 `REQUIRES_NEW` 方法。同类内确需独立事务时用编程式 `TransactionTemplate`(设 `PROPAGATION_REQUIRES_NEW`),范例:`agent/run/AgentRecoveryService`、`knowledge/KnowledgeService` 的 `reindexTransactions`/`uploadTransactions`。
- **跨进程一致性用事务性 outbox,不用"先落库再发 MQ"。** Agent 状态迁移与 MQ 排队通过 `agent/outbox/` 同事务写入、独立投递;Run 创建与首步派发靠同事务领域事件绑定(`WebhookAgentRunService` 发布 `AgentRunCreatedEvent`,BEFORE_COMMIT 监听排入首步,见 `docs/adr/0001-工作区归档引用契约的单一事实源.md` 决策 6)。

---

## 常见错误

- 在已执行迁移里"顺手补一列" → checksum 不匹配,环境全红。新增迁移。
- 给同类的内部调用加 `@Transactional(REQUIRES_NEW)` 并期待它生效 → 代理被绕过,悄悄跑在外层事务里。抽独立 bean 或 `TransactionTemplate`。
- 新增可配置路径/新表后不同步测试属性 → 见 [quality-guidelines.md](./quality-guidelines.md) 的 Spring 上下文测试规则(F-02 事故)。
