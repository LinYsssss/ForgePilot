# Implement — Finding explanation, remediation advice, and model confidence

执行清单。技术依据在 `design.md`，需求与验收在 `prd.md`。

**总原则**：最小改动，不写答不上「锁的是哪个不变量」的测试（见 `design.md` 8）。

## 迁移号占用

本任务占 **V9**。`08-24-resource-removal-semantics` 后执行，用 V10。这是三个 08-24 任务之间唯一的硬耦合点，start 本任务即锁定该分配。

## 验证命令

```bash
# 后端（仓库根）
./mvnw -B -ntp verify          # 全绿零 skip

# 前端（frontend/）
npm run lint
npm run typecheck
npm run test
npm run build
```

## 阶段 1 — 契约与哈希红线（后端）

先做这一步，因为它是唯一会造成不可见损坏的部分；红线测试要在任何存储改动之前就位。

1. `ReviewPrompts.java`：`BATCH_SCHEMA`(:59) 与 `SYNTHESIS_SCHEMA`(:104) 的 finding item 各加 `explanation` / `suggestion` / `confidence` 三个属性，两处**逐字相同**；`required` 同步扩充。照抄 `design.md` 5 的字面量。
2. 同文件：`VERSION` 由 `"review-1"` 升为 `"review-2"`（`design.md` 2 —— 这是该常量自身 javadoc 的要求）。
3. **确认 `FindingKeys.java` 一个字符未动**，`RULE_VERSION` 仍为 `"1"`。
4. 写 `design.md` 8.1 的哈希不变性测试：同一条 finding，仅改变 explanation / suggestion / confidence 措辞，断言 `findingKey` / `evidenceHash` / `basisHash` 三者逐字节相同。
5. 扩展既有 `ReviewPipelineIntegrationTest.bothSchemasAreValidJsonAndAgreeOnTheCategoryVocabulary`（:456），断言两处 schema 的新字段声明一致（8.2）。

**闸门**：`./mvnw -B -ntp verify` 全绿。哈希不变性测试必须在此刻已经通过——它锁的东西在后续每一步都不得回退。

**回滚点**：此阶段只改常量与测试，`git checkout` 单文件即可还原。

## 阶段 2 — 校验与落库（后端）

6. `V9__finding_explanation.sql`：照 `design.md` 4 追加四列 + 两个 CHECK。**不改 V1–V8**，不加索引。
7. `ReviewOutputValidator.readFinding`(:169)：
   - 按 `design.md` 6.1 表格实现四种处置，全部走 `warnings`，**任何新情形都不丢弃整条 Finding**；
   - **关键**：category / confidence 映射到闭合词表，越界存 `null`；**哈希继续使用模型给的原始 category 字符串**（`design.md` 4.1）。这一条错了会让幻觉值中止整批插入。
8. `ReviewOutput.FindingCandidate`(:49) 加四个组件；同时**改写 :45-47 的 javadoc**——「这里没有承载模型散文的字段」在本任务后为假（`design.md` 6.3）。
9. `Finding.java` 加四个字段（`updatable = false`）+ 构造器参数 + getter。
10. `ReviewPipeline.java:185` 的 `new Finding(...)` 补四个实参。**逐字核对 explanation 与 suggestion 未调换**（`design.md` 6.2 的明码代价）。
11. 写 8.3（校验器四种处置）与 8.4（落库字段正确、防参数调换）两组测试。

**闸门**：`./mvnw -B -ntp verify` 全绿零 skip；空库 Compose 启动通过；从 V8 升级到 V9 的路径通过，且既有 8 条历史 Finding 升级后仍合法可读（AC4）。

**回滚点**：迁移一旦在真实部署库上跑过就不可撤销。因此**先在空库与 V8 快照上各验一次**，再碰部署库。

## 阶段 3 — API 与前端

12. `ReviewViews.FindingView`(:99) 加四个组件；`ReviewDecisionService.java:223` 映射点补四个实参（全仓唯一映射点）。
13. `frontend/src/features/review/api.ts`：`Finding` 接口加同样四个可空字段；新增 `FindingCategory`、`FindingConfidence` 联合类型。
14. `labels.ts`：加 `FINDING_CATEGORY_LABELS`、`FINDING_CONFIDENCE_LABELS`、`FINDING_CONFIDENCE_TONES`，形态与既有 `FINDING_STATUS_LABELS` / `FINDING_CONTINUITY_TONES` 一致。
15. `FindingCard.vue` 按 `design.md` 7.2 的五点改：说明→证据→建议层次；置信度徽章接真实数据并改写 :123-125 已失效的 field-hint；类别徽章并入 record-head；三个哈希收进 `<details>`；说明/建议显式标为模型判断。
16. 长内容 containment **复用 `.evidence`(:276-290) 既有那组属性**，不造新原语（AC9）。
17. 写 8.5 前端测试（渲染说明与建议、哈希不在默认视图）。

**闸门**：前端 lint / typecheck / test / build 四项全绿零 skip。确认 `tests/routes.spec.ts` 未受影响——本任务不动导航与路由。

## 阶段 4 — 文档与决策

18. 按 `design.md` 10 的表逐项更新文档。注意**表数不变仍 19**，只有迁移数 8 → 9。
19. 写 D021，格式逐条对齐 D020（`**决定**` / `**数据实现**` / `**理由**` / `**明确不做**`），内容覆盖 `design.md` 9 的四点。
20. `docs/v2/TEST-ISSUES.md` 更新 T-010 行状态与任务归属表。

## 阶段 5 — 真实 PR 复审（AC15）

21. 对一个真实 PR 重新触发一次审查，确认新产出的 Finding 在页面上可读、可执行，且既有哈希语义未变。
22. 输出留档到**本任务目录下的 `evidence/`**。

> **它不是正式评测资产**，不受不可变约束；但**绝不可与正式证据混放**。正式评测的配置冻结、语料清单、holdout 台账与原始输出全程不得触碰或重跑（AC16）。

## 阶段 6 — 收尾

23. 最后一次全量 quality check（Phase 2.2 的 final pass），覆盖 backend 与 frontend 两个 spec 层，而不只是最后一段改动。
24. Phase 3.3 spec 更新判断：4.1 的「CHECK 约束会中止整批插入」是本任务发现的、非显然且可复用的知识，**应写入 `.trellis/spec/backend/database-guidelines.md`**，与代码改动同批提交。
25. Phase 3.4 提交：先出工作提交，再让 archive 与 journal 提交落在其后。呈现分组后等确认，**不自动 push**。

## 执行顺序的理由

先契约与红线测试、再存储、最后前端，是因为哈希不变性是唯一「出错后不可见、要到下一轮审查才显现」的失败模式；把它的测试放在最前，后续每一步都跑在这层保护之下。迁移排在校验器之后、前端之前，是因为迁移是本任务唯一不可回滚的动作，必须在词表映射防线（4.1）就位后才执行。
