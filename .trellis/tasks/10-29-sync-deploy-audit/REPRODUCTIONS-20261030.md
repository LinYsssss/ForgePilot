# 隔离复现与统计口径复核（2026-10-30）

本轮确认四项缺陷，尚未修改或部署修复代码。测试使用 `76ed32e` 的源码副本；后端使用 JDK 21 和 Testcontainers 临时 PostgreSQL，前端使用现有依赖和模拟 API。两项后端、两项前端观察断言均通过，表示缺陷按设定顺序真实出现，不能解读为修复已通过验收。

## 1. 草稿质量检查旧结果写回（P1）

1. 对原始已保存草稿发起 `RequirementQualityService.check`，读取其正文和 AC。
2. 在模拟 AI 调用返回前，通过真实 `RequirementService.editDraft` 提交新标题、正文和 AC；修订 ID 不变，质量列被清空。
3. 模型返回对原始文本的评估。
4. `store` 无条件按项目及修订 ID 更新，数据库的新草稿重新带上旧评估；接口也成功返回旧结果。

现有 `RequirementQualityTest.anInPlaceDraftEditStillClearsAResultThisCheckWrote` 只覆盖检查完成后编辑的顺序。本轮插入在外部调用返回前提交编辑的顺序，证明原来的测试无法阻止这个问题。

修复应以短事务取得一致的正文/AC 快照，并在短写回事务内比较同一内容的指纹或代次；冲突时返回可操作错误。不能把 120 秒的外部调用放进行锁事务。只比较修订 ID 或标题均不够，也须考虑 AC 单独变化。

## 2. 草稿保存后显示晚到的旧质量报告（P1）

1. 挂载真实 App 与正式 router，发起质量请求并暂缓响应。
2. 保存新草稿，页面更新标题并清除质量报告。
3. 释放原请求的旧报告，`runQualityCheck` 直接赋值；页面在新标题旁显示旧评估。

这是同一页面实例内的竞争，和页面路由切换的实例隔离无关。建议与实现建议现有机制一致，给质量请求增加请求代次；内容保存/发布和卸载时使旧请求失效。后端拒绝旧写回与前端拒绝旧响应都要做。

## 3. 发布新修订后覆盖度和活动不刷新（P2）

真实 App 内发布修订 2 后，标题和版本历史已更新；`coverage` 和 `reviewActivity` 各只在初次加载读取一次，页面仍显示原覆盖度和“审查中”。模拟服务器为新修订准备的空覆盖度和“待审查”未被请求。

应在发布/变更影响派生事实后刷新活动、覆盖度与退回明细，并处理各面板的独立错误；刷新的失败不能继续把旧结果作为当前结果显示。避免用完整 `load()` 清空用户尚未保存的其他输入。

## 4. 首次 PR 接收旧仓库快照（P1，范围较窄）

1. 在尚无 PR 的仓库下取得旧仓库对象和来源于旧仓库的 PR 快照。
2. 经真实 `ScmRepositoryService.update` 提交外部仓库 ID 变更。
3. 调用真实 `PullRequestSyncService.apply(旧对象, 旧快照)`。
4. 入库成功：旧仓库的 PR 标题及 patch 归到新外部仓库 ID 下；指纹还按新 ID 计算。

**指纹可以从新仓库行与旧 patch 重算，不等于快照的真实来源正确。** 现有加锁重读保护插入期间的身份不变，却没有验证拉取时身份与当前身份相同。旧对象来自认证后、Provider 拉取前的阶段；这一阶段不能跨外部调用长期持锁。

修复应在 `apply` 行锁内比较 Provider、规范化实例及外部仓库 ID，与认证/拉取时的身份不符则拒绝旧快照，必要的重拉取放到锁外。凭据正常轮换不应因稳定身份未变而被误拒绝。

现有线上四个仓库均已有 PR，禁止再变更稳定身份；本轮没有观察到线上错归属。这里确认的是隔离测试中的可达缺陷，不是声称线上历史数据已经损坏。

## 5. Halo 标签观察单位

| 口径 | 标签数 | 确认数 | 确认比例 |
|---|---:|---:|---:|
| 每条 Finding 的首次人工判定 | 55 | 42 | 76.4% |
| 每个 PR + finding_key 的首次人工判定 | 44 | 32 | 72.7% |

原口径有 11 条重复身份标签。去重后的 HIGH 为 29/36，MEDIUM 为 3/8。去重不等于独立业务真值：同一个 PR 的多个问题仍可能相关，且行号/类别变化会导致同一语义问题得到不同 key。不能把二项独立抽样前提下的 Wilson 区间当成严格的生产准确率置信保证。

两种口径都混合 review-2/3/4/5，没有 review-6 标签；仅统计已报告的问题，无法衡量漏报。正式冻结评测及 holdout 保持原样。

## 6. 当前版本及数据复核

2026-10-30 再次 fetch 后，本地和 `origin/main` 均为 `76ed32edc3d0c2eec838ab9d4807b355f91849b9`；与运行的 `5021013` 业务提交相比，backend/frontend/compose 没有差异。三个业务容器 healthy，公网健康接口 `UP`。

线上后端 JAR SHA-256 为 `d125780401729bc49b79a236b0d842663fcd6f7efc8a4f41b30228095360abfe`，与前轮取得的部署 JAR 一致；前轮已验证本地 JAR 的非目录 payload 全部相同。ZIP 文件整体哈希与本地构建不相同，不能把整体哈希不同直接称为业务代码不同。

公网、容器和本地前端产物摘要仍一致：JS `9e811ae8bf160af970cc7ae20b33b4c80dcaf1ac4adc6ca1c001656b7de6f48b`，CSS `a95f60175d4ef5509d56bbcc0375940d7d3e57737ffdb24f1212c3a406263142`。重新执行原只读 SQL，业务聚合与发布时完全一致；新复现没有增加线上 AI 调用或业务记录。

## 7. 原始证据

服务器 `/root/forgepilot-reports/release-20261029/` 保留：

- `prepare-race-repros.py`、`RequirementQualityTest-audit.java`、`ScmIdentityFreezeConcurrencyTest-audit.java` 和 `isolated-race-repros.log`。
- `prepare-frontend-repros.py`、`requirement-audit.spec.ts` 和 `isolated-frontend-repros.log`。
- `adjudication-units.sql`、`adjudication-units.txt`、`live-data-20261030.json`。

复现测试是审计观察，不进入正式测试目录。修复时需把它们改为正确行为的回归断言，并补正常请求、无内容变化和仅 AC 改动的对照；不能长期将“旧结果覆盖成功”的断言作为产品契约。
