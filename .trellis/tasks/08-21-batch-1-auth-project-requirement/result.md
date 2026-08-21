# 批次 1 结果

任务：`08-21-batch-1-auth-project-requirement`（Phase 2 + Phase 3）。
授权依据：[D012](../../../docs/v2/DECISIONS.md#d012)。实现裁定：[D013](../../../docs/v2/DECISIONS.md#d013)。

本文只记录**实际发生的事实**：真实执行过的命令、真实输出、真实偏差。计划见 `implement.md`，验收清单见 `validation.md`。

## 1. 完成情况

| 项 | 状态 |
|---|---|
| 迁移 V2/V3 与六个实体（闸门 A） | 完成 |
| Auth 切片（注册/登录/登出/改密/CSRF/session_version） | 完成 |
| Project + Member 切片（隔离、角色、LEADER 转移、SCM 身份） | 完成 |
| Requirement 切片（三步回填、Revision 冻结、`ac_key`、状态机） | 完成 |
| ArchUnit 加固至七条 | 完成 |
| 前端五个界面与登录态处理 | 完成 |
| 跨切片 HTTP 闭环测试 | 完成 |
| Compose 空库冷启动 | 见 §4 |
| CI 四个 job | 见 §4 |

未完成、且**有意**不做的，见 §6 边界。

## 2. 代理分工与文件范围

主会话（我）自己写的部分，是整批最贵的地基与安全边界：两条迁移、六个实体、`common` 错误契约、
`auth` 只读账户目录、**整个 project 切片**、ArchUnit 两条新规则与反证 fixture、跨切片 HTTP 闭环测试、
compose smoke 断言、以及全部规划与 spec 文档。

| 代理 | 文件范围 | 结果 |
|---|---|---|
| auth 切片 | `backend/**/com/forgepilot/auth/**` | 7 个主类 + 2 个测试类，未越界 |
| requirement 切片 | `backend/**/com/forgepilot/requirement/**` | 10 个主类 + 1 个测试类，未越界 |
| 前端 | `frontend/**` | 13 个新文件 + 8 个修改，未越界，未新增依赖 |

三个代理文件范围零重叠，同时运行，均被明确禁止派子代理、提交、推送、reset、checkout、删除未授权文件。

**我没有采信任何代理的自述结论。** 三份代码全部由我用真实构建、`git status`、以及针对性 grep 复核；
其中一条代理自述与磁盘事实不符（自述测试类名 `ActuatorProbeTest`，实际文件为 `ActuatorExposureTest.java`），
以磁盘为准。

## 3. 关键实现形态（与决策的对应）

- **[D013.1](../../../docs/v2/DECISIONS.md#d013) 变体 A 成立**：`Requirement.currentRevision` 用三列复合外键做只读导航，
  `@JoinColumn` 全部 `insertable=false, updatable=false`，写入走标量 `currentRevisionId`。
  `ddl-auto: validate` 在应用启动期通过，这是闸门 A 要求的**最早信号**，第一次构建即通过。
- **[D013.10](../../../docs/v2/DECISIONS.md#d013) 三步回填成立**：`current_revision_id` 为 NULL 时 `MATCH SIMPLE` 跳过整条复合键，
  回填时三列俱全才真正校验。指向别的需求或别的项目的 revision 被 `23503` 拒绝，有测试。
- **[D013.8](../../../docs/v2/DECISIONS.md#d013) LEADER 转移**：降级 → `flush()` → 升级，禁止单条 CASE 交换。
  **实现时发现并修正了一处授权顺序缺陷**：原本先查角色再锁 `project` 行，导致并发转移的失败方
  在自己已经不是 LEADER 之后仍能继续操作（基于陈旧读）。改为**先锁行、再查角色**，失败方重读时
  已被降级，直接 403。这同时让 AC3「并发转移只有一个成功」在语义上真正成立。
- **[D013.11](../../../docs/v2/DECISIONS.md#d013)**：全仓库 grep 确认没有任何一处捕获
  `DataIntegrityViolation`/`ConstraintViolation`/`PersistenceException`/`SQLException`。
- **404 与 403 的信息流**：非成员一律 404（与「id 不存在」不可区分），成员但角色不足才 403。
  由 `BatchOneApiTest.anotherProjectsIdsAreInvisibleOverHttp` 在 HTTP 层断言。

### 规划期补入的三项契约（均已写入 `api-contract.md`）

1. **`POST /api/auth/register` 与 `POST /api/auth/password`**。AC2 要求「可注册或由种子数据获得账户」且
   「改密后其它会话失效」，而 `database-guidelines.md` 不允许迁移放种子行——注册接口是不新增结构的解，
   也避免把口令写进仓库。
2. **业务模块如何拿到 `userId`**。`ARCHITECTURE.md` §1.3 禁止业务模块依赖 auth，但成员/需求都需要身份。
   定型为：Controller 收 JDK 的 `java.security.Principal`，经 [D013.6](../../../docs/v2/DECISIONS.md#d013) 明确放行的只读
   `UserDirectory.byUsername` 换 id。业务模块因此既不 import Spring Security，也不碰认证机制。
   代价是每请求多一次唯一索引查询，MVP 接受。
3. **409 与 422 的分界**。只看请求体可判定的拒绝是 422，必须读资源当前状态才能判定的是 409。
   由此「对已冻结需求原地编辑」是 409，「非法状态转换」是 422。前端两者都按「操作被拒绝」处理。

另外批准了 requirement 切片提出的一条守卫：**指派只在 `READY` / `IN_DEVELOPMENT` 开放**。
没有它，「先给 DRAFT 指派、再置 READY」会让 `IN_DEVELOPMENT` 只能靠换人进入，与「首次指派同事务进入」矛盾。
已写入契约。

## 4. 实际执行的命令与结果

### 后端

宿主无 JDK，构建走容器（`backend/README.md` 的命令），并用 `flock` 串行化以免与并行代理的构建互撞：

```bash
cd /root/ForgePilot/backend && flock /root/.claude/jobs/e84ffece/tmp/maven.lock \
  docker run --rm --network host -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" \
  -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

`BUILD SUCCESS`，**Tests run: 59, Failures: 0, Errors: 0, Skipped: 0**：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `DatabaseConstraintTest` | 16 | 约束由数据库拒绝（绕过 Service 直写，断言真实 SQLState） |
| `requirement.RequirementLifecycleTest` | 11 | Revision 冻结、`ac_key`、状态机、越权 |
| `project.ProjectAuthorizationTest` | 10 | 隔离、角色矩阵、恰一 LEADER、并发转移、SCM 唯一 |
| `ArchitectureRulesTest` | 8 | 七条规则 + 两组反证 fixture |
| `auth.AuthApiTest` | 6 | 登录/登出/改密/CSRF/口令不回显 |
| `BatchOneApiTest` | 4 | **跨切片 HTTP 闭环**、跨项目不可见、CSRF 缺失、匿名错误体 |
| `FoundationDatabaseTest` | 3 | 扩展、迁移历史、恰好 6 张业务表 |
| `auth.ActuatorExposureTest` | 1 | `/actuator/metrics` 真容器下仍是 404 而非 401 |

`BatchOneApiTest` 值得单独说：它是三个独立编写的切片第一次拼在一起跑真实 HTTP，
**第一次运行即全绿**——注册、登录、建项目、加成员配 SCM 身份、写需求与 AC、置 READY、指派、
发新 Revision、看版本历史，以及 CSRF cookie/header 往返。这是「契约先行」有效的直接证据。

### 前端

```bash
cd /root/ForgePilot/frontend && npm run lint && npm run typecheck && npm run test -- --run && npm run build
```

四条全部退出码 0。`Test Files 5 passed (5), Tests 15 passed (15)`；
构建 52 modules，`index-DB71gMCW.js 117.18 kB`（gzip 42.93 kB）。
`git diff --stat frontend/package.json frontend/package-lock.json` 为空——**未新增任何依赖**。
一级导航仍严格三项（`项目` / `研发需求` / `代码审查`）。

### 复核用的针对性检查

```bash
grep -rn "catch.*\(DataIntegrity\|ConstraintViolation\|PersistenceException\|SQLException\)"   # 无
grep -rniE "retry|fallback|@Recover"                                                           # 无
grep -rn "Logger\|slf4j" | grep -v "^./common/"                                                # 仅 common
find . -mindepth 2 -type d                                                                     # 无子包
find . -mindepth 1 -maxdepth 1 -type d                                                         # 恰好 8 个顶层包
```

`project` / `requirement` 均未注入 `UserAccountRepository`，`requirement` 未注入 `ProjectMemberRepository`
（仅在 javadoc 中作为「不注入」的说明出现）。

### Compose 与 CI

```bash
scripts/phase1-compose-smoke.sh forgepilot-phase1-batch1-<epoch>
```

退出码 0：`Compose smoke passed ... (pgvector 0.8.6, 6 application tables)`。
空卷冷启动，Postgres → 健康检查 → backend → frontend 全部起来，Flyway 三条迁移成功，
`public` 下恰好是预期的六张表，`/actuator/metrics` 在真容器下仍是 **404**（不是 401），
结束后容器、卷、网络全部清理干净。

两处本次修正：

- 原断言是「业务表数为 0」，那是 Phase 1 的形态，本批次必然使其失败。改为**逐名比对六张表**，
  比只比数量更强：多出一张计划外的表也会被挡下。同时新增「Flyway 无失败迁移」的断言。
- 首次运行时收尾那行打印「5 application tables」。断言本身比对的是完整表名串、确实通过了，
  是我那句统计用 `tr ',' '\n' | wc -l` 少算了最后一个无换行的字段。已改为 `awk -F, '{print NF}'`
  并单独验证输出为 6。**一份说 5 的报告正是日后会误导人的东西，所以没有留着。**

Spring Security 接入后有一处曾会打破 smoke 的坑，已在实现期拦下：若只放行 `/actuator/health`，
未暴露的 `/actuator/metrics` 会返回 401 而不是 404，smoke 与 CI 都会挂。放行整个 `/actuator/**`
才是对的——只有 `health` 被 `application.yml` 暴露，其余本就不存在，而 smoke 的 404 断言正是这一点的守卫。
另有一条容器级细节：404 要送达客户端需要一次 ERROR dispatch 到 `/error`，该 dispatch 会被重新鉴权，
因此必须放行 `DispatcherType.ERROR`；客户端无法自行触发它，因为鉴权入口用 `setStatus` 而非 `sendError`。

## 5. Legacy 使用依据

本批次**未复制任何 Legacy 代码**。`LEGACY-MIGRATION-MATRIX.md` 中与本批次相关的条目按 REWRITE/REFERENCE 处理：
认证的「失败不区分用户不存在与密码错误」是 REWRITE 要点，按该要点重新实现；
Legacy 的私有 Token 协议是 REFERENCE，按 [D013.7](../../../docs/v2/DECISIONS.md#d013) 明确不继承，改用框架自带 `HttpSession` + CSRF cookie repository。

## 6. 边界（有意不做）

- 无 Knowledge / AI / SCM / Review / Finding 相关代码或表；无向量索引、无维度绑定。
- 无需求状态审计表——[D013.3](../../../docs/v2/DECISIONS.md#d013) 明确列为 MVP 缺口，需正式决策才能新增第 17 张表。
- 迁移中无 `ON DELETE`：`ARCHITECTURE.md` §2.3 只为 `pull_request.author_user_id` 规定了删除语义，
  其余一律让外键挡住硬删，直到某个 Phase 回答「删除意味着什么」。
- 不提供项目/成员/需求的硬删除接口；成员移出项目需先回答 `requirement.assignee` 的处置，属批次 2 之前的开放项。
- `project.status` 的 `ARCHIVED` 只建列不实现转换；`quality_json` 三列只建列并实现「DRAFT 修改时同事务清空」。
- 未新增第 17 张表、未新增顶层包、未新增一级菜单、未新增前端依赖。

## 7. 风险与已知缺口

1. **进程重启会话即失效**（[D013.7](../../../docs/v2/DECISIONS.md#d013) 已接受的代价，须写进部署说明）。单节点部署下无 Redis、无 session 表。
2. **`session_version` 每个已认证请求一次主键查询**，外加 `Principal → userId` 一次唯一索引查询。
   两次都是索引命中，MVP 规模无碍；有缓存的诱惑但故意不加——陈旧缓存会让被撤销的会话继续存活。
3. **禁用账户不会踢掉已存在的会话**：`session_version` 只在改密时递增，`enabled` 只在登录时检查。
   本批次没有禁用账户的接口，因此不可达；若批次 2 加该接口，必须同时递增 `session_version`。
4. **`ac_key` 退休编号有一处窄缺口**：DRAFT 期原地编辑会硬删被移除的 AC 行，若删掉的恰是编号最大的一条，
   该编号会被重新发放。需求一旦离开 DRAFT 就不再删任何 AC 行，跨 Revision 身份稳定。
   彻底堵住需要持久化已退休编号，即新增列或新增表，本批次禁止。
5. **需求正文无乐观锁**：两个并发发布由 `uq_requirement_revision_requirement_seq` 串行化（失败方 409），
   两个并发 DRAFT 编辑是后写覆盖。超出本批次范围。
6. **smoke 脚本名与内容已错位**：文件仍叫 `phase1-compose-smoke.sh`、仍要求项目名以 `forgepilot-phase1-` 开头，
   但断言的已是批次 1 的六张表。没有改名是因为 CI 引用该路径；属可择日清理的命名债，不影响正确性。

## 8. 回滚

按文件组独立回滚：迁移+实体、auth、project、requirement、ArchUnit、前端各自成组。
数据库回滚等价于重建空库（本批次尚无生产数据，且 `clean-disabled: true`，回滚靠丢卷而非 `flyway clean`）。
`V2`/`V3` 一旦被任何环境应用过就不得编辑或重编号——只能追加 `V4`。

## 9. 批次 2 的前置条件

1. 成员移出项目的语义（`requirement.assignee` 如何处置）必须先有决策，才能实现成员删除。
2. 若引入禁用账户，必须同时递增 `session_version`（见 §7.3）。
3. `scm` 切片落地时，`project_member.scm_external_user_id` 已是现成的授权键；`scm_username` 永远只做展示。
4. 复合外键关联一律沿用 [D013.1](../../../docs/v2/DECISIONS.md#d013) 变体 A，已写入 `.trellis/spec/backend/database-guidelines.md`。
5. 新增的每一条迁移都必须带一条「断言约束真的拒绝」的集成测试，而不是只断言表存在。
