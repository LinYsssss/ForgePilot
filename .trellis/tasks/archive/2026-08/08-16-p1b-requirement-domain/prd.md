# P1b Requirement + AC 域

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R2/A2,design §3)。设计基线已在父 design 定稿。

## Goal

建立 Requirement + AcceptanceCriterion 领域:V30 迁移、状态机 + 守卫 + REQ 取号、
CRUD/指派/状态流转端点、前端 `/requirements` 墨境页(列表 + 详情)。
命名避让:不与 `pullrequest.ReviewAction#getRequirementText`(审查意见文本)混用词根——
实体/包名用 `requirement`,禁止在 pullrequest 包内新增同名概念。

## Requirements

**数据(V30):**

- R1 `requirement(id, project_id fk cascade, seq, title, background, description, priority,
  assignee_id, status, created_by, created_at, updated_at)`,`unique(project_id, seq)`;
  `acceptance_criterion(id, requirement_id fk cascade, seq, text)`,`unique(requirement_id, seq)`。
  priority ∈ {HIGH, MEDIUM, LOW}(check);status check 全枚举。
- R2 REQ 取号:事务内 `select max(seq)+1`,唯一约束兜底;取号与保存同一事务
  (database-guidelines:@Transactional 不自调用)。
- R3 项目删除级联:ProjectCleanupService 应用层删 requirement/AC(H2 无 FK 级联)。

**状态机(design §3):**

- R4 `DRAFT → NEEDS_IMPROVEMENT ⇄ READY → IN_DEVELOPMENT → IN_REVIEW → DONE`,
  任意非终态 → CANCELED;非法流转抛新 ErrorCode `REQUIREMENT_TRANSITION_ILLEGAL(409)`。
  DRAFT→READY 允许(体检非强制);READY 可回退 NEEDS_IMPROVEMENT。
- R5 守卫:→IN_DEVELOPMENT 需 assignee 非空;→DONE 本阶段人工推进(P5 后接门禁);
  IN_DEVELOPMENT 及之后修改标题/描述/AC 必须先回退到 READY 之前(保证审查与实验口径稳定),
  违者 409。CANCELED/DONE 为终态。
- R6 角色(P1a 矩阵):创建/编辑/指派/READY 推进/取消 = LEADER;
  IN_DEVELOPMENT→IN_REVIEW = LEADER 或被指派 DEVELOPER;读 = 任意成员。
  指派对象必须是项目成员(任意角色可被指派,常规为 DEVELOPER)。

**端点(`/api/projects/{projectId}/requirements`):**

- R7 POST 创建(title 必填,AC 列表随创建/编辑全量提交);GET 列表(分页 PageResponse,
  按 seq 倒序,支持 status 过滤);GET `/{id}` 详情(含 AC);PUT `/{id}` 编辑;
  POST `/{id}/assign` `{userId}`;POST `/{id}/status` `{status}` 统一流转入口。
- R8 新 ErrorCode:`REQUIREMENT_NOT_FOUND(404)`、`REQUIREMENT_TRANSITION_ILLEGAL(409)`、
  `REQUIREMENT_LOCKED(409, 进入开发后需先回退才能修改)`。

**前端(墨境,新建页):**

- R9 路由 `/requirements`(ink 壳)+ 导航入口;列表(状态徽章/优先级/指派人/REQ-号)
  + 创建/编辑表单(含 AC 行编辑)+ 详情(AC 列表、状态流转按钮组、指派下拉=成员名册);
  按 myRole 裁剪写入口,后端仍兜底。

**语料 schema(L 线前置,design §13):**

- R10 evaluation/manifest.json 的 schema 扩展字段定稿并写入
  `evaluation/README 或 manifest 内 schema 注释`:requirement{title,background,description}、
  acceptanceCriteria[{id,text}]、consistencyTruth[{acId,verdict}];schemaVersion 递增在首例标注时执行
  (本任务只定稿字段,不动现有 38 例)。

## Acceptance Criteria

- [ ] A1 非法流转(DRAFT→DONE 等)409;READY→NEEDS_IMPROVEMENT 允许;无指派→IN_DEVELOPMENT 409;
  IN_DEVELOPMENT 后编辑 409;REQ seq 项目内唯一递增。
- [ ] A2 角色负面用例:REVIEWER 创建 403;非指派 DEVELOPER 推进 IN_REVIEW 403;成员读 200。
- [ ] A3 阶段末 `mvn verify` 全绿 + 前端 `npm test`/`build` 绿;需求 CRUD+指派+流转全链路可演示。

## Notes

- 非必要不测试:过程不跑,阶段末一次;状态机与角色负面用例按 spec 门禁必须写(集中一个测试类)。
