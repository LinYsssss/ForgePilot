# Research: after-commit 调度与 reconciliation（Phase 6）

- **Query**: `PullRequestChanged` 的「事务内建 PENDING + 提交后才调度」两段语义在 Spring Boot 4.1 里如何同时成立；AFTER_COMMIT 抛异常的真实后果；reconciliation 的查询必须长什么样才**不可能**补建 Review；停滞判定；不新增依赖的执行器与并发上限
- **Scope**: internal + 真实 Spring Boot 4.1.0 / Spring Framework 7.0.8 / PostgreSQL 15.19 实测
- **Date**: 2026-08-21

---

## 0. 六条最重要的结论（先读这个）

| # | 结论 | 性质 |
|---|---|---|
| **C1** | **AFTER_COMMIT 监听器抛出的异常不会传播到任何调用方。** 在 Spring Framework 7.0.8 里它由 `TransactionSynchronizationUtils.invokeAfterCompletion` 调用，该方法 `catch (Throwable)` 并只打一条 ERROR 日志。实测：webhook 依然返回 **202**，PR 行**已提交**，调用方 `callerSaw = "no exception"`。 | **实测**（§3.1、§3.2） |
| **C2** | **「一个方法同时挂两个注解」会静默丢掉事务内那一半。** 实测同时标注 `@EventListener` + `@TransactionalEventListener(AFTER_COMMIT)` 的方法**只被调用一次，且在提交之后**。必须是**两个方法**。 | **实测**（§2.2） |
| **C3** | **AFTER_COMMIT 里 `isActualTransactionActive()` 仍返回 `true`**，`isConnectionTransactional()` 也返回 `true`，但 `isSynchronizationActive()` 已是 `false`，且底层物理连接的 `autoCommit` 已被恢复成 `true`。**用前两者判断「我在不在事务里」必然判错。** | **实测**（§2.3、§2.5） |
| **C4** | **AFTER_COMMIT 里 `EntityManager.persist` / `Repository.saveAndFlush` 显式失败（`No active transaction`），但裸 `JdbcTemplate.update` 会成功并立即提交**——落在同一条已提交连接上、单语句自动提交、无法回滚。这是最危险的一条：它「看起来能用」。合法写法只有 `REQUIRES_NEW`。 | **实测**（§2.4、§2.5） |
| **C5** | **Boot 4.1 的默认 `applicationTaskExecutor` 是 `core=8 / max=2147483647 / queue=2147483647`，绝不能复用**；而且**只要应用声明任何 `Executor` bean，Boot 的这个默认 bean 就整个消失**（实测 `applicationTaskExecutorPresent=false`）。并发上限必须写在 **corePoolSize**——`maxPoolSize` 在默认无界队列下永远达不到（实测 8 个任务、max=4，池仍只有 1 个线程）。 | **实测**（§6.1–§6.3） |
| **C6** | **reconciliation 的禁令可以用「FROM 子句里只准出现 `review`」这一条结构规则执行。** 实测同一场景下：以 `pull_request` 为驱动表的 `NOT EXISTS` 补建查询返回 `[1]`（会建出一条不该有的 Review），以 `review` 为唯一驱动表的恢复查询返回 `[]`。 | **实测**（§4.2） |

---

## 1. 实测环境与可复现方法

### 1.1 版本（实测输出，非查文档）

```text
PROBE|DEFAULTS.springBootVersion|4.1.0
PROBE|DEFAULTS.springFrameworkVersion|7.0.8
PROBE|DEFAULTS.javaVersion|21.0.11
PROBE|DEFAULTS.hikari.maximumPoolSize|5
PROBE|DEFAULTS.hikari.minimumIdle|5
```

数据库：`pgvector/pgvector:0.8.6-pg15-bookworm`，启动日志 `Database version: 15.19`，`Isolation level: READ_COMMITTED [default READ_COMMITTED]`。

### 1.2 复现命令

四轮一次性探针类写入 `backend/src/test/java/com/forgepilot/scm/`，跑完全部删除（见 §8.4 的清理自证）。
探针源码保存在 `/root/.claude/jobs/e84ffece/tmp/`：
`AfterCommitProbeTest.java`、`AfterCommitProbeTwoTest.java`、`AfterCommitProbeThreeTest.java`、
`DefaultRuntimeProbeTest.java`、`BothAnnotationsProbeTest.java`、`ExecutorBackoffProbeTest.java`。

```bash
cd backend && flock /root/.claude/jobs/e84ffece/tmp/maven.lock docker run --rm --network host \
  -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp test \
  -Dtest='AfterCommitProbeTest,DefaultRuntimeProbeTest,BothAnnotationsProbeTest' -DfailIfNoTests=false
# 第二轮：-Dtest='AfterCommitProbeTwoTest'
# 第三轮：-Dtest='AfterCommitProbeThreeTest'
# 第四轮：-Dtest='ExecutorBackoffProbeTest'
```

四轮结果：

| 轮次 | 结果 | 日志 |
|---|---|---|
| 1 | `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` | `/root/.claude/jobs/e84ffece/tmp/probe-run1.log` |
| 2 | `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` | `probe-run2.log` |
| 3 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` | `probe-run3.log` |
| 4 | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` | `probe-run4.log` |

所有事实以 `PROBE|键|值` 行打印，可 `grep "^PROBE" <log> | sort -u` 复现。

### 1.3 探针如何区分「提交前」与「提交后」

`isActualTransactionActive()` **不能**区分（C3），所以判据换成：**从连接池另开一条裸连接**（`dataSource.getConnection()`，绕过 `DataSourceUtils`）去数刚写入的那一行。READ COMMITTED 下提交前必然看到 0、提交后必然看到 1。该判据本身经过标定：

