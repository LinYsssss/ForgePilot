# ADR-010 SCM 身份映射与仓库不可变边界

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §2.1 · §2.3 · §7、[PRD.md](../PRD.md) §3 · §6 P1 · §8、[ADR-006](./ADR-006-cross-project-referential-integrity.md)、[ADR-007](./ADR-007-pr-requirement-association.md)

## 背景

PRD §3 与 ADR-007 §3 都规定 DEVELOPER 可修改**本人 PR** 的需求关联，但数据模型中 `user_account` 无 SCM 登录名、`pull_request` 无作者列，**没有任何数据支撑"本人"判定**。ADR-007 中"映射到本地账户时"是唯一提及映射的表述，机制未定义、未落表、未进阶段计划。

同时，`scm_repository.project_id` 的 UNIQUE 约束使新旧仓库无法并存，一旦原地覆盖仓库记录，就无法判断历史 PR 是否仍属当前活动仓库。

## 决策

1. **项目级 SCM 身份**：`project_member` 增加 `scm_external_user_id`（权限依据）、`scm_username`（仅显示）、`scm_identity_verified_at`（审计）；同一项目内 `(project_id, scm_external_user_id)` 唯一。
2. 身份由 **LEADER 在成员管理页配置**并经 SCM API 解析为稳定外部 ID，**开发者不得自行声明**。
3. **禁止以用户名比对授权**。SCM login 可改名、可重名、可被他人重新注册，按用户名授权是直接的越权入口。
4. `pull_request` 增加 `author_external_user_id`、`author_username`（**不可变快照**，永不重算）与 `author_user_id`（**派生映射缓存**）。
5. 权限：LEADER 可改项目内任意 PR 关联；DEVELOPER 仅当 `author_user_id` = 当前用户；未映射时仅 LEADER。进一步的收紧规则见 [ADR-003](./ADR-003-review-identity.md)（当前 head 已有 Review 时仅 LEADER 可改）。
6. `author_user_id` 在每次 webhook 同步中按 `(project_id, provider, external_user_id)` **幂等重算**，使后绑定的成员身份在下次 push 自动生效。
7. 作者外键指向 `project_member` 而非 `user_account`：
   `FOREIGN KEY (project_id, author_user_id) REFERENCES project_member(project_id, user_id) ON DELETE SET NULL (author_user_id)`。
8. **仓库不可原地更换**（MVP 产品限制）：已产生 PR 后 `provider + external_id` 不可修改；token / webhook secret / api_base 可更新；真要更换仓库须新建项目。
9. 不建独立 `scm_user_identity` 表，不引入 OAuth。多仓库、多 Provider、多账号、SCM 登录均未发生，属预埋。

## 后果与实施注记

- 第 7 条必须使用 PostgreSQL 15+ 的**列级** `ON DELETE SET NULL (author_user_id)`。普通 `ON DELETE SET NULL` 会同时清空复合外键中的 `project_id`（NOT NULL），删除成员时直接报错。
- 与 [ADR-011](./ADR-011-requirement-revision-and-state.md) 的 `UNIQUE NULLS NOT DISTINCT` 共同构成硬依赖：**本项目最低 PostgreSQL 版本为 15**，Testcontainers 镜像、Docker Compose 与部署环境须统一。
- 第 7 条的副作用即第 5 条的兜底：成员被移出项目时 `author_user_id` 自动置空，权限自然退化为"仅 LEADER 可改"，无需额外代码。
- 第 8 条是**用户可见的产品限制**，已写入 PRD §8。未来支持换仓或多仓库时改为 Project 1:N Repository + 单活动仓库约束；届时第 6 条的重算必须补充"该 PR 所属仓库仍是当前活动仓库"守卫。**当前不写该守卫**——在本 ADR 的约束下它恒为真，永真判断属死代码。
- 落地阶段：`project_member` 三列与唯一约束在 Phase 2；`pull_request` 三列与权限判断在 Phase 5。
- Phase 2 退出标准须含 `(project_id, scm_external_user_id)` 唯一性用例：两个成员争抢同一批 PR 的作者身份是明确的越权场景。
