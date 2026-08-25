# Result — Removal semantics for knowledge documents, project members, and canceled requirements

任务：`08-24-resource-removal-semantics`（T-005 / T-006 / T-007，D022，V10）
完成日期：2026-08-25
验收结论：**自动化、迁移与部署部分通过；AC18 的真实浏览器闭环待人工执行**（下文如实区分）

## 交付

| 层 | 内容 |
|---|---|
| 迁移 | `V10__resource_removal.sql`：`requirement.deleted_at` / `deleted_by` + FK + `ck_requirement_deleted_shape`；新表 `project_deletion_record`。业务表 19 → **20**，Flyway 9 → 10 |
| 留痕 | `project` 新增 `DeletedResourceType` / `ProjectDeletionRecord` / 仓库 / `ProjectDeletionLog`（`Propagation.MANDATORY`，把「必须与删除同事务」变成运行时事实） |
| T-005 | `KnowledgeService.deleteProjectKnowledge`：硬删 + 同事务显式删 chunk；附件文档 409。`DELETE /knowledge/documents/{id}` → 204。**批量上传零后端改动** |
| T-006 | `ProjectMemberRemoving` 收集型事件 + `requirement` / `review` / `scm` 三个监听器；`ProjectMemberService.remove` 八步顺序（行锁 → 授权 → 取目标 → 拒绝 LEADER → 发事件 → 删行 → flush → 留痕）。`DELETE /members/{userId}` → 204 |
| T-007 | `Requirement.markDeleted` + 两个 `...AndDeletedAtIsNull` 派生查询；七处取值口逐处替换。`DELETE /requirements/{id}` → 204 |
| 前端 | 知识页多文件上传 + 逐文件结果 + 删除入口；成员表移除入口；作废需求详情删除入口；三个 `api.ts` 各加一个 DELETE |
| 文档 | D022；ARCHITECTURE §2.1（20 表 + 新表行 + 不建清单例外）与 §2.3（删除语义表）；API / README / PRD / AGENTS / CLAUDE / TEST-ISSUES / FULL-CHAIN-UI-TEST / MANUAL-ACCEPTANCE；`.trellis/spec/backend/` 两份 |

## 自动化证据

```
后端 ./mvnw -B -ntp verify → 331 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS  （此前 323）
前端 lint / typecheck / test --run / build → 全部通过，37 tests 零跳过
```

本机无 JDK，后端全程走 `DEFENSE-GUIDE.md:27` 的固定容器路径（`eclipse-temurin:21-jdk`）。

新增 8 条测试，每条锁一个不变量：

| 测试 | 锁住的不变量 |
|---|---|
| `V10MigrationTest` | `ck_requirement_deleted_shape` 在**非空** `requirement` 表上的升级路径。`ADD CONSTRAINT CHECK` 会立即校验既有行，而 `FoundationDatabaseTest` 跑空库看不见这类失败 |
| `FoundationDatabaseTest`（改） | 十条迁移 + **二十**张具名表；它刻意按名字比对，多一张计划外的表必须失败 |
| `ResourceRemovalTest` 全链路 | 三个模块的引用全部撤销 **且** 既成事实与审计一字不动。任一监听器缺失或顺序颠倒都在这里炸 |
| `ResourceRemovalTest` 唯一 LEADER | 「至少一个 LEADER」是服务端职责，没有任何约束表达它 |
| `ResourceRemovalTest` 需求软删 | 只有 CANCELED 可删；删后从列表与详情消失；`ai_call_log` 未被销毁 |
| `ResourceRemovalTest` 词表贯通 | `ck_project_deletion_record_resource_type` 与 `DeletedResourceType` 不得分叉（走完整个 enum） |
| `KnowledgeServiceTest` 删除 + 检索 | chunk 随文档消亡，**且删除后检索不再召回**——AC4 在实现侧一行代码都没写，正确性完全依赖这一条 |
| `KnowledgeServiceTest` 附件拒绝 / 授权 | 附件文档 409 而不是数据库报错；非 LEADER 403、跨项目 404 |

**刻意未写**：三个端点的 getter/序列化、`detail` 字符串措辞、留痕表各列可空性单测、逐端点重复的幂等与 403 矩阵（授权走同一个 `ProjectAccessService`）。