```text
PROBE2|A-BOTH.inTx.rawConnectionSees|0          # 事务内 @EventListener
PROBE2|A-BOTH.beforeCommit.rawConnectionSees|0  # BEFORE_COMMIT
PROBE2|A-BOTH.afterCommit.rawConnectionSees|1   # AFTER_COMMIT
```

---

## 2. Q1：两段语义如何在 Spring 4.1 里同时成立

### 2.1 结论：两个方法，不是一个方法分两段

**实测（一个 bean、两个方法、同一个事件）全部按预期触发，顺序为事务内先、提交后后：**

```text
PROBE|Q1.inTx.order|1
PROBE|Q1.inTx.actualTransactionActive|true
PROBE|Q1.inTx.rowVisible|1
PROBE|Q1.afterCommit.order|2
PROBE|Q1.afterCommit.threadName|main
PROBE|Q1.afterCommit.readSeesTheCommittedRow|1
```

事务内那一半（`@EventListener`）的语义与批次 2 已有测试一致，实测两条边界：

```text
PROBE|Q1C.rollback.inTxListenerFired|true
PROBE|Q1C.rollback.afterCommitListenerFired|false
PROBE|Q1C.rollback.rowsCommitted|0          # 回滚，PR 与 PENDING 一起消失
PROBE|Q1B.noTx.inTxListenerFired|true
PROBE|Q1B.noTx.afterCommitListenerFired|false   # 无事务时 AFTER_COMMIT 根本不触发
```

> **`Q1B` 对人工触发路径有直接后果**：`ReviewService.requestReview(...)` 若在**没有事务**的调用栈里发布同一个事件，AFTER_COMMIT 那一半**不会执行**，Review 会永远停在 PENDING 直到 reconciliation 捡起来。人工触发与失败重试要么必须自己包在事务里，要么必须走**显式**的「建行 → 提交 → 调度」而不是复用事件。

### 2.2 一个方法挂两个注解：静默丢掉事务内那一半

```text
PROBE2|A-BOTH.both.invocationCount|1
PROBE2|A-BOTH.both.invocation.0|rawConnectionSees=1 (0 means the adapter ran before commit, 1 means after) synchronizationActive=false
```

`rawConnectionSees=1` 说明它跑在**提交之后**：`TransactionalEventListenerFactory` 赢了，普通 `@EventListener` 适配器**根本没有被创建**。
容器**不报错、不警告**，只是那一半消失了。

**这条如果写错，「监听失败则整个 SCM 事务回滚」这条 ARCH:261 的保证会静默失效，而所有单线程测试仍然全绿。**
建议把「两个注解不得同现于一个方法」写成一条可执行断言（ArchUnit 或一条集成测试断言事务内监听器确实看到未提交状态）。

### 2.3 AFTER_COMMIT 阶段的线程状态（三个反直觉读数）

```text
PROBE|Q1.afterCommit.actualTransactionActive|true     # ← 仍然是 true
PROBE|Q1.afterCommit.synchronizationActive|false      # ← 已经是 false
PROBE|Q1.afterCommit.transactionName|com.forgepilot.scm.AfterCommitProbeTest$ProbeService.writeAndPublish
PROBE|Q1.afterCommit.dataSourceStillBound|true
PROBE|Q1.afterCommit.entityManagerStillBound|true
```

对照事务内：

```text
PROBE2|A-BOTH.inTx.actualTransactionActive|true
PROBE2|A-BOTH.inTx.synchronizationActive|true
```

**唯一能区分两个阶段的 `TransactionSynchronizationManager` 读数是 `isSynchronizationActive()`**（true = 事务内，false = 提交后）。
`isActualTransactionActive()`、`getCurrentTransactionName()`、资源绑定三项在两个阶段完全相同。

原因（**推理**，依据是 §3.1 里实测到的调用栈）：Spring 7.0.8 把 AFTER_COMMIT 监听器搬到了 `afterCompletion` 里执行，而 `triggerAfterCompletion` 会先 `clearSynchronization()` 再回调，`actualTransactionActive` 则要到 `cleanupAfterCompletion` 才清。

### 2.4 AFTER_COMMIT 里还能不能用同一个事务的 EntityManager：不能

**`EntityManager.persist` + `flush`**：

```text
PROBE|Q1-EM.emPersist.listenerSaw|jakarta.persistence.TransactionRequiredException: No active transaction
    at org.hibernate.internal.AbstractSharedSessionContract.checkTransactionNeededForUpdateOperation(AbstractSharedSessionContract.java:682)
    at org.hibernate.internal.SessionImpl.fireFlush(SessionImpl.java:1453)
    at org.springframework.orm.jpa.SharedEntityManagerCreator$SharedEntityManagerInvocationHandler.invoke(SharedEntityManagerCreator.java:383)
    at com.forgepilot.scm.AfterCommitProbeTest$ProbeListeners.afterCommit(...)
PROBE|Q1-EM.emPersist.childRowCommitted|0
PROBE|Q1-EM.emPersist.callerSaw|no exception      # ← 异常还被吞了，见 C1
```

**`JpaRepository.saveAndFlush`**：

```text
PROBE|Q1-REPO.repoSave.listenerSaw|org.springframework.dao.InvalidDataAccessApiUsageException: No active transaction
    at org.springframework.orm.jpa.hibernate.HibernateExceptionTranslator.translateExceptionIfPossible(HibernateExceptionTranslator.java:112)
    at jdk.proxy2/jdk.proxy2.$Proxy198.saveAndFlush(Unknown Source)
PROBE|Q1-REPO.repoSave.childRowCommitted|0
PROBE|Q1-REPO.repoSave.callerSaw|no exception
```

