# P1a 成员与 3 角色 RBAC

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R1/A1,design §2)。
> 复杂子任务,但设计已在父 design §2 定稿,本文件承载差量细化,不另建 design.md。

## Goal

引入 `project_member` 表与 LEADER/DEVELOPER/REVIEWER 三角色;`ProjectAuthorization`
扩展式演进(保签名、扩语义、加 `requireRole`);成员管理端点 + 墨境前端;
**单人项目行为零回归**(owner 即 LEADER,存量语义不变)。

## Requirements

**数据(V29):**

- R1 `project_member(id, project_id fk cascade, user_id, role, created_at)`,
  `unique(project_id, user_id)`,role check in ('LEADER','DEVELOPER','REVIEWER');
  回填:每个 project 的 owner 插 LEADER 行(幂等)。
- R2 项目创建时同事务写入 owner 的 LEADER 行;`ProjectCleanupService.purgeProjectData`
  增删成员行(H2 dev 无 FK 级联,必须应用层删)。

**授权语义(冻结契约扩展式演进):**

- R3 `ProjectAuthorization`:
  - `requireRead` → 任意成员(owner 兜底视为 LEADER,防数据漂移);404/403 口径不变。
  - `requireWrite` → LEADER。
  - 新增 `requireRole(projectId, userId, Set<ProjectRole>)` 与 `roleOf(projectId, userId)`。
  - 维持无 admin bypass。
- R4 `ProjectService.getRequired` 语义收敛为**读**(委托 requireRead 后返回实体),
  全部既有调用点自动获得成员读;写路径改为显式角色检查(见 R5 矩阵)。

**写路径角色矩阵(本阶段落地部分;未列出的域维持 LEADER,后续 Phase 再放):**

| 端点/动作 | 允许角色 |
|---|---|
| 项目 update/delete、仓库 bind/unbind、知识删除/重建索引、审查任务删除/报告删除、补丁审批、AgentRun cancel/retry | LEADER |
| 成员管理(增/删/改角色/移交) | LEADER(移交仅 owner) |
| 知识上传、临时审查触发(review create / PR review-task)、审查任务取消、PR 导入/更新 | LEADER, DEVELOPER |
| PR 审查意见(action)、审查 issue 反馈、AgentRun findings 反馈 | 任意成员 |
| 全部读端点、项目列表(成员项目并入) | 任意成员 |

- R5 矩阵按上表逐控制器落实;`ProjectResponse` 增 `myRole` 字段(只加字段)。

**成员端点(`/api/projects/{projectId}/members`):**

- R6 GET 列表(成员可见,join user_account 出 username/nickname);
  POST `{username, role∈{DEVELOPER,REVIEWER}}`(LEADER);
  PUT `/{userId}` 改角色(LEADER,owner 行不可改);
  DELETE `/{userId}`(LEADER,owner 不可移除);
  POST `/transfer` `{userId}`(仅 owner;目标须为成员;单事务:ownerId 更新 +
  新负责人行→LEADER + 原负责人行→DEVELOPER)。
- R7 新 ErrorCode 入词汇表:`MEMBER_NOT_FOUND(404)`、`MEMBER_ALREADY_EXISTS(409)`、
  `MEMBER_OWNER_IMMUTABLE(409)`;用户名不存在复用 `NOT_FOUND`。

**前端(墨境):**

- R8 项目区新增成员管理:成员列表、按用户名添加(角色下拉 DEVELOPER/REVIEWER)、
  改角色、移除、移交负责人(二次确认);非 LEADER 隐藏管理操作(以 `myRole` 判断)。

## Acceptance Criteria

- [ ] A1 授权矩阵测试扩展:非成员 403/404 口径不变;DEVELOPER 调项目设置/成员管理 403;
  REVIEWER 调知识上传 403;成员读 200;移交后新负责人可管理、原负责人降为 DEVELOPER。
- [ ] A2 存量单人项目零回归:`mvn verify` 全绿(阶段末一次)。
- [ ] A3 前端 `npm test` + `npm run build` 通过(阶段末一次)。

## Notes

- 用户指令:非必要不测试——过程不跑,阶段末一次全量;授权负面用例按 spec 门禁必须写。
- schema-postgres.sql 为历史遗留(prod 实际走 Flyway baseline),不同步。
