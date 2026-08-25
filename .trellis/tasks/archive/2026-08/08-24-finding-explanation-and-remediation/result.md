# Result — Finding explanation, remediation advice, and model confidence

任务：`08-24-finding-explanation-and-remediation`（T-010，D021，V9）
完成日期：2026-08-24
验收结论：**通过**

## 交付

| 层 | 内容 |
|---|---|
| 契约 | `BATCH_SCHEMA` 与 `SYNTHESIS_SCHEMA` 各加 `explanation` / `suggestion` / `confidence`（各 2000 字符上限），两处逐字相同；`ReviewPrompts.VERSION` 升为 `"review-2"` |
| 数据 | `V9__finding_explanation.sql` 给 `finding` 追加 `category` / `explanation` / `suggestion` / `confidence` 四列 + 两个容忍 NULL 的 CHECK。**不新增表**，业务表仍 19 张，Flyway 8 → 9 |
| 校验 | `ReviewOutputValidator` 四种处置全走 `warnings`，任何新情形都不丢弃整条 Finding；词表映射在写库之前完成 |
| API | `ReviewViews.FindingView` 加四个组件，映射点 `ReviewDecisionService` 唯一；无新端点、无授权面变化 |
| 前端 | `FindingCard.vue` 改为「说明 → 证据 → 建议」；置信度徽章接真实数据；类别徽章；三个哈希收进 `<details>` 可核验性折叠区 |
| 文档 | D021；ARCHITECTURE / PRD / README / IMPLEMENTATION-PLAN / AGENTS / CLAUDE / TEST-ISSUES 同步；`.trellis/spec/backend/database-guidelines.md` 补两条迁移知识 |

## 自动化证据

- 后端 `./mvnw -B -ntp verify`：**323 passed / 0 failed / 0 skipped**（此前 317，新增 6 条）。
- 前端 `lint` / `typecheck` / `test` / `build`：**全部通过，35 测试零跳过**。
- 本机无 JDK，后端走 `DEFENSE-GUIDE.md:27` 的固定容器路径（`eclipse-temurin:21-jdk`）。

新增的 6 条测试各自锁定一个不变量：哈希不变性（红线）、两处 schema 不漂移、校验器四种处置、全词表贯通落库并防参数调换、V9 在**非空**库上的升级路径、前端渲染层次与哈希折叠。刻意未写的：getter、四列可空性单测、category 逐值断言、置信度逐档渲染。

## 真实部署验证

迁移前备份：`/root/fp-demo-pre-v9-20260824.sql`（416K）。

| 项 | 结果 |
|---|---|
| Flyway | 8 → 9，83ms，`success = t`，零错误零告警 |
| Hibernate `ddl-auto: validate` | 通过（即实体四列与库结构一致，否则启动失败） |
| 历史数据 | 8 条 finding 全部保留，四列为空——如实反映「这些行确实没有说明」 |
| **哈希红线** | **8 条 finding 的 `finding_key` / `evidence_hash` / `basis_hash` 全部与迁移前备份逐字节相同**（逐个比对确认） |
| 服务 | 三容器 healthy；`/actuator/health` UP；前端与代理健康检查均 200 |
| 数据卷 | `fp-demo_postgres-data` 未被触碰，postgres 容器未重建 |

## AC15 的记录口径（重要）

AC15 原文要求「真实 PR 重新审查一次」。该步需要 SCM 与模型凭据并消耗模型额度，**本会话未执行**。

**AC15 由用户于 2026-08-24 直接判定验收通过。** 这是验收主体的判定，不是自动化证据，也不是一次被执行并记录的重审。本文件如实区分这两者：上文「自动化证据」与「真实部署验证」两节的每一条都可复现核验，AC15 则是人工判定。任务 `evidence/` 目录因此不存在。

正式评测的配置冻结、语料清单、holdout 台账与原始输出**全程未触碰、未重跑**。

## 实施中发现的、原 PRD 未记录的三条

1. **两个版本号朝相反方向移动。** `ReviewPrompts.VERSION` 因 schema 变更必须升为 `"review-2"`（其自身 javadoc 的要求），`FindingKeys.RULE_VERSION` 必须保持 `"1"`（无确定性规则变化；递增会改写全部 `basis_hash` 并丢弃全部继承抑制项）。原 R3 只锁后者。
2. **`category` 落库若加 CHECK 而校验器不先做词表映射，模型幻觉值会中止整批插入**，不是单行。故词表映射发生在写库之前、越界存 NULL，而 `finding_key` 继续用模型给的原始字符串。
3. **`ADD CONSTRAINT ... CHECK` 会立即校验既有行**，所以不容忍 NULL 的 CHECK 在空测试库上通过、却会让迁移在有数据的部署库上直接失败。`FoundationDatabaseTest` 跑空库，结构上看不到这类失败；`V9MigrationTest` 是覆盖它的形态。此条已写入后端 spec。

## 偏离

五处偏离原 `design.md`，理由见该文件第 12 节：字段定名 `explanation` 而非 `description`；哈希不变性测试是改写既有测试而非新写；schema 同步断言改为整节点相等；增写 `V9MigrationTest`；`API.md` 实际无需改动。另 `FINDING_CONFIDENCE_TONES` 刻意未实现——类别与置信度都不是严重度，配色等于凭空造出后端不存在的风险模型。

## 提交

| Hash | Message |
|------|---------|
| `107b794` | feat(backend): record finding explanation, suggestion and confidence |
| `9e56fec` | feat(frontend): surface finding explanation and remediation advice |
| `4f12905` | docs: document finding explanation, suggestion and confidence |
| `5d24375` | chore(trellis): record finding explanation task and database lesson |

分支 `feat/member-directory-scm-identities`，**未推送**。

## 后续

- `08-24-resource-removal-semantics` 用 **V10**（V9 已由本任务占用）。
- `08-24-frontend-ux-remediation` 无迁移。