两条都**显式失败**（好事），但**两条的失败都被吞掉**（坏事）：调用方看到「成功」，子行没写进去。

### 2.5 裸 JdbcTemplate 却「成功」了——最危险的一条

```text
PROBE|Q1-JDBC.jdbcWrite.listenerSaw|no exception, rowsAffected=1
PROBE|Q1-JDBC.jdbcWrite.listenerCanReadItBack|1
PROBE|Q1-JDBC.jdbcWrite.childRowCommitted|1        # ← 真的提交了
```

原因实测：**同一条物理连接**，`autoCommit` 已经被恢复：

```text
PROBE2|C-CONNECTION.inTx.connection|identity=1412567468 autoCommit=false transactional=true
PROBE2|C-CONNECTION.afterCommit.connection|identity=1412567468 autoCommit=true  transactional=true
```

即：AFTER_COMMIT 里的每条 JDBC 语句都是**独立自动提交**的，没有任何事务包住它，出错也没有回滚，
而 `DataSourceUtils.isConnectionTransactional()` **仍然骗你说 `true`**。

（连接身份相同、autoCommit 被 Hibernate 在物理事务结束时恢复——身份与 autoCommit 是实测值；「Hibernate 恢复的」是**推理**。）

### 2.6 AFTER_COMMIT 里开 `REQUIRES_NEW`：可行，且是唯一合法写法

```text
PROBE|Q1-NEWTX.requiresNew.listenerSaw|REQUIRES_NEW ran, actualTransactionActive=true name=com.forgepilot.scm.AfterCommitProbeTest$NewTransactionWriter.write
PROBE|Q1-NEWTX.requiresNew.childRowCommitted|1
PROBE|Q1-NEWTX.requiresNew.callerSaw|no exception
```

真的开了新事务、真的提交了。注意 `callerSaw = no exception` 仍然成立——**新事务里失败了照样没人知道**（C1 对它同样适用）。

---

## 3. Q2：AFTER_COMMIT 抛异常会怎样

### 3.1 异常**不会**传播到调用方，事务**当然**不回滚（已提交）

服务边界：

```text
PROBE|Q2-SERVICE.boom.callerSaw|no exception
PROBE|Q2-SERVICE.boom.rowsCommittedAfterwards|1
```

监听器确实抛了，日志里有且只有一条 ERROR（run1 log:171，线程是 Tomcat 工作线程）：

```text
2026-08-21T18:37:11.020Z ERROR 78 --- [forgepilot-backend] [o-auto-1-exec-1] o.s.t.s.TransactionSynchronizationUtils  : TransactionSynchronization.afterCompletion threw exception

java.lang.IllegalStateException: the after-commit scheduler could not hand the Review to the executor
	at com.forgepilot.scm.AfterCommitProbeTest$ProbeListeners.afterCommitOnPullRequest(AfterCommitProbeTest.java:816)
	at org.springframework.context.event.ApplicationListenerMethodAdapter.processEvent(ApplicationListenerMethodAdapter.java:270)
	at org.springframework.transaction.event.TransactionalApplicationListenerSynchronization.processEventWithCallbacks(TransactionalApplicationListenerSynchronization.java:65)
	at org.springframework.transaction.event.TransactionalApplicationListenerSynchronization$PlatformSynchronization.afterCompletion(TransactionalApplicationListenerSynchronization.java:118)
	at org.springframework.transaction.support.TransactionSynchronizationUtils.invokeAfterCompletion(TransactionSynchronizationUtils.java:202)
	at org.springframework.transaction.support.AbstractPlatformTransactionManager.invokeAfterCompletion(AbstractPlatformTransactionManager.java:1046)
	at org.springframework.transaction.support.AbstractPlatformTransactionManager.triggerAfterCompletion(AbstractPlatformTransactionManager.java:1021)
	at org.springframework.transaction.support.AbstractPlatformTransactionManager.processCommit(AbstractPlatformTransactionManager.java:838)
	at org.springframework.transaction.interceptor.TransactionAspectSupport.commitTransactionAfterReturning(TransactionAspectSupport.java:687)
	at com.forgepilot.scm.PullRequestSyncService$$SpringCGLIB$$0.apply(<generated>)
	at com.forgepilot.scm.github.GitHubWebhookController.receive(GitHubWebhookController.java:86)
```

**关键在倒数第 6 行**：Spring 7.0.8 把 AFTER_COMMIT 监听器接在 `PlatformSynchronization.afterCompletion` 上，
而 `TransactionSynchronizationUtils.invokeAfterCompletion` 是 catch-and-log 的。所以异常到不了 `processCommit` 的调用方。

同一轮还实测到：**第一个 AFTER_COMMIT 监听器抛异常不影响第二个**（逐个 synchronization catch）：

```text
PROBE2|B1.b1.callerSaw|no exception
PROBE2|B1.b1.rowsCommitted|1
PROBE2|B1.b1.secondListenerStillRan|true
```

### 3.2 真实 webhook 的真实 HTTP 状态：**202**

真 Tomcat（`webEnvironment=RANDOM_PORT`）、真 socket、真签名、真 provider stub、production 的 `GitHubWebhookController`：

```text
PROBE|Q2-WEBHOOK.webhook.afterCommitListenerFired|true
PROBE|Q2-WEBHOOK.webhook.httpStatus|202
PROBE|Q2-WEBHOOK.webhook.body|
PROBE|Q2-WEBHOOK.webhook.pullRequestRowsCommitted|1
```

**GitHub 会认为投递成功、不会重投；PR 与（批次 3 之后的）PENDING Review 已提交；而调度没有发生，且没有任何调用方被告知。**
这正是 PLAN:76「after-commit 失败可恢复」所指的场景——实测证明它比文档措辞更严重：**失败是完全静默的**，只有一行 ERROR 日志。

