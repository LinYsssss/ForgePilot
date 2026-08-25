# 执行计划：三类资源的删除语义

顺序原则：**迁移 → 留痕基座 → 三条删除各自独立成组 → 前端 → 文档**。三条删除之间没有依赖，任一组失败可单独回滚而不牵连其他两组；但它们共用迁移与留痕，所以那两样必须先绿。

本机无 JDK。后端每一步都走固定容器路径（`docs/v2/DEFENSE-GUIDE.md:27`）：

```bash
docker run --rm --network host \
  -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

## 步骤

### 1. 迁移 V10 + 两个基础测试
- [ ] `V10__resource_removal.sql`：`requirement.deleted_at` / `deleted_by` + FK + `ck_requirement_deleted_shape`；`project_deletion_record` 表。SQL 注释写明 `resource_id` **故意**不带外键及其理由。
- [ ] `FoundationDatabaseTest`：`EXPECTED_TABLES` 加 `project_deletion_record`（19 → 20），`everyMigrationApplied` 加 `"10:resource removal"`。
- [ ] `V10MigrationTest`（照 `V9MigrationTest` 的形态）：迁到 V9 → 写一条 requirement → 迁完 → 断言该行仍合法且两列为空。
- 验证：`verify` 全绿。**这一步不绿，后面全部无意义**（`ddl-auto: validate` 会在实体加列后直接拦住启动）。

### 2. 留痕基座（`project`，被另外两个模块调用）
- [ ] `project/DeletedResourceType.java`：`KNOWLEDGE_DOCUMENT / PROJECT_MEMBER / REQUIREMENT`。
- [ ] `project/ProjectDeletionRecord.java` 实体 + `ProjectDeletionRecordRepository`。
- [ ] `project/ProjectDeletionLog.java`（`@Service`，公开方法 `record(projectId, type, resourceId, actorId, detail)`）——`knowledge` 与 `requirement` 经它写入，不注入 `project` 的仓库。
- [ ] 测试：`DeletedResourceType` 逐值贯通 CHECK 词表（走完整个 enum，不抽样）。
- 验证：`verify` 全绿。

### 3. T-005 知识文档删除
- [ ] `KnowledgeService.deleteProjectKnowledge(projectId, actorId, documentId)`：LEADER → 取文档（404）→ `REQUIREMENT_ATTACHMENT` 则 409 并指出 `source_requirement_id` → 删 chunk → 删文档 → 写留痕。
- [ ] `KnowledgeChunkRepository` 加按 `(projectId, documentId)` 删除的方法。
- [ ] `KnowledgeController`：`@DeleteMapping("/{documentId}")` + `204`。
- [ ] 测试两条：删除后 chunk 归零且文档消失；附件来源文档被 409 拒绝。
- [ ] 测试一条：已删文档不再出现在 `ChunkSearchRepository.search` 结果里（AC4 的直接证明）。
- 验证：`verify` 全绿。
- **回滚点**：本组只碰 `knowledge` 包 + 控制器，可单独 revert。

### 4. T-006 成员移除（最重的一组）
- [ ] `project/ProjectMemberRemoving.java`：收集型事件（可变累加器 + `summary()`），javadoc 写明「必须同步 `@EventListener`，禁止 `@TransactionalEventListener`」及可变为何安全。
- [ ] `requirement/ProjectMemberRemovingListener.java` + `RequirementRepository` 的 `@Modifying` 批量置空。
- [ ] `review/ProjectMemberRemovingListener.java` + `FindingRepository` 的 `@Modifying` 批量置空。
- [ ] `scm/ProjectMemberRemovingListener.java` + 绑定仓库的按 `(projectId, userId)` 删除。
- [ ] `ProjectMemberService.remove(projectId, actorId, targetUserId)`：按 `design.md` §3.2 的八步顺序，含 `findByIdForUpdate` 行锁、LEADER 拒绝、`flush()`、留痕。
- [ ] `ProjectMemberController`：`@DeleteMapping("/{userId}")` + `204`。
- [ ] 测试一条全链路：目标成员同时持有需求指派、Finding 指派、SCM 绑定与 PR 作者映射 → 移除后成员行与角色消失、两个 assignee 为 NULL、绑定消失、`author_user_id` 为 NULL 而两列作者快照不变、`finding_event` 与 `pull_request_requirement_event` 完好、留痕摘要含三类计数。
- [ ] 测试一条：唯一 LEADER 移除被 409 拒绝。
- 验证：`verify` 全绿。
- **回滚点**：四个新文件 + 两处改动，独立可 revert。

### 5. T-007 需求软删
- [ ] `Requirement` 实体加 `deletedAt` / `deletedBy` + `markDeleted(actorId)`。
- [ ] `RequirementRepository` 加两个 `...AndDeletedAtIsNull` 派生查询。
- [ ] 按 `design.md` §4.3 的表**逐处**替换取值口：`RequirementService.require`、列表、`RequirementDirectory.existsInProject`、`RequirementAttachmentService`、`ImplementationGuidanceService`、`RequirementQualityService`、`ReviewActivityRepository` 两条 SQL。删除路径本身保留未过滤的 finder。
- [ ] `RequirementService.delete(...)`：LEADER → 取（含已删）→ 已删 404 → 非 CANCELED 409 → 打标 → 留痕。
- [ ] `RequirementController`：`@DeleteMapping("/{requirementId}")` + `204`。
- [ ] 测试一条：只有 CANCELED 可删；删后列表与详情均消失；同需求的 `ai_call_log` 行未被销毁。
- 验证：`verify` 全绿。**这一步最容易漏改一处读取点**——逐条对着 §4.3 的表勾，别凭记忆。

### 6. 跨项目隔离
- [ ] 一条测试覆盖三个 DELETE：用另一个项目的 id 调用，答案与「不存在」不可区分（沿用 `BatchOneApiTest.anotherProjectsIdsAreInvisibleOverHttp` 的形态）。
- 验证：`verify` 全绿零 skip。

### 7. 前端
- [ ] `features/knowledge/api.ts`、`features/project/api.ts`、`features/requirement/api.ts` 各加一个 delete 函数。
- [ ] `KnowledgePage.vue`：`<input multiple>` + 逐文件顺序上传与逐文件结果；每份文档的删除按钮（LEADER），附件来源文档不给入口并说明原因。
- [ ] `ProjectMembersPage.vue`：成员表操作列加「移除成员」（LEADER，且不给 LEADER 自己那行）+ `window.confirm`。
- [ ] `RequirementDetailPage.vue`：作废 + LEADER 时给删除按钮，成功后回列表。
- 验证：`cd frontend && npm run lint && npm run typecheck && npm run test -- --run && npm run build`

### 8. 文档
- [ ] `docs/v2/DECISIONS.md` 新增 **D022**（四件事：三资源三策略、留痕表与 `audit_event` 禁令的边界、事件反转依赖、批量上传是纯前端）。
- [ ] `docs/v2/ARCHITECTURE.md`：§2.1 标题 19 → 20、新表一行、不建清单补例外并指向 D022、§2.3 删除语义。
- [ ] `docs/v2/API.md` 三个端点；`docs/v2/README.md` 表数与边界行；`docs/v2/PRD.md`；`docs/v2/TEST-ISSUES.md` 的 T-005..T-007 结论。
- [ ] `docs/v2/FULL-CHAIN-UI-TEST.md` + `frontend/MANUAL-ACCEPTANCE.md` 补人工闭环（AC18）。
- [ ] `.trellis/spec/backend/database-guidelines.md`：V10 与「留痕的 resource_id 故意无外键」。
- [ ] `.trellis/spec/backend/quality-guidelines.md`：表数检查命令 **16 → 20**（该数字自 V8 起就已过期）。

### 9. 最终全量检查 + 部署
- [ ] 后端 `verify` 全绿零 skip；前端四命令全绿零 skip。
- [ ] `docker compose -p fp-demo build backend frontend` + `up --detach --wait`。**绝不带 volume 参数**，postgres 不重建。
- [ ] 迁移前先 `pg_dump` 备份，形态同 V9（`/root/fp-demo-pre-v9-20260824.sql`）。
- [ ] 部署后核验：Flyway 9 → 10 成功、`ddl-auto: validate` 通过、既有数据完好、三容器 healthy。
- [ ] 逐条对照 AC1–AC19 写 `result.md`；AC18 的真实浏览器闭环如实记为待人工执行。

## 审查门

- 第 1 步后：`FoundationDatabaseTest` 必须因为表数与迁移条目而**先失败再修好**——否则说明它没在看真东西。
- 第 4 步后：确认三个 `RemovedMember*Listener` 都用 `@EventListener`。**注意 grep 全库不会为空**——`review/PullRequestChangedListener:65` 早就有一个刻意的 `@TransactionalEventListener(AFTER_COMMIT)`，那是给 `ReviewReady` 的另一条路径。要看的是这三个新文件里没有它。
- 第 5 步后：`grep -rn "findByProjectIdAndId(" backend/src/main/java/com/forgepilot/requirement` 逐处确认只有删除路径还在用未过滤版本。
- 第 9 步前：`grep -c 'create table' db/migration/*.sql` = 20；`git status` 确认 `evaluation/` 零改动。

## 明确不做

- 不建向量索引（D019 非目标）。
- 不加需求状态转换留痕（D013.3 接受的缺口），软删自己的 `deleted_at/deleted_by` 不等于补上它。
- 不写 down 迁移；不给 `resource_id` 加外键；不加第二条 `ON DELETE`。
- 不做离职归档、成员重新激活、删除项目/账号/PR/Review。
- 不碰或重跑正式评测资产。
