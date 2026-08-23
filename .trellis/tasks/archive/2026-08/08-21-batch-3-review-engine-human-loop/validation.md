# 批次 3 验证清单

命令为规划目标，实际脚本名可微调；`result.md` 必须记录真实执行的命令、退出码与关键结果。

## 1. 工作树

```bash
git status --short --branch && git diff --check
```

- [ ] 所有 dirty path 归属本批次；无 whitespace error、无冲突标记。
- [ ] **有代理在跑时不得 `git add -A`**（批次 2 `7daf632` 与批次 3 规划期各踩过一次）。

## 2. 后端构建与测试

```bash
cd backend && flock /root/.claude/jobs/e84ffece/tmp/maven.lock docker run --rm --network host \
  -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

- [ ] 全部测试通过，无 skip；**依赖零新增**（`backend/pom.xml` 与 `frontend/package.json` 均零改动）。

## 3. 数据库（真实 PostgreSQL 15）

- [ ] 空库 Flyway 后恰好 **16 张**业务表 + `flyway_schema_history`，**逐名比对**。此后不得再有新表。
- [ ] `review` 唯一键是 `UNIQUE NULLS NOT DISTINCT` 四元组——查 `pg_index.indnullsnotdistinct` 确认，
      **不是**按名字猜。写成默认 `NULLS DISTINCT` 时未关联需求的 PR 能堆积同四元组 Review。
- [ ] `review` 的「requirement_id 与 requirement_revision_id 同空或同非空」CHECK 存在；
      **反证**：临时表上去掉该 CHECK 后，半 NULL 行使三列复合外键静默失效（同 [D015.2](../../../../../docs/v2/DECISIONS.md#d015) 手法）。
- [ ] 约束触发器是 **IMMEDIATE**，父子上下文不一致被 `23514` 当场拒绝。
- [ ] `review` 身份列（`pull_request_id`/`head_sha`/`review_input_fingerprint`/`requirement_id`/
      `requirement_revision_id`/`context_snapshot_json`）创建后**不可改**，改动被拒。
- [ ] `finding` 的 attempt 复合外键存在；旧 attempt 插 Finding 被数据库拒绝。
- [ ] `idx_finding_carried_from` 存在。
- [ ] `ai_call_log.review_id` **现在有**外键了——反转批次 2 的断言，且反转与 [D016.2](../../../../../docs/v2/DECISIONS.md#d016) 一起做。
- [ ] 跨项目写入被数据库拒绝，测试须绕过 Service 直写，覆盖三张新表。

## 4. fencing 与执行（**四条路径缺一不可**）

- [ ] 旧 Worker **完成** → 0 行。
- [ ] 旧 Worker **标记失败** → 0 行。（最常被漏）
- [ ] 旧 Worker **续租** → 0 行。
- [ ] 旧 Worker **插入 Finding** → 被**数据库**拒绝（不是应用层先查）。
- [ ] 崩溃 attempt 的遗留 Finding **不会把 Review 钉死**：re-claim 同事务删除后成功领取。
- [ ] `COMPLETED` 的 Review 领不到，其 Finding 一行不动。
- [ ] 两个 Worker 抢同一过期 lease，**只有一个成功**（双线程实测）。
- [ ] **并发上限断言直接读 `corePoolSize`**，不是只断言「Review 能跑完」。

## 5. Review 引擎

- [ ] 事务内建 PENDING 与提交后调度是**两个方法**；监听器失败使整个 SCM 事务回滚。
- [ ] AFTER_COMMIT 内不使用 `EntityManager`，不使用裸 `JdbcTemplate`（只允许 `REQUIRES_NEW`）。
- [ ] reconciliation 的查询 **FROM 子句只有 `review`**；给出「若写成补建会建出什么」的反例测试。
- [ ] 任一 Batch 非法 JSON 且修复失败 → **整个 Review FAILED**；断言的是「判定为 FAILED」而非「没抛异常」。
- [ ] 每条 AC 必有 `COVERED|NOT_FOUND|AT_RISK`，模型漏项由 Validator 补 `NOT_FOUND`。
- [ ] truncation/coverage manifest 落库且在响应体中可见；未审查文件不得静默消失。
- [ ] 两个 hash 不含 LLM 自由文本（给出「换一句模型措辞，hash 不变」的断言）。
- [ ] 连续性只在同一 PR 内；`NOT_REPORTED` **不落库**。

## 6. Phase 7 人工闭环

- [ ] Decision：六项前置 + **PR 行锁** + 条件更新，影响行数必须为 1，否则 409。
- [ ] **并发测试**：两个并发 APPROVE 只有一个成功；APPROVE vs REQUEST_CHANGES 同理。
- [ ] **不加 `FOR UPDATE` 会放行陈旧 head** 这一点被挡住——单线程绿在此无意义。
- [ ] 前置 5 写 `IS NOT DISTINCT FROM`；未关联需求的 PR **能**做出终局决定。
- [ ] Decision Gate 派生判定；force-push 回旧 head 时**自动重新封锁**。
- [ ] Finding 状态机逐对断言，含 LEADER 在「认领」「标记已修复」两格是 ❌。
- [ ] `REJECTED → OPEN` 仅限 `continuity=SUPPRESSED`，且重开后 continuity **保留** SUPPRESSED。
- [ ] 每次流转同事务写 `finding_event`，`from_status` 取自条件更新而非先前的读。

## 7. 架构规则

- [ ] ArchUnit 七条全绿；顶层包仍八个；子包只有 `scm.github`。
- [ ] `scm` 仍不依赖 `review`（此刻 `review` 已有真实类，这条规则第一次真正生效）。
- [ ] `requirement` **不依赖** `review`——activity 全部算在 `review` 侧。
- [ ] 无 `common` 之外的 logger。

## 8. 前端

```bash
cd frontend && npm ci && npm run lint && npm run typecheck && npm run test -- --run && npm run build
```

- [ ] 五条命令全绿（**必须 `npm ci`**，不得复用既有 `node_modules`）。
- [ ] 导航仍三项；路由仍七条；`package.json` 零改动。
- [ ] 三页从占位变实页。
- [ ] jsdom 全旅程测试通过，并断言「状态/血缘/置信度/Decision 四个标记未被合并」。

## 9. Compose 与 CI

```bash
scripts/phase1-compose-smoke.sh forgepilot-phase1-batch3-<unique>
```

- [ ] `expected_tables` 已改为**十六张全名**；空库冷启动三服务健康。
- [ ] CI 四 job 全绿，`ci.yml` 中**仍无 `secrets.*`**。

## 10. 运行边界实测（**不可放松**）

- [ ] 在目标 **4 GB** 机、生产 JVM/PostgreSQL 上限下跑至少一个**最大预算** Review。
- [ ] 记录峰值内存、连接池占用、失败与降级行为。
- [ ] **据实**把并发 Review 冻结为 1 或 2——若实测结论是 1，就写 1。
- [ ] **不得预写常量再补一个「能跑」的测试**（[D012](../../../../../docs/v2/DECISIONS.md#d012).2、[D014](../../../../../docs/v2/DECISIONS.md#d014).6）。
- [ ] 若跑不动：**如实记录失败与降级行为**，不得为了有个数字而缩小「最大预算」的定义。

## 11. 边界人工检查

- [ ] 恰好 16 张表，**不得有第 17 张**；无新增顶层包、无新增一级菜单、无新增运行时依赖。
- [ ] 无向量索引、无维度绑定（[D001](../../../../../docs/v2/DECISIONS.md#d001) 仍然有效）。
- [ ] 迁移中除 `author_user_id` 外无 `ON DELETE`。
- [ ] **holdout 未被读取、未被运行**（锁死 Phase 8）。
- [ ] Phase 6 调参只用 development 集。

## 12. 最终任务验证

```bash
python3 ./.trellis/scripts/task.py validate 08-21-batch-3-review-engine-human-loop
git diff --check && git status --short
```

- [ ] `result.md` 含全部证据与偏差。
- [ ] 非数据库执行的不变式如实记为「非数据库执行」（至少：`REJECTED → OPEN` 的 SUPPRESSED 限制）。
- [ ] **浏览器/响应式/视觉漂移三项如实记为部分通过**——jsdom 测试**不是**浏览器验收（`design.md` §3.5）。
- [ ] 已关闭 PR 持续计入聚合这一产品限制如实记录（`design.md` §2.3）。
- [ ] 按 [D014](../../../../../docs/v2/DECISIONS.md#d014) 逐条自证退出闸门，不合格就停。