### 3.3 对照：什么才会传播

| 挂钩点 | 抛异常后调用方看到 | 数据 | 实测键 |
|---|---|---|---|
| 事务内 `@EventListener` | **传播** `IllegalStateException` | **回滚**（0 行） | `PROBE2\|B5` |
| `@TransactionalEventListener(BEFORE_COMMIT)` | **传播** | **回滚**（0 行） | `PROBE2\|B4` |
| `@TransactionalEventListener(AFTER_COMMIT)` | **吞掉**（`no exception`） | 已提交（1 行） | `PROBE2\|B1` |
| 手写 `TransactionSynchronization.afterCommit()` | **传播** | 已提交（1 行） | `PROBE2\|B2` |
| 手写 `TransactionSynchronization.afterCompletion(int)` | **吞掉** | 已提交（1 行） | `PROBE2\|B3` |

B2 的实测栈（证明它走的是另一条路径）：

```text
java.lang.IllegalStateException: manual afterCommit refuses to schedule
  at ...$ProbeListeners$1.afterCommit(AfterCommitProbeTwoTest.java:529)
  at org.springframework.transaction.support.TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationUtils.java:165)
  at org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit(TransactionSynchronizationUtils.java:153)
  at org.springframework.transaction.support.AbstractPlatformTransactionManager.triggerAfterCommit(AbstractPlatformTransactionManager.java:1005)
  at org.springframework.transaction.support.AbstractPlatformTransactionManager.processCommit(AbstractPlatformTransactionManager.java:835)
```

> **设计取舍（需裁定，见 §7.2）**：若希望「调度失败」对调用方可见，只有手写 `TransactionSynchronization.afterCommit()` 这一条路。
> 代价是 webhook 会在 **PR 与 PENDING 都已提交** 的情况下返回 500，GitHub 会重投——重投是幂等的（四元组唯一约束），但会白跑一次 provider 拉取。
> 用 `@TransactionalEventListener(AFTER_COMMIT)` 则永远返回 202，**完全依赖 reconciliation 兜住**。两条都能成立，但**必须显式选一条并写进 design.md**，不能靠默认。

### 3.4 最真实的失败模式：执行器满了

不需要人为抛异常。把执行器冻结成 `core=1 / max=1 / queue=0` 并占满，再走一次事件：

```text
PROBE3|B-SATURATED.saturated.listenerReached|true
PROBE3|B-SATURATED.saturated.callerSaw|no exception
PROBE3|B-SATURATED.saturated.rowsCommitted|1
```

日志里（run3 log）：

```text
ERROR ... o.s.t.s.TransactionSynchronizationUtils  : TransactionSynchronization.afterCompletion threw exception

org.springframework.core.task.TaskRejectedException: ExecutorService in active state did not accept task: ...$ProbeListeners$$Lambda/0x00007fd04d031ba8@28964cb5
	at org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.execute(ThreadPoolTaskExecutor.java:388)
	at com.forgepilot.scm.AfterCommitProbeThreeTest$ProbeListeners.afterCommit(AfterCommitProbeThreeTest.java:292)
	at ...TransactionalApplicationListenerSynchronization$PlatformSynchronization.afterCompletion(...:118)
	at ...TransactionSynchronizationUtils.invokeAfterCompletion(...:202)
```

**并发上限冻结为 1 或 2（PLAN:75）与队列容量一起，直接决定了这条路径的发生频率。**
`Q5-BOUNDED`（§6.2）证明超出后是**拒绝**而不是阻塞，所以「上限越小 → 被拒绝的 PENDING 越多 → reconciliation 越吃重」。

### 3.5 webhook 的 202 会等 AFTER_COMMIT 监听器返回

热身后的干净 A/B（run3）：

```text
PROBE3|A-TIMING.webhook.1.warmUpMillis|7099        # 首次调用，含 JIT / HttpClient / provider 首连
PROBE3|A-TIMING.webhook.2.noSleepMillis|586
PROBE3|A-TIMING.webhook.3.noSleepMillis|613
PROBE3|A-TIMING.webhook.4.with1500msListenerMillis|2086   # 监听器里 sleep(1500)
PROBE3|A-TIMING.webhook.5.noSleepMillis|795
PROBE3|A-TIMING.webhook.status.803..807|202（五次全部 202）
```

2086 − ≈600 ≈ 1486 ≈ 1500。**监听器阻塞多久，webhook 就多返回多久。**
所以 after-commit callback 里**只能做 hand-off**（一次 `execute(...)`），不能做任何 I/O、不能预热上下文、更不能同步跑 Review。
（这也是 ARCH:263「Webhook 在 PR 与 PENDING Review 均提交后返回 202，不等待 LLM」在实现上的确切含义。）

---

## 4. Q3：reconciliation 的边界——「禁止补建」在实现上意味着什么

### 4.1 一条可执行的结构规则

> **恢复查询的 FROM 子句里只准出现 `review` 一张表；语句只准是 `UPDATE ... WHERE`，不准出现 `INSERT`；
> `pull_request` 只允许出现在 `SELECT` 投影或 `JOIN` 的被动侧，绝不允许出现在驱动位置。**

理由是结构性的，不是纪律性的：**只要驱动表是 `review`，查询的结果集就是 `review` 已有行的子集，
因此无论条件写错成什么样，它都不可能产出「一条不存在的 Review」。**
反过来，只要驱动表是 `pull_request`（哪怕条件是 `NOT EXISTS (select ... from review ...)`），
结果集就是 PR 的子集，而对一个 PR「恢复」的唯一可能动作就是**创建**。

### 4.2 两种查询形状的实测对照

