# Prompt Asset Management(Prompt 资产管理规范)

> 审查质量的上限由 prompt 资产决定,而 prompt 是最容易无治理膨胀的资产。本规范是 r8(提示词调优:分层模板/两段复核/动态 few-shot)的执行前提,五条规则对**所有** prompt/检查清单/few-shot 变更常驻生效。

---

## 现状(规则的作用对象)

- 已有机制:`agent/prompt/PromptTemplateRegistry`(版本化 classpath 模板,当前仅 `review-v1` → `resources/prompts/agent/review-v1.txt`)+ `agent/prompt/AgentPromptAssembler`(信封组装:分节预算截断、秘密脱敏、citation 白名单、`promptHash`)。每次模型调用的 `promptHash` 随 `AgentModelCall` 落库(`V16__agent_model_prompt_hash.sql`),运行可归因到模板版本。
- 待治理:任务指令仍在步骤执行器内联拼接(`PlanningStepExecutor`、`VerifyingFindingsStepExecutor`、`ExecutingToolsStepExecutor`、`GeneratingPatchStepExecutor`),评测 manifest 的 `promptVersion`(`pr-gatekeeper-v1`)与注册表版本(`review-v1`)尚未统一。r8 的分层模板机制将把内联拼接清零——r8 全部工作受本规范约束。
- 既有纪律先例(必须保持):提示词里的数值/枚举约束与校验器同源取值,零字面量——`PlanningStepExecutor` 引 `validator.defaultToolLimit()`、`VerifyingFindingsStepExecutor` 的 `SEVERITY_VALUES` 从枚举生成(细则见 [agent-model-contracts.md](./agent-model-contracts.md))。

---

## 规则一:宁精勿多

- 每个模板单一职责(初审模板不做复核,复核模板禁止发现新问题)。
- 检查清单每份 **≤10 条**,每条**可验证**——能落到行号或规则(如"事务边界:`@Transactional` 方法内是否有自调用"可验证;"注意代码质量"不可验证,不准入)。
- **新增条目必须附"它能抓住什么漏报案例"**:没有对应的评测用例/真实漏报背书,不准入。清单靠案例生长,不靠头脑风暴生长。

## 规则二:漏报 recall-first

- 分层压制:**初审层宁多报,复核层压误报**;规则引擎与分类器是确定性兜底,永不撤除。现有形态即此结构——`VerifyingFindingsStepExecutor` 汇集语言插件规则候选(`language/{java,javascript,python}`)+ 模型候选,过 `AgentFindingPipeline`(去重 → `FindingVerifier` 独立验证 → `GateDecisionService` 门禁);`model-service` 的 `predict_with_rules` 是模型不可用时的规则回退。
- 被复核否决的 finding **保留可追溯**(前端标"已否决"并给出 rejectionReason,不参与阻断),而不是静默删除。
- **禁止以降低召回换误报率好看**:漏报率与误报率是两个独立呈报的指标(r7 评测口径),不许合成单一分数掩盖"召回换精度"。

## 规则三:版本化与评测门禁

- 模板/清单/few-shot 选例逻辑的任何变更,**合入前必须附评测集对比结果**(`evaluation/` 语料,基线见 `evaluation/results/baseline.md`);对比对象是上一版本。
- **漏报率相对上一版本不得上升**——上升的改动回炉或放弃,没有"先合了再说"。
- 每次变更 bump 版本号(注册表键 + manifest `promptVersion` 对齐),使 `promptHash` → 版本 → 评测数字三者可互查。

## 规则四:退役机制

- 连续 N 轮评测(建议 N=3)无捕获贡献(没有任何命中 finding 归因于该条目)的清单条目,标记**退役候选**;定期(每次评测集扩充后)清理退役候选,腾出 ≤10 的名额。
- 退役下线的条目留档(记录条目文本 + 退役理由),防止同一条无效条目反复被"新发明"。

## 规则五:禁承诺红线

- 对外文档、答辩口径、README 一律**禁用"零漏报"及等价表述**(100% 检出/绝不遗漏)。
- 唯一批准口径:**"漏报率实测持续下降 + 多层兜底"**,数字必须来自 `evaluation/results/` 的真实跑分记录。
- 演示素材同规则:`demo-repos/README.md` 的"诚实边界"七条不可削弱(总纪律见 `.trellis/spec/guides/demo-assets-and-claims.md`)。
