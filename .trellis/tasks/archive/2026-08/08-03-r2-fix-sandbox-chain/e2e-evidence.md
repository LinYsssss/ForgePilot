# r2 端到端修复证据（沙箱归档断链）

日期：2026-08-04（服务器实测）。本文档记录 08-03-r2-fix-sandbox-chain 的实施证据，
含审计内目标（F-03/F-04/F-05）与 e2e 途中暴露的审计外断点。生产库在验证前已备份
（`deploy/backups/code_review-20260804-133202.dump`）。

## 一、审计内目标的落地与实证

| 项 | 修复 | 实证 |
|---|---|---|
| F-03 契约冲突 | 两侧同构 `WorkspaceArchiveReference`（C' 方案），裸文件名、无 scheme；双向金标测试钉住 `agent-run-42-abcdef1234567.tar` / `patch-9-<64hex>.tar` 字面量与完整拒绝集 | run9/run10 的 PREPARING_REPOSITORY=SUCCEEDED；runner 日志 `SecurityException\|ENVIRONMENT_INCOMPLETE` 计数为 0 |
| F-04 无生产者 | `WorkspaceArchiveService`（唯一生产者）+ `GitCliService.archiveForReview/archiveWithFiles`（`.partial` 原子落盘，预置 `.reposage/review.diff`）；compose backend 读写挂载、runner 保持 `:ro`；发布步骤就地清理 + TTL 清扫 | `/app/archives/agent-run-9-e205e0e….tar`（40KB，tar 内容为 head 树）在作业期间可见；runner 经契约 parse 解析并回传预置 diff |
| F-05 软失败 | `ProdSecretValidator.checkSandboxToolImage`：生产非空 + 完整 64 位小写 hex digest；正则收敛 `PinnedImageDigests`（语料形状校验/部署严格校验分档） | 部署实测：`.env` 真实 digest（`maven@sha256:1ed5…ed45`，与 `docker images --digests` 一致）通过启动；单测覆盖缺失/tag/缩写/大写 hex 四类拒绝 |

## 二、e2e 途中暴露的审计外断点（同批修复）

链路此前从未真实跑通（生产库 `agent_run` 为空），首次点火按顺序暴露四截：

1. **首步无人派发**（run5/6 前）：步骤调度只存在"上一步完成→排下一步"，RECEIVED 的
   Run 永远停着。修复：`AgentRunCreatedEvent` + `AgentRunKickoffListener`（BEFORE_COMMIT，
   与建 Run 同事务原子排入第 1 步）。涟漪：3 个断言"停在 RECEIVED"的测试改为断言
   PREPARING_REPOSITORY——旧断言钉住的正是缺陷行为。
2. **PR 数据单源**（run6，`The given id must not be null`）：取证步骤只认
   `PullRequestEntity`，而 webhook 流程的 PR 数据在 `AgentScmContext`（run 的
   pullRequestId 为 null）。修复：`resolveBaseHead` 双源解析，webhook 路径补单测。
3. **maxBytes 自相矛盾**（run7，`Malformed input for tool git.diff`）：执行器请求 131072,
   工具入参校验上限 65536。修复：对齐全平台预算口径 65536。此时归档已成功落盘
   （`agent-run-7-….tar`），证明 F-03/F-04 修复已生效，失败点已移出 r2 主链。
4. **RPC 应答等待缺配**（run8，`Reply received after timeout`）：runner 正常处理并应答,
   但 backend RabbitTemplate 应答等待为默认 5s，罩不住 120s 工具预算。修复：
   `application-prod.yml` 增 `spring.rabbitmq.template.reply-timeout`（默认 130s）。

## 三、部署配置补全（宿主机 `.env`，不入库）

- `SANDBOX_TOOL_IMAGE=maven@sha256:1ed5…ed45`（F-05；此前已配置，本次实测校验通过）。
- `GIT_ALLOW_LOCAL_PATH=true`：绑定 `/app/demo-repos/*` 演示仓库所需（校验器按设计仅警告）。
- `AI_RUNTIME=langchain4j`：Agent 步骤的 `AgentModelClient` 仅在该运行时装配，
  缺省 legacy 下 PLANNING 永远 CHECKPOINTED（run9 实测）。