在真实 PostgreSQL 上用两张一次性表跑（`review` 表批次 3 才存在，故用 `probe_pr` / `probe_review` 同构模拟，跑完 drop）：

场景：PR 的 Review 已经 `COMPLETED`（`requirement_revision_id = NULL`），随后**人工把需求版本关联上去**（D007 明确允许）。

```sql
-- 补建形状（禁止）
select p.id from probe_pr p where not exists (
  select 1 from probe_review r where r.pull_request_id = p.id
    and r.head_sha = p.head_sha and r.fingerprint = p.fingerprint
    and r.requirement_revision_id is not distinct from p.requirement_revision_id);

-- 恢复形状（允许）
select r.id from probe_review r where
     (r.status = 'PENDING' and r.created_at < now() - interval '2 minutes')
  or (r.status = 'RUNNING' and r.lease_until < now());
```

```text
PROBE2|F-QUERIES.backfillQuery.rowsItWouldCreate|[1]      # ← 会建出一条不该有的 Review
PROBE2|F-QUERIES.recoveryQuery.rowsItWouldResume|[]       # ← 什么都不做（正确）
PROBE2|F-QUERIES.recoveryQuery.rowsWhenStalled|[1]        # ← 真停滞时确实捡起来
PROBE2|F-QUERIES.backfillQuery.rowsWithNoReviewAtAll|[1]  # 一条 Review 都没有时照样想建
PROBE2|F-QUERIES.recoveryQuery.rowsWithNoReviewAtAll|[]   # 结构上无法凭空创建
```

### 4.3 具体反例：如果 reconciliation 写成会补建

**反例 A（最可能真实发生）——人工改需求关联触发自动重审。**
PRD:119 与 ARCH:265 都明确「需求版本变更**不自动重审**，由人工触发」。
但 Review Identity 含 `requirement_revision_id`（ARCH:270），所以人工一改关联，
「当前 head + 当前 fingerprint + 当前 revision」这一组就**没有**对应 Review 了。
补建形状的查询会立刻返回这条 PR（实测 `[1]`），于是 scheduler 建出一条 PENDING 并跑起来——
**这就是 ARCH:337 禁止的「第二套 Pipeline」，只不过它藏在一条 SQL 里**：一个绕过 `ReviewService.requestReview(...)`
和权限判定的自动触发入口。用户没点任何按钮，AI 调用和 token 就消耗了。

**反例 B（更隐蔽）——head 未变但 Base 变了。**
ARCH:275：Base/changed files/patch 变化时即使 head 不变也必须形成新 Review Identity。
同一 head 上若已有 `REQUEST_CHANGES`，补建查询会因为 fingerprint 变了而认为「当前上下文没有 Review」并建一条新的。
Decision Gate 本身仍然会挡住 APPROVE（D003：同 head 的 REQUEST_CHANGES 只能靠新 head 解除），
所以**直接后果不是放行合并**，而是：每次 Base 变动都自动烧一次 Review，且 UI 上出现一条「看起来是新一轮、实际没人要求过」的审查。
**但这条依赖 Decision Gate 实现正确**——如果 Gate 同时写错（把 Gate 认成四元组而不是 `pull_request_id + head_sha`），
补建 + 错 Gate 合起来就是「新建一条干净 Review 然后 APPROVE 掉一个已被打回的 head」。补建是那条链上的第一环。

**反例 C——启动风暴。**
补建查询在「Review 表为空」时对**每一个** PR 都返回一行（实测 `rowsWithNoReviewAtAll = [1]`）。
一次数据库恢复、一次手工清理、或第一次上线，都会让 scheduler 对全库 PR 发起 Review。
恢复形状在同一状态下返回 `[]`。

### 4.4 与「失败重试」的边界

ARCH:296 的 `FAILED → PENDING` 是**人工重试复用同一行**，不是 reconciliation 的活。
所以恢复查询的 `status` 集合是 `{PENDING, RUNNING}`，**不含 `FAILED`**（含了就等于自动重试，违反「不设无产品依据的兜底」）。
`COMPLETED` 永不重跑（ARCH:303）。

---

## 5. Q4：停滞（stalled）如何判定

### 5.1 lease 还是心跳：在单节点 4 GB 上，lease 更简单且不新增表

- **心跳**需要一张（或一列 + 一个高频写入路径）承载「worker 还活着」的事实。ARCH:107 已把「执行恢复不另建任务表」写死，
  16 表清单里没有心跳表，第 17 张表要走 §2.1 的门槛。
- **lease 是 `review` 行上的一列**（ARCH:102 已列出 `execution_attempt/token/lease`），零新增结构。
- **worker 续租就是心跳**：ARCH:301 已经要求「完成、失败、**续租**」三条都匹配 token，
  所以续租动作本来就要实现，把它当心跳用不产生任何额外机制。

**结论：lease 过期即停滞，续租频率就是心跳频率。不需要第二种判据。**

### 5.2 实测：条件领取对过期/未过期的行为

```text
PROBE|Q4-LEASE.lease.claimExpired.rowsAffected|1
PROBE|Q4-LEASE.lease.claimLive.rowsAffected|0
PROBE|Q4-LEASE.lease.finalRow|{status=RUNNING, token=new, attempt=2}
```

用的就是单条原子条件更新：

```sql
update probe_lease set status='RUNNING', attempt = attempt + 1, token='new',
       lease_until = now() + interval '5 minutes'
 where id = ? and (status='PENDING' or (status='RUNNING' and lease_until < now()));
```

### 5.3 实测陷阱：`now()` 在一个事务里是**冻结**的