## 真实部署验证

迁移前备份：`/root/fp-demo-pre-v10-20260825.sql`（417K）。只重建 `backend` 与 `frontend`；**postgres 未重建，数据卷未触碰**（该容器已连续运行 27 小时）。

| 项 | 结果 |
|---|---|
| Flyway | 9 → 10，349ms，`success = t` |
| Hibernate `ddl-auto: validate` | 通过（backend 容器 healthy 即证明：两个新列与新表若与实体不符，启动就会失败） |
| 表数 | `public` 20 张业务表 |
| 既有数据 | 8 条 finding、1 条需求、6 名成员、4 份知识文档全部保留；`deleted_at` 非空计数为 **0**（没有任何行被追溯标记为已删） |
| 留痕表 | 0 行——新表启动时是空的，没有被回填任何猜测 |
| 服务 | 三容器 healthy；`/actuator/health` 200；前端 200 |
| 已部署 bundle | `/assets/index-D01cmds3.js` 含「删除该作废需求」「移除成员」「删除文档」「可一次选择多个文件」四段新文案 |

## 逐条验收

| AC | 结论 | 依据 |
|---|---|---|
| AC1 多文件上传逐文件报告，单个失败不阻断 | 通过（实现）／浏览器确认待人工 | 前端逐文件循环，每次调用后端各自一个事务 |
| AC2 文档可删，chunk 与 embedding 按级联处理，无悬空 | 通过 | `KnowledgeServiceTest`；`knowledge_chunk` 的 FK 无 `ON DELETE`，悬空会直接 23503 |
| AC3 附件文档行为可预期而非数据库报错 | 通过 | 409 + 指出是哪条需求 |
| AC4 已删文档不再出现在任何检索结果 | 通过 | `KnowledgeServiceTest` 直接断言 `search` 结果由 2 变 1 |
| AC5 移除后角色、需求指派、Finding 认领、SCM 绑定全部失效 | 通过 | `ResourceRemovalTest` 四项逐一断言 |
| AC6 作者快照、PR 关联事件、Finding 血缘与审计仍完整 | 通过 | 同上；`author_user_id` 置空、两列快照原值不变 |
| AC7 指派与认领按既定策略处理并留痕 | 通过 | 留痕 detail 为 `roles: 1; requirement assignments: 1; finding assignments: 1; scm bindings: 1` |
| AC8 唯一 LEADER 无法被移除 | 通过 | 409，且转移后可移除 |
| AC9 平台账户与自有 SCM 身份不受影响 | 通过 | `scm_identity` 与 `user_account` 计数不变 |
| AC10 只有作废需求可删 | 通过 | 非作废 409 |
| AC11 删除需求后 `ai_call_log` 与 PR 关联事件未被销毁 | 通过 | 软删；`ai_call_log` 计数仍为 1 |
| AC12 三类删除授权在后端强制 | 通过 | 三处都是 `access.requireRole(..., LEADER)`；非 LEADER 403 有测试 |
| AC13 跨项目删除与枚举被拒绝 | 通过 | 跨项目与不存在同答 404（知识、成员各有断言） |
| AC14 重复删除返回明确结果 | 通过 | 三类均 404，无歧义状态 |
| AC15 三类删除均有留痕且不随被删对象消失 | 通过 | 留痕写 `project_deletion_record`，落在被删对象之外 |
| AC16 迁移仅追加，V1–V8 未改；空库与升级路径均通过 | 通过 | `V10MigrationTest`（非空升级）+ `FoundationDatabaseTest`（空库全量）+ 真实部署 9 → 10 |
| AC17 后端 verify 与前端四命令全绿零 skip | 通过 | 上文命令输出 |
| AC18 真实浏览器完成四步闭环 | **待人工执行** | 步骤已写入 `FULL-CHAIN-UI-TEST.md` §7（8 步）与 `MANUAL-ACCEPTANCE.md` §2 |
| AC19 v2 文档同步；评测资产未被触碰 | 通过 | `git status --porcelain -- evaluation/` 为空 |

AC18 记为待执行是口径问题而非缺陷：它要求在真实浏览器里串一遍四类操作，而本会话没有浏览器。**不把「代码能跑、测试全绿、部署成功」记成人工闭环通过。**