- `EMBEDDING_PROVIDER=openai-compatible` + `EMBEDDING_BASE_URL=<MiMo /v1>` +
  `LLM_EMBEDDING_MODEL=mimo-embedding-demo-unused`：**名义配置**。langchain4j 运行时的
  prod 校验器硬性要求真实 embedding 配置，而本部署为 MiMo（无 embedding 接口）+
  全量注入（`RAG_FULL_CONTEXT=true`，检索不调用 embedding，F-12 已记录该姿态）。
  该三行仅为通过启动校验，运行期不会被调用；生产级检索姿态属 r5/r8 范畴。
  部署手册 §7 的演示配置（`EMBEDDING_PROVIDER=mock`）与该校验器在 langchain4j
  运行时下互斥，属既有文档-校验矛盾，留待 r3/r8 收敛口径。

## 四、e2e 验收记录（对照 prd.md Validation）

- 触发方式：临时种子管理员（`.env` 注释钦定的机制，用后移除口令）→ 管理端建
  installation → 构造真实 PR payload（demo 仓库 `mall-order-service`,
  base=6977028 main / head=e205e0e feature/promotion-batch-ship）→ HMAC 签名投递
  `/api/webhooks/scm/github` → 202 + agentRunId。
- run9：PREPARING_REPOSITORY / ANALYZING_CHANGE / PLANNING 三步 SUCCEEDED,
  归档在 `/app/archives` 可见且为契约文件名;runner 无 SecurityException /
  ENVIRONMENT_INCOMPLETE。PLANNING 停在 CHECKPOINTED → 暴露 AI_RUNTIME 缺配。
- run10：AI_RUNTIME=langchain4j 后首次全链路点火,PLANNING(a1)通过,败在
  EXECUTING_TOOLS 终稿:`unknown tool static_analysis; unknown tool security_scan`
  ——MiMo 在回执里自创工具名。修复:终稿提示词白纸黑字枚举合法工具名并要求逐字
  回写原计划项(修复于会话间隙进镜像,由 run13+ 实证)。

## 五、续验（2026-08-08,同批第 5–8 截）

温度 0 的链路像端到端的探针:每修一截,失败前沿即推进一步。当日四轮点火、四截修复:

| Run | 失败点 | 错误 | 根因与修复 |
|---|---|---|---|
| run11 | PLANNING(a0) | `item[3]/item[4]: tool repetition exceeds limit 3` | 验证器数值约束(计划 ≤8 项、单工具 ≤3 次)从未写进规划提示词。修复:提示词声明上限,数值与 `PlanPolicy.remainingToolCalls()`/`ReviewPlanValidator.defaultToolLimit()` 同源(零新增字面量) |
| run12 | PLANNING(a0) | `item[3]: tool repetition exceeds limit 3` | 提示词生效(违规 2 项→1 项)但 MiMo 数不准——提示词工程到头。修复:`PlanPolicy.clampOverBudget` 服务端确定性裁剪(内在缺陷仍硬错;超预算条目丢弃并 WARN,裁至空报错),`ModelOutputValidator` 重建响应使下游只见存活条目;顺带修读码发现的回执预算错误(`8-requests` → `plan().size()`,计划 ≥5 项时回执必被误拒) |
| run13 | EXECUTING_TOOLS(a0) | `Unrecognized field "claim" (CitedClaim)` | 裁剪生效(PLANNING 首次 a0 通过,WARN 记录裁掉 code.search 第 4/5 次重复);断点前移到 claims 条目内部——schema 里 `"claims":[]` 等于没告诉模型条目形状。修复:两执行器 schema 钉死 `{"text","knowledgeBacked","citationIds"}`,并因本步 citation 白名单为空集而要求 knowledgeBacked=false、citationIds=[] |
| run14 | VERIFYING_FINDINGS(a0) | `LangChain4j provider call failed transiently (InternalServerException)` | 1–5 步首次全绿(claims 修复实证)。客户端已把 429/5xx/超时分类为 `AiCallTransientException`,却穿透到兜底 catch 被按 INTERNAL_ERROR 终态——`RETRYABLE_PROVIDER_ERROR` 与 `scheduleRetry` 全程闲置。修复:`AgentStepExecutionService` 补上分类→重试的最后一跳 |
| run15 | VERIFYING_FINDINGS(a0) | `finding model output is not schema-valid JSON`(审计:finish_reason=STOP,644 tokens,输出完整非截断) | `AgentFindingModelService` 是独立于 `ModelOutputValidator` 的第二条解析路径,缺失全部防线:无 markdown 围栏剥离、错误不带 cause(诊断被蒙)、引用校验整体硬失败。修复:围栏剥离提取为 `ModelJsonOutputs` 单一事实源两路共用;错误与审计行带定界原因;severity 枚举大小写容错;引用违规从整步硬失败改为逐条裁剪+WARN(非空全灭仍硬错,防"全垃圾伪装零发现");提示词枚举 severity 合法值(枚举单源)与逐键钉死 |
| run16 | VERIFYING_FINDINGS(a0) | `all 6 model findings were dropped as invalid`(6 条 WARN 全为 unknown citation) | 修 #5 生效(JSON 过关、6 条反序列化成功、盲错已带因),断点前移到引用门:`retrieved_context_json` 实证 `evidence:[]`(e2e 项目零知识入库,合法姿态),**空白名单+强制引用=无解约束**,模型只能编造 citation。修复:白名单为空时零引用 finding 存活、带引用仍丢(防捏造);提示词按知识有无分支(空→"citationIds 必须为空数组",同 run13 claims 修法);生产级强制引用姿态归 r5/r8 |
| run17 | PUBLISHING_RESULT(a0) | `SCM publication failed`(agent_publication 0 行) | **1–6 步全绿,7 条 findings 首次持久化——PRD 字面验收(取证成功+Findings 产出)达成**。发布步对 `apiBaseUrl=null`、无凭证的演示 installation 无条件发起真实 GitHub 投递。修复:无凭证 installation 显式跳过远端投递(渲染+发布记录照常落库,message 写明 skipped,WARN 留痕,幂等保持),生产有凭证路径零改动;catch 盲错补定界 cause(run15 教训同类) |