```text
PROBE|Q4-CLOCK.clock.a.now  |2026-08-21 18:37:05.993963
PROBE|Q4-CLOCK.clock.a.clock|2026-08-21 18:37:05.997352
（中间 pg_sleep(1)）
PROBE|Q4-CLOCK.clock.b.now  |2026-08-21 18:37:05.993963   ← 一模一样
PROBE|Q4-CLOCK.clock.b.clock|2026-08-21 18:37:07.027534   ← 走了 1.03 s
```

后果分两面：

- **判过期**用 `now()` 是**保守**的（参照时刻更早 → 判出的过期行更少 → 不会误抢活着的 worker）。安全。
- **写 `lease_until = now() + interval`** 会**少续**一个「事务已运行时长」。若续租发生在一个长事务里（比如与写 Finding 同一个事务），
  lease 实际到期会比预期早，可能被 reconciliation 抢走。**续租应当用 `clock_timestamp()`，或续租单独短事务。**
  这条是 §7 必须裁定的项。

### 5.4 触发方式：`@Scheduled`，并且它不是被禁止的「兜底分支」

**实测：当前 production context 里连 scheduler 都没有**：

```text
PROBE|DEFAULTS.scheduledAnnotationBeanPostProcessorPresent|0
（taskSchedulerBean.* 一条都没打印 → 零个 TaskScheduler bean）
PROBE|DEFAULTS.property.spring.task.scheduling.pool.size|null
```

加 `@EnableScheduling`（来自 `spring-context`，**已在依赖树内，pom 零改动**）之后：

```text
PROBE2|E-SCHEDULED.scheduler.taskScheduler|org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler poolSize=1
PROBE|Q4-SCHEDULED.scheduled.threadName|scheduling-1
PROBE|Q4-SCHEDULED.scheduled.ticks|20
```

**实测 `fixedDelay` 不会自己叠自己**（方法体 sleep 300 ms、间隔 50 ms、跑 53 次）：

```text
PROBE2|E-SCHEDULED.scheduled.runs|53
PROBE2|E-SCHEDULED.scheduled.maxConcurrentEntries|1
PROBE2|E-SCHEDULED.scheduled.threadNames|[scheduling-1]
```

**为什么它不属于「无产品依据的重试/兜底」**（这是**论证**，不是实测）：

1. **它不是错误处理分支，而是一个状态的唯一所有者。** §3.1–§3.4 实测证明：调度失败（抛异常或执行器拒绝）
   **对任何调用方都不可见**，webhook 返回 202，PENDING 已提交。没有 reconciliation 时，
   状态机里的 `PENDING` 就是一个**有入边、无出边**的状态——它不是「偶尔恢复一下更好」，而是状态机不完整。
2. **它由权威文档直接要求**，不是实现自作主张：ARCH:263、ARCH:265、ARCH:303、D008、PLAN:71 五处都写了它。
3. **它不改变任何语义**：它只把行送回**同一条**领取/执行路径（ARCH:265「统一回到同一个领取/执行路径」），
   不产生第二套 Pipeline，不新建任何行，不做重试次数管理（`FAILED` 不在它的集合里，见 §4.4）。
4. **它没有隐藏失败**：被它捡起的行仍然走 `execution_attempt` 递增，审计列上留痕。

反过来，被禁止的「兜底」长这样：catch 住异常后改走另一条路径、静默降级、或凭空补一条数据让流程看起来通了。
reconciliation 三条都不沾——**只要它遵守 §4.1 那条 FROM 子句规则**。

`poolSize=1` 的后果（**推理**）：reconciliation 与将来任何 `@Scheduled` 共用一个线程，会互相排队；
若 reconciliation 单次耗时长，其他定时任务会被推迟。单节点 4 GB 上这是可接受的，但需要知情。

---

## 6. Q5：调度执行器

### 6.1 classpath 上已有什么（零新增依赖）

```text
PROBE|DEFAULTS.executorBean.applicationTaskExecutor|org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor corePoolSize=8 maxPoolSize=2147483647 queueCapacity=2147483647
PROBE|DEFAULTS.taskExecutorBean.applicationTaskExecutor|（同一个 bean）
PROBE|DEFAULTS.asyncTaskExecutorBean.applicationTaskExecutor|（同一个 bean）
PROBE|DEFAULTS.property.spring.threads.virtual.enabled|null
PROBE|DEFAULTS.hikari.maximumPoolSize|5
```

- **有且只有一个** Executor bean：Boot 的 `applicationTaskExecutor`，`core=8`、`max` 与 `queue` 都是 `Integer.MAX_VALUE`。
  **直接复用 = 8 个并发 Review + 无界积压**，与 PLAN:75「冻结为 1 或 2」正面冲突。
- 虚拟线程未开（Java 21 支持，`spring.threads.virtual.enabled` 未设）。
- **Hikari 只有 5 条连接**，是并发 Review 的硬天花板：并发 2 会占用 2 条，Web 层只剩 3 条。
  这个数在 §7 的实测（PLAN:75 的 4 GB 最大预算 Review）里必须一起量。

### 6.2 冻结成 1 的实际行为：先排队，满了**拒绝**（不阻塞、不静默丢）

`core=1 / max=1 / queue=1`，提交三个长任务：

```text
PROBE|Q5-BOUNDED.bounded.afterTwoSubmits.activeCount|1
PROBE|Q5-BOUNDED.bounded.afterTwoSubmits.queueSize|1
PROBE|Q5-BOUNDED.bounded.thirdSubmit|org.springframework.core.task.TaskRejectedException: ExecutorService in active state did not accept task: ...
```

`ThreadPoolTaskExecutor` 默认拒绝策略是 abort（抛 `TaskRejectedException`），**不会静默吞掉任务**。
但结合 C1：这个异常在 AFTER_COMMIT 里抛出会被吞（§3.4 实测），**所以「不静默」只在日志里成立**。

