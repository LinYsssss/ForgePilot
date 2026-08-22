# 批次 3 结果（Phase 6 + Phase 7）

任务：`08-21-batch-3-review-engine-human-loop`
授权依据：[D012](../../../../../docs/v2/DECISIONS.md#d012)、[D014](../../../../../docs/v2/DECISIONS.md#d014)。

> 验收条件只有通过、不通过和部分通过；浏览器、响应式与视觉检查不以 jsdom 或 CI 结果替代。

## 1. 当前结论

批次 3 的本地实现与实测已完成：固定的 16 张业务表、唯一 Review Engine、fencing、Finding 连续性、人工 Decision 闭环和三个前端实页均已落地。最大预算容量实测支持默认并发 `2`。真实 development 三臂评测及独立 Chat/Embedding Provider 配置已完成。

实现按五个职责分组提交并推送；远端 CI run `32574477108` 的 Evaluation、Frontend、Backend 与 Empty-stack Compose smoke 四个 job 全部成功。D014 退出闸门已通过，本任务可以归档。

| 项 | 结果 |
|---|---|
| 数据库 | **16 张**业务表；Flyway V1–V7，V7 仅给 PR 补 `title` 列 |
| 后端全量 | 当前工作树 JDK 21 `verify`：**298 tests，0 failure/error/skip** |
| 前端 | `npm ci`、lint、typecheck、15 tests、build 均退出 0 |
| Compose | CI 连续两次 fresh-volume 冷启动通过；当前 `fp-demo` 后端重建、V7 迁移和 readiness 均通过 |
| 容量 | 2 个并发最大预算 Review 均完成；冻结默认并发 `2` |
| 模型评测 | `gpt-5.6-luna` 三臂各 12/12 完成，0 structure failure |
| Holdout | **未读取、未运行** |

## 2. 关键实现结果

- Review 创建时冻结 Requirement、AC、PR 标题、head SHA、changed files 与 knowledge locator；后续 Pipeline 只读该快照。
- `review` 的身份列由数据库禁止修改；attempt/lease 的完成、失败、续租和 Finding 写入均受 fencing 保护。
- re-claim 在同一事务清除崩溃 attempt 的遗留 Finding；终态 Review 不可再次领取。
- 每条 AC 必有 `COVERED|NOT_FOUND|AT_RISK`；模型漏项由 Validator 补 `NOT_FOUND`。任一批次非法 JSON 且修复失败会使整个 Review `FAILED`。
- Finding continuity 只在同一 PR 内；`NOT_REPORTED` 不落库；状态流转与 `finding_event` 同事务。
- Decision 通过 PR 行锁与条件更新串行化；force-push 后旧终局决定不再放行新 head。
- Chat 与 Embedding 使用独立 OpenAI-compatible 路由和凭据，缺省时 Embedding 回退 Chat 路由；请求携带稳定 User-Agent，structured output 使用 `strict=true`。
- PR title 正式持久化，避免 Review 快照在同步后丢失标题；retry attempt 双递增已修复。

## 3. 运行边界实测

有效证据：[`evidence/capacity/20260822T121359Z-037799412c61/summary.md`](evidence/capacity/20260822T121359Z-037799412c61/summary.md)。

| 指标 | 实测 |
|---|---:|
| 主机内存 | 4,101,304,320 bytes |
| 负载 | 2 个并发 Review，各 300 文件 |
| 每个 canonical manifest | 3,989,101 字符 |
| Review Gateway 调用 | 152 次，均一次完成，无截断 |
| 终态 | `COMPLETED` / `COMPLETED` |
| JVM heap 峰值 | 83,167,816 bytes |
| direct buffer 峰值 | 428,032 bytes |
| Hikari active / pending 峰值 | 1 / 0 |
| Backend/PostgreSQL OOMKilled | false / false |

首轮 `20260822T120847Z-037799412c61` 的存储渲染超过产品 4,000,000 字符上限，已明确标为 **INVALID**，不拿它支撑并发结论。有效轮使用生产 AiGateway、分批、校验、持久化和 fencing；Provider 是本地确定性 OpenAI 协议 stub，未使用 evaluation corpus。

因此 `application.yml`、Compose 示例和架构文档将默认 Review 并发冻结为 `2`，约束更紧的部署可显式覆盖为 `1`。

## 4. Development 三臂真实模型评测

证据目录：[`evidence/development-evaluation/20260822-gpt-5.6-luna-v2`](evidence/development-evaluation/20260822-gpt-5.6-luna-v2)。Runner 固定 12 个 development quick cases，不接受自定义 manifest/case set，也不会读取 holdout。

| Arm | 完成 | Precision | Recall | Requirement violation recall | AC accuracy |
|---|---:|---:|---:|---:|---:|
| `DIFF_ONLY` | 12/12 | 10.00% | 11.11% | 0.00% | 0.00% |
| `DIFF_REQUIREMENT_AC` | 12/12 | 30.00% | 33.33% | 100.00% | 96.1538% |
| `DIFF_REQUIREMENT_AC_KNOWLEDGE` | 12/12 | 20.00% | 22.22% | 66.67% | 84.6154% |

三臂均 0 failed、0 structure failure。Knowledge 臂相对 Requirement+AC 臂下降，已如实保留；为避免在 12-case development 集上反复过拟合，本批次不继续据此调参。Holdout 仍锁在 Phase 8，只允配置冻结后运行一次。

## 5. 独立 Provider 与真实业务烟测

部署使用独立 Chat 和 Embedding Provider；真实密钥只在被 Git 忽略的本地 `.env`，未写入报告、diff 或日志。运行时脱敏核对确认：

- Chat model：`gpt-5.6-luna`
- Embedding model：`Qwen/Qwen3-Embedding-8B`
- Embedding profile：`hybgzs-openai-compatible / qwen3-embedding-8b-4096-v1`
- Review concurrency：`2`
- 两套 key 均存在，仅报告为 `[REDACTED]`

Embedding 协议真实调用返回 1 个 4096 维向量。部署后的 Requirement Quality 业务烟测经注册、登录、项目、需求和 `/quality` 完整 HTTP 链路返回 `200`；`ai_call_log` 只读核对为：

```text
REQUIREMENT_QUALITY | gpt-5.6-luna | SUCCESS | 8593 ms | prompt 257 | completion 374
```

审计行正确关联 requirement/revision。烟测数据以 `runtime-smoke-20260822-1245`、`Runtime AI Smoke 2026-08-22` 明确标识，保留在本机 `fp-demo` 演示库；未写入任何凭据。

## 6. 验证记录

| 检查 | 结果 |
|---|---|
| JDK 21 后端全量（最终当前工作树） | 298/298，0 failure/error/skip，`BUILD SUCCESS`，3m03s |
| 双 Provider 定向回归 | `AiGatewayTest` 16/16，0 failure/error/skip |
| 前端五门 | `npm ci` / lint / typecheck / test / build 全绿；15 tests |
| Compose 空库冷启动 | 三服务 healthy；16 张业务表逐名比对 |
| 当前演示部署 | backend healthy，Actuator `UP`，V7 迁移成功 |
| 容量 | 有效轮 PASS；无 OOM、重启、截断或连接等待 |
| CI `32574477108` | 四个 job 全绿；Compose job 连续两次 fresh-volume 冷启动 |

最终收口：`task.py validate` 退出 `0`，`git diff --check` 退出 `0`；顶层包 8 个、唯一子包 `scm/github`、`CREATE TABLE` 数量 16，`ci.yml` 无 `secrets.*`。

## 7. 非数据库执行与已知限制

- `REJECTED → OPEN` 只允许 `continuity=SUPPRESSED` 是 Service 条件更新执行的业务约束，**不是数据库执行的不变式**；测试覆盖允许与拒绝路径。
- 已关闭 PR 仍持续计入 activity/聚合。这是当前产品限制，不误记为通过；若 Phase 8 要过滤，必须先明确“关闭”的权威来源和历史语义。
- 浏览器真实点击闭环、1440/768/390 响应式检查和视觉漂移检查均为 **部分通过**：jsdom journey 能证明交互状态和四类标记未被合并，不能代替真实浏览器验收。
- Knowledge development arm 的质量下降未解决，只记录、不掩盖、不基于 holdout 调参。
- 本机 smoke 证明协议、配置绑定与真实 Quality 业务调用；Knowledge 当前无公开上传 Controller，因此 Embedding 的业务级 HTTP 链路未伪造，采用独立 Provider 真实协议调用加 `AiGatewayTest` 路由断言。
- GitHub Actions 提示 `actions/*` 的 Node 20 compatibility mode 与 `setup-java@v4` 已弃用；这是非阻断的 CI 维护提醒，本次四个 job 均成功。

## 8. 边界检查

- 恰好 16 张业务表；V7 不新增表。
- 顶层包仍为 `ai/auth/common/knowledge/project/requirement/review/scm`；唯一生产子包为 `scm.github`。
- 无 Agent、Patch、RabbitMQ/Outbox、第二 AI runtime 或第二 Review pipeline。
- 无新增运行时依赖；`backend/pom.xml` 与 `frontend/package.json` 未因本批次改变。
- 无向量索引、无 schema 维度绑定。
- 迁移中除 `pull_request.author_user_id` 外无 `ON DELETE`。
- `ci.yml` 不引用 `secrets.*`；真实 Provider key 不进入 CI。
- Holdout 未读取、未运行。

## 9. D014 退出闸门

| # | 标准 | 当前结论 | 说明 |
|---|---|---|---|
| 1 | 构建与测试全绿、无 skip | ✅ 本地成立 | 最终当前工作树全量 298/298；`BUILD SUCCESS` |
| 2 | Compose 空库冷启动 | ✅ 成立 | 三服务 healthy，16 表逐名比对 |
| 3 | CI 全部 job 绿 | ✅ 成立 | run `32574477108`：Evaluation 7s、Frontend 24s、Backend 1m21s、Compose 1m33s，全部 success |
| 4 | 边界干净 | ✅ 成立 | 见 §8 |
| 5 | 偏差与部分通过如实记录 | ✅ 成立 | 见 §7；浏览器三项与关闭 PR 限制明确记录 |

D014 五项全部成立，退出闸门通过。批次 3 可以归档；下一步是单独规划并授权 Phase 8，且 holdout 只在配置冻结后运行一次。
