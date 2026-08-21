# 批次 1 执行计划

## 0. 启动前闸门

- [ ] `prd.md`、`design.md`、本文件与 `validation.md` 已就绪；[D013](../../../docs/v2/DECISIONS.md#d013) 已提交。
- [ ] `implement.jsonl` / `check.jsonl` 已填入真实条目。
- [ ] 运行 `python3 ./.trellis/scripts/task.py start 08-21-batch-1-auth-project-requirement`。
- [ ] 记录 `git status --short`，识别并隔离既有改动。
- [ ] 派发提示第一行为 `Active task: .trellis/tasks/08-21-batch-1-auth-project-requirement`；子代理不得再派子代理，不得提交/推送/reset/checkout/删除未授权文件。

## 1. 数据库迁移与实体映射（必须最先完成）

**范围**：`backend/src/main/resources/db/migration/V2__auth_project.sql`、`V3__requirement.sql`，六个实体类，以及仅验证约束的集成测试。

- [ ] 按 `design.md` §2 写两条迁移；枚举用 `varchar + CHECK`；不写 `ON DELETE`；不加 §2.1 未规定的列。
- [ ] 六个实体按 [D013.1](../../../docs/v2/DECISIONS.md#d013) 变体 A 映射；**先让应用启动成功**再往下走。
- [ ] 集成测试逐条断言约束真的生效：部分唯一索引拒绝第二个 LEADER、复合外键拒绝跨项目写入（23503）、三步回填成功且指向别的需求/项目被拒、`(requirement_revision_id, ac_key)` 唯一。
- [ ] 运行 `./mvnw -B -ntp verify`。

**闸门 A（必须）**：若变体 A 在真实实体上不成立，或任一约束行为与 `research/pg15-hibernate-constraints.md` 的实测不符，**停止**并回到决策，不得改用 Service 校验绕过（[D006](../../../docs/v2/DECISIONS.md#d006)）。

**验收点**：AC1、AC7 的数据库部分。

## 2. Auth 切片

**范围**：`backend/src/main/java/com/forgepilot/auth/**` 与其测试。

- [ ] `UserAccount` + Repository；BCrypt `PasswordEncoder`。
- [ ] Spring Security 表单登录、进程内 `HttpSession`、CSRF cookie repository。
- [ ] `session_version` 比对与强制失效；登录失败不区分用户不存在与密码错误。
- [ ] `UserDirectory` 只读 facade（byId / byUsername → id, username, enabled）。
- [ ] `CurrentUser`：从 SecurityContext 取 `userId`，业务 Service 不接触 Security。
- [ ] 测试：登录成功/失败、登出后旧会话失效、改密后其他会话失效、CSRF 缺失被拒、口令不回显。

**验收点**：AC2。

## 3. Project + Member 切片

**范围**：`backend/src/main/java/com/forgepilot/project/**` 与其测试。前置：步骤 1、2。

- [ ] `Project`、`ProjectMember`、`ProjectRole`、各自 Repository（读路径一律带 `projectId`）。
- [ ] `ProjectAccessService` 作为唯一授权入口；不存在与无权限返回同一结果。
- [ ] 创建项目 = 插 project + 插 LEADER 成员，同事务（[D013.5](../../../docs/v2/DECISIONS.md#d013)）。
- [ ] LEADER 转移 = 降级 → flush → 升级，`project` 行锁串行化（[D013.8](../../../docs/v2/DECISIONS.md#d013)）；**禁止单条 CASE 交换**。
- [ ] 成员增删改角色、SCM 身份配置（仅 LEADER）。
- [ ] 成员列表经 `UserDirectory` 取用户名，不注入 `UserAccountRepository`。
- [ ] 测试：恰一 LEADER、并发转移只有一个成功、SCM 身份唯一、跨项目猜 id、角色越权。

**验收点**：AC3、AC4、AC5（项目部分）、AC6（项目部分）。

## 4. Requirement 切片

**范围**：`backend/src/main/java/com/forgepilot/requirement/**` 与其测试。前置：步骤 1、3。

- [ ] 三个实体 + Repository；授权经 `ProjectAccessService`，不自查 `ProjectMemberRepository`。
- [ ] 创建走三步回填；`ac_key` 生成与跨 Revision 继承；`sort_order` 仅显示。
- [ ] DRAFT 原地编辑并同事务清空 `quality_json/quality_version/quality_checked_at`。
- [ ] `DRAFT → READY` 冻结；READY 后发布新 Revision（`change_reason` 必填）并回填。
- [ ] 状态机按 `design.md` §6.4；`READY → IN_DEVELOPMENT` 与首次指派同事务；`CANCELED` 任意非终态可达且不可恢复。
- [ ] 测试：Revision 冻结、`ac_key` 稳定、`quality_json` 清空（夹具需先 seed 非空值）、状态机非法转换逐条被拒、跨项目猜 id、角色越权。

**验收点**：AC7、AC8、AC9、AC10、AC5/AC6 的需求部分。

## 5. ArchUnit 加固

**范围**：`backend/src/test/java/com/forgepilot/ArchitectureRulesTest.java` 与 fixture。前置：步骤 4（此时才有真实业务类可约束）。

- [ ] 新增规则：feature 内部子包白名单（仅允许 `scm.github` / `scm.gitlab` / `ai.openai`）。
- [ ] 新增规则：Repository 识别不再只靠类名后缀，叠加 Spring Data `Repository` 类型判定。
- [ ] 两条新规则各配反证 fixture 证明非恒真；现有五条保持通过。

**验收点**：AC12。

## 6. 前端切片

**范围**：`frontend/**`。前置：步骤 2–4 的 API 稳定。

- [ ] 登录页与登录态处理；失效统一跳转；CSRF token 经 `http.ts` 注入点附加。
- [ ] 项目列表与创建、成员管理（角色 + SCM 身份）。
- [ ] 需求列表、详情、AC 编辑、版本历史。列表标题来自后端组装的视图对象，前端不二次拼接。
- [ ] 需求状态与评审活动分开呈现，评审活动恒为 `NO_PR`。
- [ ] 不新增一级菜单、不引入新依赖、不使用虚构数据。
- [ ] 运行 `npm ci && npm run lint && npm run typecheck && npm run test -- --run && npm run build`。

**验收点**：AC11。

## 7. 集成、CI 与全范围复核

- [ ] Compose 空库冷启动仍成功（业务表已由 Flyway 建出，smoke 的"业务表数为 0"断言需按本批次调整为"恰好 6 张预期表"）。
- [ ] 推送后确认 CI 四个 job 全绿——Phase 1 遗留项，本批次必须闭环。
- [ ] 主会话逐个检查实际 `git diff`，不以代理总结代替事实。
- [ ] 派发定向审查代理：数据完整性/授权边界、需求状态机与版本化、前端契约。文件范围不重叠，默认只读。
- [ ] 运行 `validation.md` 全部命令。

**验收点**：AC13、以及全部 AC 的复核。

## 8. Finish 与提交闸门

- [ ] 更新 `result.md`：完成/未完成、代理分工、文件范围、命令与结果、边界、Legacy 依据、风险、回滚、批次 2 前置条件。
- [ ] Trellis spec update：把 [D013.1](../../../docs/v2/DECISIONS.md#d013) 的映射形态、三步回填、LEADER 转移写法写入 `.trellis/spec/backend/`；前端新增约定写入 `.trellis/spec/frontend/`。
- [ ] 展示提交分组与 commit message，等待确认；不 amend、不自动推送。
- [ ] 批次 1 验收后停止，不创建、不启动批次 2。

**验收点**：AC14。

## 文件所有权与派发顺序

1. 迁移 + 实体（步骤 1）**必须由单一执行者串行完成**，是后续一切的地基，不并行。
2. 步骤 2 与步骤 3 有依赖（project 需要登录上下文），按序执行。
3. 步骤 4 依赖步骤 3 的 `ProjectAccessService`。
4. 步骤 5、6 可在步骤 4 完成后并行：ArchUnit 只动测试目录，前端只动 `frontend/**`，文件不重叠。
5. 审查代理默认只读；需要修复时由主会话按 finding 分配不重叠文件。

并发不超过 5 个子代理，可多轮分派。

## 回滚点

- 每个切片按独立文件组回滚。数据库回滚等价于重建空库（本批次尚无生产数据）。
- 闸门 A 未过即停止，回到 `design.md` / [D013](../../../docs/v2/DECISIONS.md#d013) 重新裁定，不在代码里加兼容分支。
- 发现与产品/架构决策冲突时，先更新文档或新增决策，不用代码自行解释。