### 6.3 陷阱：`maxPoolSize` 在默认队列下是废的

`core=1 / max=4 / queue=默认`，提交 8 个长任务：

```text
PROBE|Q5-DECEPTIVE.deceptive.queueCapacityDefault|2147483647
PROBE|Q5-DECEPTIVE.deceptive.poolSizeAfterEightSubmits|1
PROBE|Q5-DECEPTIVE.deceptive.activeCount|1
PROBE|Q5-DECEPTIVE.deceptive.queueSize|7
```

`ThreadPoolExecutor` 只有在队列**满了**之后才扩到 `maxPoolSize`；默认队列无界 → 永远不满 → 永远只有 `corePoolSize` 个线程。

> **PLAN:75「据实把并发 Review 冻结为 1 或 2」必须落在 `corePoolSize` 上，并且必须同时显式设 `queueCapacity`。**
> 只写 `setMaxPoolSize(2)` 是一个**看起来正确、实测无效**的写法，而且任何「跑一个 Review 能过」的测试都会给它报绿。
> 这正是 prd.md §7 风险 3 说的「写一条容易过的断言就报绿」的一个具体实例。

### 6.4 声明自己的 Executor 会让 Boot 的默认 Executor 整个消失

```text
PROBE4|applicationTaskExecutorPresent|false
PROBE4|beanNamesForExecutor|reviewExecutor
PROBE4|executorBean.reviewExecutor|ThreadPoolTaskExecutor core=1 max=1 queue=16
```

只要上下文里存在**任何** `Executor` 类型的 bean，Boot 的 `applicationTaskExecutor` 就不再创建。
后果（**推理，未实测**）：Spring MVC 的异步请求处理（`Callable` / `DeferredResult` / SSE）会失去它原本的执行器。
ForgePilot 目前不用 MVC async，所以**当前无实害**，但这是一个「以后加 SSE 时会突然踩到」的雷。
两种规避方向（**均未实测**）：把执行器包在一个不实现 `Executor` 的持有类里；或显式再声明一个 `applicationTaskExecutor`。
**这条建议在 design.md 里明确处理，而不是让它默默发生。**

### 6.5 `@Async` 可用，但不建议直接用在 `ReviewService` 上

实测（`@EnableAsync` + `@Async("probeExecutor")`）：

```text
PROBE|Q5-ASYNC.async.threadName|probe-review-1
```

确实跑在指定执行器上。但**推理**层面有两个理由更倾向于在 after-commit callback 里显式 `executor.execute(...)`：
一是 `@Async` 把「提交任务」这个动作藏进代理，AFTER_COMMIT 里到底提交没提交、被不被拒绝，都更难断言；
二是 `@Async` 方法抛出的异常走的是 `AsyncUncaughtExceptionHandler`，又多一条静默路径。
**这是偏好，不是实测结论，设计阶段可以推翻。**

### 6.6 「提交前不得有 Worker 读取该 Review」的直接实测

事务内就把任务丢给执行器（错误写法）：

```text
PROBE|Q5-EARLY.earlyWorker.threadName|probe-review-1
PROBE|Q5-EARLY.earlyWorker.rowsVisibleToWorker|0    ← worker 看不见那一行
PROBE|Q5-EARLY.earlyWorker.rowsAfterCommit|1
```

AFTER_COMMIT 才丢（正确写法）：

```text
PROBE|Q5-LATE.lateWorker.rowsVisibleToWorker|1
```

**这是 ARCH:263「提交前不得有 Worker 读取该 Review」在本部署（READ COMMITTED）下的直接证据**：
提前调度的 worker 会查不到 Review 行——而它大概率会把这解释成「没活干」并安静退出，
于是 PENDING 永远留在库里。**这个 bug 在单元测试里几乎必然表现为偶发 flaky，不会稳定复现。**

---

## 7. 对设计的直接后果（可以直接抄进 design.md 的规则）

1. **两个方法，不是一个。** `review` 侧至少两个监听方法：`@EventListener`（建 PENDING，参加同一事务）
   与 `@TransactionalEventListener(AFTER_COMMIT)`（只做一次 `executor.execute(...)`）。
   **禁止把两个注解写在同一个方法上**（§2.2 实测会静默只剩后者）。
2. **AFTER_COMMIT 方法体内禁止任何数据库写。** 需要写就必须 `REQUIRES_NEW`（§2.6），
   且**绝不能用裸 `JdbcTemplate`**（§2.5：会在 autoCommit 连接上单语句提交，无法回滚）。
3. **AFTER_COMMIT 方法体内禁止任何阻塞。** §3.5 实测 webhook 的 202 会等它返回。
4. **不得用 `isActualTransactionActive()` 或 `isConnectionTransactional()` 判断阶段**（§2.3、§2.5 两者在 AFTER_COMMIT 都撒谎）。
5. **reconciliation 的 SQL 只准以 `review` 为驱动表、只准 UPDATE、不准 INSERT**（§4.1），
   状态集合 `{PENDING 超时, RUNNING lease 过期}`，**不含 `FAILED`、不含 `COMPLETED`**（§4.4）。
6. **并发上限写在 `corePoolSize`，并显式设 `queueCapacity`**（§6.3）。`maxPoolSize` 单独写是无效的。
7. **`@EnableScheduling` 是必须显式加的**（§5.4 实测当前 context 里零个 scheduler），`spring-context` 已在依赖内，pom 零改动。
8. **续租的时间来源要裁定**：`now()` 在事务内冻结（§5.3），续租用 `now()` 会少续。

### 7.2 必须在设计阶段裁定的开放项

