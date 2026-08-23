# 批次 1 验证清单

命令为规划目标，实际脚本名可微调；`result.md` 必须记录真实执行的命令、退出码和关键结果。默认不跑与本批次无关的全量测试。

## 1. 工作树

```bash
git status --short --branch
git diff --check
```

- [ ] 所有 dirty path 归属本批次或明确列为既有改动；无 whitespace error、无冲突标记。

## 2. 后端构建与测试

```bash
cd backend && ./mvnw -B -ntp verify
# 宿主无 JDK 时用容器（见 backend/README.md 的命令）
```

- [ ] 全部测试通过，无 skip；依赖仍无 prerelease。
- [ ] 应用能启动——[D013.1](../../../../../docs/v2/DECISIONS.md#d013) 的映射形态若不成立会在**启动期**失败，这是最早的信号。

## 3. 数据库约束（真实 PostgreSQL 15，不得用 H2 或跳过）

- [ ] 空库 Flyway 后恰好 6 张业务表 + `flyway_schema_history`，无多余表。
- [ ] 同项目插第二个 LEADER 被拒（23505）；不同项目各一个 LEADER 可共存。
- [ ] LEADER 降级后可升级新成员；转移后仍恰一个 LEADER。
- [ ] `(project_id,user_id)`、`(project_id,scm_external_user_id)`、`(requirement_revision_id,ac_key)` 唯一性由数据库拒绝重复。
- [ ] 三步回填成功；`current_revision_id` 指向别的需求或别的项目的 revision 被拒（23503）。
- [ ] 跨项目写入被数据库拒绝，而不是只被 Service 拦下——测试须绕过 Service 直接写。

## 4. 权限与隔离

- [ ] A 项目成员用 B 项目的 project / requirement / revision / member id 读写，全部被拒，且**不存在与无权限返回同一结果**（不泄漏资源是否存在）。
- [ ] DEVELOPER / REVIEWER 执行 LEADER 专属操作（建需求、改 AC、置 READY、指派、配 SCM 身份、管成员）全部被拒。
- [ ] 授权判定不读 `scm_username`。

## 5. 认证

- [ ] 登录成功/失败；失败不区分用户不存在与密码错误。
- [ ] 登出后旧会话不可用；改密码后其他会话失效。
- [ ] 写请求缺失或伪造 CSRF token 被拒。
- [ ] 响应体与日志中不出现口令或哈希。

## 6. 需求版本化与状态机

- [ ] 创建需求同事务产生 Revision 1 及其 AC。
- [ ] DRAFT 原地编辑生效，且同事务清空 `quality_json/quality_version/quality_checked_at`（夹具须先 seed 非空值，否则该语义测不到）。
- [ ] `DRAFT → READY` 后原地修改被拒；新 Revision 需 `change_reason`；旧 Revision 与旧 AC 仍可读。
- [ ] 同一 AC 跨 Revision 保持相同 `ac_key`；改 `sort_order` 不改 `ac_key`。
- [ ] `READY → IN_DEVELOPMENT` 与首次指派同事务；后续换人不改状态。
- [ ] 非法转换逐条被拒；`CANCELED` 由任意非终态可达且不可恢复。

## 7. 架构规则

```bash
rg -n 'class .*Repository' backend/src/main/java | rg -v 'auth|project|requirement'
find backend/src/main/java/com/forgepilot -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
```

- [ ] ArchUnit 七条全部通过；新增两条各有反证 fixture 证明非恒真。
- [ ] 顶层包集合仍严格为八个；`scm` 仍不依赖 `review`。
- [ ] `project` / `requirement` 未注入 `UserAccountRepository`；`requirement` 未注入 `ProjectMemberRepository`。
- [ ] 无 `domain/application/infrastructure/web` 空分层目录。

## 8. 前端

```bash
cd frontend && npm ci && npm run lint && npm run typecheck && npm run test -- --run && npm run build
```

- [ ] 五条命令全部退出码 0。
- [ ] 一级导航仍只有三项；未新增依赖。
- [ ] 登录 → 建项目 → 加成员配 SCM 身份 → 写需求与 AC → 置 READY → 指派 → 看版本历史，闭环可完成。
- [ ] 需求状态与评审活动分开呈现，评审活动显示 `NO_PR`。
- [ ] 键盘可达与 reduced-motion 契约未回归。

## 9. Compose 与 CI

```bash
scripts/phase1-compose-smoke.sh forgepilot-phase1-<unique>
```

- [ ] **smoke 脚本需按本批次调整**：原断言「业务表数为 0」应改为「恰好为预期的 6 张」，否则本批次必然使其失败。
- [ ] 空库冷启动三服务健康、Flyway 成功。
- [ ] **CI 首次真实运行，四个 job 全绿**（Phase 1 遗留项，本批次必须闭环）。
- [ ] CI 仍不依赖 AI/SCM 凭据或仓库秘密。

## 10. 边界人工检查

- [ ] 无 Knowledge / AI / SCM / Review / Finding 相关代码或表。
- [ ] 无需求状态审计表（[D013.3](../../../../../docs/v2/DECISIONS.md#d013) 明确列为 MVP 缺口）。
- [ ] 未新增第 17 张表、未新增顶层包、未新增一级菜单。
- [ ] 迁移中无 `ON DELETE`、无向量索引、无维度绑定。
- [ ] Legacy 仅按迁移矩阵取用，未整包复制、未继承旧 Flyway 历史。

## 11. 最终任务验证

```bash
python3 ./.trellis/scripts/task.py validate 08-21-batch-1-auth-project-requirement
python3 ./.trellis/scripts/task.py current --source
git diff --check && git status --short
```

- [ ] `result.md` 含全部证据与偏差。
- [ ] 任务在提交与验收前仍为 `in_progress`，不提前 archive。
- [ ] 未创建、未启动批次 2。
- [ ] 提交分组与 commit message 已展示并等待确认；未自动推送。
