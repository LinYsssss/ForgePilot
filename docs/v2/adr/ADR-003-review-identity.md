# ADR-003 Review Identity：业务键收敛为 (pull_request_id, head_sha)

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §3.1 · §2.1、[PRD.md](../PRD.md) §6 P4

## 背景

原候选版 Review 唯一键为 `(pull_request_id, head_sha, engine_version)`：引擎升级后同一 head SHA
可产生第二条 Review，与"同一 head SHA 只允许一次终局 Decision"冲突，需要额外的跨 engine_version
部分唯一索引来堵漏。

## 决策

1. Production Review 的唯一业务键为：

   ```sql
   UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, requirement_revision_id)
   ```

   即「一个 head 在一个需求上下文版本下只有一条 Review」。
2. engine_version、prompt_version、model 仅作为**审计元数据**列，不参与 Review Identity。
3. 同一个 head SHA 在生产环境**不因 Engine 升级自动重新 Review**。
4. Engine/Prompt/Model 多版本对比属于 **evaluation**，不进入生产 Review 数据模型。
5. 删除"跨 engine_version 终局 Decision partial unique index"的必要性——Decision 保存在 Review 上。
6. **Decision Gate 与 Review Identity 分离**：

   ```text
   Review Identity = pull_request_id + head_sha + requirement_revision_id
   Decision Gate   = pull_request_id + head_sha
   ```

   同一 `head_sha` 一旦出现 `REQUEST_CHANGES`，**任何**需求上下文版本都不能再产生 `APPROVE`；解除闸门只能靠新 head SHA。MVP **不提供 LEADER 绕过入口**。
7. 上下文变化产生新 Review 身份的完整行为：

   | 事件 | 结果 |
   |---|---|
   | `NULL → REQ-A` / `REQ-A → REQ-B` / `REQ-A → NULL` | 产生新 Review |
   | Requirement v1 → v2（[ADR-011](./ADR-011-requirement-revision-and-state.md)） | 产生新 Review |
   | `REQ-A → REQ-B → REQ-A` | 复用原 Review，不重复运行 |
   | 项目知识更新 | **不**改变生产 Review 身份 |
   | Prompt / 模型 / 检索策略变化 | 进入 evaluation，不制造生产 Review |

8. **终局决策必须串行化**：事务内 `SELECT ... FOR UPDATE` 锁 `pull_request` 行 → 校验当前 head → 查该 head 是否已有 `REQUEST_CHANGES` → 写 Decision → 提交。
9. **关联修改权限按是否已有 Review 收紧**：当前 head 尚无 Review 时，本人 PR 的 DEVELOPER 可修正关联（[ADR-010](./ADR-010-scm-identity-and-repository-immutability.md)）；当前 head 已有任意 Review 时，**仅 LEADER** 可修改。LEADER 亦不能解除 head 级 `REQUEST_CHANGES`。

## 后果与实施注记

- **`NULLS NOT DISTINCT` 不可省略**：未关联需求时 `requirement_revision_id` 为 NULL，PostgreSQL 默认语义下 NULL 互不相等，会允许同一上下文重复创建 Review。该语法需 PostgreSQL 15+，与 [ADR-010](./ADR-010-scm-identity-and-repository-immutability.md) 的列级 `ON DELETE SET NULL` 共同构成最低版本硬依赖。
- Review 身份由**真实上下文派生**，不存在任何人工维护的上下文计数器；因此也不需要"什么才触发上下文变更"的纪律清单——知识库更新不产生新 revision，重审风暴在设计上不可能发生。
- 第 6 条堵的是一条真实可达路径：被审查方修改需求关联即可换出新 Review 身份，若闸门跟随身份走，`REQUEST_CHANGES` 就能被"改个关联再跑一次"洗掉。闸门只认 head，代码不动则退回不可解除。
- 第 8 条不能退化为普通 `EXISTS` 查询：两个 Reviewer 并发提交时两笔事务会同时通过检查。须补并发集成测试，证明同一 head 不可能并发产生冲突终局。
- 人工重试**复用同一条 Review 行**（停滞的 PENDING/RUNNING 或 FAILED 重置后重跑），不插入第二行；历史尝试通过 `ai_call_log` 与状态时间戳追溯。`COMPLETED` 的 Review **永不覆盖**。
- "修复后按新 head SHA 产生新 Review、保留前后结果"的规则不受影响。
- 评测环境多版本对比使用 evaluation 设施（Phase 8），不写生产 `review` 表。