## 实施中发现的三处冲突，都正面处理了

这三条都不是实现细节，任一条含混过去都会留下真正的债。

### 1. 留痕表撞上 ARCHITECTURE §2.1 明令「不建」的 `audit_event`

§2.1 的不建清单写着「通用 `audit_event`（多态 entity_id 无法被 D006 复合外键约束）」，而 PRD 决策 4 要的正是一张带多态 `resource_id` 的表。

处理：**建，但把禁令的适用范围写清楚，并记为 D022 + 在 §2.1 就地补例外。** 那条理由在删除台账上不适用——被引对象按定义已经不存在（两类硬删，且 R5 明令留痕不得写在被删对象自身），所以不是「没加外键」，是没有可加外键的目标。能约束的两列都约束了（`project_id`、`actor_user_id`），`resource_type` 是三值封闭 CHECK 词表。拆三张窄表不会多出任何一个外键，只会把同一个问题抄三遍。§2.1 现在写明这是**唯一记录在案的例外**，且针对存活实体的多态审计表仍然不建。

### 2. `project` 依赖不到那三个要撤销引用的模块

§1.3 规定 `project` 只能依赖 `common`，§1.4 第 4 条还禁止跨 feature 注入对方 `*Repository`。而移除成员必须动 `requirement.assignee_id`、`finding.assignee_id` 与 `project_member_scm_binding`。

处理：**用既有的进程内事件反转方向**，不发明新机制——`project` 定义 `ProjectMemberRemoving`，三个模块各自 import 并监听，与 `scm` 发布 `PullRequestChanged` / `review` 监听是同一形状，连「类型定义在发布方」这一点都一样。附带一个好处：那三处引用的外键都没有 `ON DELETE`，所以**漏掉一个监听器不会静默通过**——外键本身就是「每一处引用都真的撤销了」的证明。

三个监听器起初都叫 `ProjectMemberRemovingListener`，Spring 的默认 bean 名取自简单类名，于是三个同名 bean 直接让上下文启动失败（`ConflictingBeanDefinitionException`）。改为按各自撤销的东西命名：`RemovedMemberAssignmentListener` / `RemovedMemberClaimListener` / `RemovedMemberBindingListener`。

### 3. `ReviewActivityRepository` 的两条 SQL 不能一视同仁地加过滤（design.md 已就地修正）

`requirementIds` 是活动概览要聚合的需求清单，必须过滤已删。但 `CURRENT_REVIEW_PER_PULL_REQUEST` 里的 `left join requirement` **不能**过滤：那次连接只是为了取 `current_revision_id` 来匹配「当前有效 Review」，过滤掉会让修订变成 NULL，从而改写判定。PR 自身的活动状态是 PR 的事实，与它的需求是否还在产品面无关。

## 写回 spec 的两条知识

1. `database-guidelines.md`：V10 的位置与表数（十条迁移 / 二十张表）；**删除台账的 `resource_id` 故意无外键**，加上去就会让它无法记录它存在的理由；以及 D022 如何在不加第二条 `ON DELETE` 的前提下定义三种删除语义，且未动的外键反而成了撤销完整性的证明。
2. `quality-guidelines.md`：表数检查命令**此前写的是十六**，自 V8 起就已过期。改成二十，并写明它跟着 ARCHITECTURE §2.1 走、需要两处同步更新，否则这条检查会静默失去意义。

## 一处如实说明

`git diff --check` 报 `docs/v2/TEST-ISSUES.md:3` 行尾空白。那是该文件既有的 Markdown 硬换行写法（第 3–5 行都是），不是本次新增的空白债；因为改动了那一行，它才被 `--check` 当作新增行报出来。保留与邻行一致的写法。

## 未做（明确边界）

向量索引（D019 非目标）；需求状态转换留痕（D013.3 接受的缺口，软删的两列打标不等于补上它）；`resource_id` 加外键；第二条 `ON DELETE`；down 迁移；离职归档、成员重新激活、删除项目/账号/PR/Review。正式评测的配置冻结、语料清单、holdout 台账与原始输出**全程未触碰、未重跑**。