模式沉淀(七截同类):**验证器/解析器强制执行的每一条约束,要么写进提示词(第一道),
要么服务端裁剪/重试兜底(第二道),缺一即断**。语义归模型、基础设施约束服务端强制的
边界在本批被反复验证。另沉淀:同一职责的第二条代码路径(本批为模型输出解析)必须
与第一条共享防线实现,否则防线只护住一半流量;合法部署姿态(零知识项目、无凭证
installation)不得被生产强约束打成 FAILED,应显式降级并留痕。

## 六、run18 终局验收(2026-08-09,全链路首次贯通)

- **run18 = COMPLETED**,1–7 步全部 SUCCEEDED 且全部 attempt=0(无一步靠重试);
  终态标记步(sequence 8, step_type=COMPLETED)按状态机设计驻留 RUNNING。
- **Findings:6 条持久化**(`agent_finding`),VERIFYING_FINDINGS 在零知识姿态下
  按修 #6 契约产出 diff 锚定发现。
- **发布记录:`agent_publication` status=PUBLISHED,message =
  `SCM delivery skipped: installation has no credential (local demo posture)`**——
  渲染+落库完成、远端投递显式跳过留痕(修 #7 语义)。
- **归档生命周期闭环实证**:run18 的 `agent-run-18-….tar` 已被发布步就地清理,
  `/app/archives` 仅剩 FAILED 的 run15/16/17 归档(按 ADR 设计保留供续跑,TTL 兜底)。
- runner 日志近 60 分钟 `SecurityException|ENVIRONMENT_INCOMPLETE` 计数 0。
- 统一测试:backend **573 通过 / 0 失败 / 跳 3**;sandbox-runner verify **75 通过 / 0 失败**。

对照 prd.md Acceptance Criteria:契约测试存在且双向金标通过 ✅;端到端演示无
SecurityException / ENVIRONMENT_INCOMPLETE、Findings 正常产出 ✅(且 Run 达 COMPLETED);
归档真实产生与消费(生产者 `WorkspaceArchiveService`,消费者 runner 契约 parse,
卷挂载 backend rw / runner ro)✅;生产 profile 缺 `SANDBOX_TOOL_IMAGE` 启动失败、
dev 不受影响 ✅(单测+部署实测);backend 与 sandbox-runner 全测试绿 ✅。