| # | 开放项 | 为什么必须现在定 |
|---|---|---|
| **O1** | **调度失败要不要对 webhook 调用方可见？** `@TransactionalEventListener(AFTER_COMMIT)` = 永远 202 + 静默 + 全靠 reconciliation；手写 `TransactionSynchronization.afterCommit()` = 500 + GitHub 重投 + 仍需 reconciliation。 | 两条实测都成立（§3.3），默认行为是前者。不写死就等于**默认选了静默**。 |
| **O2** | **`lease_until` 用 `now()` 还是 `clock_timestamp()`？续租是否独立短事务？** | §5.3 实测 `now()` 在事务内冻结；写错会让活着的 worker 被抢。 |
| **O3** | **`queueCapacity` 定多少？满了以后怎么办？** 拒绝是实测行为（§6.2），被拒绝的 PENDING 只能等 reconciliation。 | 队列越小 → reconciliation 越吃重；队列越大 → 4 GB 机上积压越久。这个数应当和 PLAN:75 的实测一起冻结。 |
| **O4** | **reconciliation 的 `@Scheduled` 周期与 PENDING 超时阈值。** | 阈值必须显著大于「正常调度 + 排队」耗时，否则会把正在排队的 PENDING 当停滞抢走。§6.2 的 queueCapacity 直接决定这个下界。 |
| **O5** | **声明 `reviewExecutor` 会移除 `applicationTaskExecutor`（§6.4 实测）。接受、还是显式补回？** | 现在无实害；以后加 SSE 会突然踩到。 |
| **O6** | **人工触发 / 失败重试路径不经过事务时，AFTER_COMMIT 那一半根本不触发（§2.1 `Q1B` 实测）。** 三条触发路径如何真正共用 `requestReview(...)`？ | 这是 ARCH:263「三条最终共用一个入口」能否成立的实现前提。 |
| **O7** | **Hikari 只有 5 条连接（§6.1 实测）。** 并发 Review 冻结为 2 时，Web 层只剩 3 条。 | PLAN:75 的 4 GB 实测必须把连接池一起量进去，否则量出来的「并发 2 能跑」可能只是因为当时没有并发 Web 请求。 |

---

## 8. 未能测出 / 假设 / 清理自证

### 8.1 未能测出（如实记录，未用推理填空）

1. **没有在真实 4 GB 目标机上测。** 本轮全部在开发机的 Docker 里跑，`ThreadPoolTaskExecutor` 的行为与内存无关，
   但 PLAN:75 要求的「峰值、失败与降级行为」**本研究一个数字都没有产出**，那是另一件事。
2. **没有测多线程并发下的 lease 抢占。** §5.2 是单线程顺序执行的两条 UPDATE，只证明条件更新的行数语义，
   **不证明**两个 worker 并发领取时恰好一个成功。该缺口由同批次的
   `research/fencing-and-concurrency-measured.md` 覆盖（它用两条持久 `psql` 连接做交错实测）；
   本文的任何结论都**不得**被当作并发领取的证据。
3. **没有测 `review` 真实表上的行为。** §4.2 用的是同构的一次性表（`review` 表批次 3 才建），
   查询形状的对照结论成立，但**没有**验证真实 schema 上的索引与执行计划。
4. **没有测「把执行器包进非 Executor 持有类能否保住 `applicationTaskExecutor`」**（§6.4 的两个规避方向都是推理）。
5. **没有测 `@Async` 抛异常时的完整路径**（§6.5 的偏好是推理，不是实测）。
6. **没有测 Spring 7 的 AFTER_COMMIT 语义在 Boot 4.1.x 后续小版本里是否稳定。** C1 是本批次最重要的实测结论，
   但它依赖 `TransactionSynchronizationUtils.invokeAfterCompletion` 的 catch-and-log 实现，
   这是框架内部行为，**升级 Spring 可能改变它**。建议为 C1 留一条集成测试（断言「AFTER_COMMIT 抛异常时调用方不受影响且 PENDING 仍在」），
   否则将来行为翻转时没人会发现。

### 8.2 假设

- 假设 Phase 6 的 `review` 表会带 `status / execution_attempt / execution_token / lease_until / created_at`，
  §4.2 的恢复查询形状按此写。若列名不同，形状不变。
- 假设部署仍是单节点、单 JVM（ARCH:449 排除了分布式锁与 MQ）。多节点下 `@Scheduled` 会在每个节点各跑一份，
  届时 lease 的原子领取是唯一防重手段——本研究**没有**测这一点。
- 假设隔离级别保持 READ COMMITTED（实测启动日志确认为默认值）。§6.6 的结论依赖它。

### 8.3 与既有代码的关系（未改动任何生产代码）

- `backend/src/main/java/com/forgepilot/scm/PullRequestChanged.java` 的类注释目前写着
  「`@TransactionalEventListener` **是错误工具、是被禁止的**」。
  按本研究，那句话对**事务内建 PENDING 那一半**完全正确，但对**提交后调度那一半**是必须用的工具。
  批次 3 落地时这段注释需要补一句区分（**这是提示，不是我做的改动**——我没有改任何生产文件）。
- `GitHubWebhookIngestionTest.aFailingListenerRollsTheWholeIngestionBack` 证明的是事务内那一半；
  它**不能**推广到 after-commit 那一半（§3.3 实测两者行为完全相反）。批次 3 需要一条**独立**的断言。

### 8.4 清理自证

```bash
find backend/src/test -type f | sort | diff /root/.claude/jobs/e84ffece/tmp/tests-before.txt -
# → 无差异（"TEST TREE CLEAN"）
git status --short
# → 空
```

六个探针类、`backend/target` 下的编译产物与 surefire 报告均已删除；
`probe_lease`、`probe_pr`、`probe_review` 三张一次性表在各自测试内 `drop`，且只存在于 Testcontainers 的临时数据库里。
本研究只写入了本文件一个路径。
