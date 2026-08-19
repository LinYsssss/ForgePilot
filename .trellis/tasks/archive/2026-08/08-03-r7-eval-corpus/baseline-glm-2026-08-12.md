# r7 基线档案:z-ai/glm-5.2 @ 2026-08-12(chat 审查路径)

> PRD AC-4「真实模型基线跑分完成并落档」的档案本体。r8 各轮评测对比的唯一基准。
> 判分明细与原始证据同目录:`baseline-runs/scores-2026-08-12.{json,md}`、`baseline-runs/2026-08-12/`(32 例原始 API 响应 + ai-call-log 两 CSV)。

## 结论(两率独立呈报,禁止合成单一分数)

| 指标 | 分子/分母 | 数值 |
| --- | --- | --- |
| **漏报率**(1−recall) | 9/25 | **36.00%** |
| **误报率**(1−precision) | 72/88 | **81.82%** |

分 split:development 33.33% / 81.25%(预期 18,模型 findings 64);holdout 42.86% / 83.33%(预期 7,模型 findings 24)。

- 模型:`z-ai/glm-5.2`(deploy/.env `LLM_CHAT_MODEL`;OpenAI 兼容端点)
- 日期:2026-08-12;语料:`pr-gatekeeper-eval-v1` 32 例(dev 22 / holdout 10),全部跑成,未跑成 0
- temperature 实值:**0.0**——`app.ai.temperature` 默认值(`AI_TEMPERATURE` 未设置);本轮起 chat 路径(`OpenAiCompatibleReviewClient`,commit 3f53398)与 langchain4j 路径同键同默认,manifest `fixedRun.temperature=0` 自此对两条路径都成立
- 提示词:`chat-review-{system,project,task}-v1`(r8-R1 分层模板,与 R1 前内联文本字节等价——golden 测试钉死,故本基线同时是 R1 前/后的共同基线)
- 匹配规则:`d3-v1`(README「两率计算口径」全文);类别别名表版本:三条(RESOURCE_LEAK/NULLABILITY/PATH_TRAVERSAL)
- **QualityGate 不适用声明**:后端 `EvaluationMetrics`/QualityGate 的 `falsePositiveRate`=FP/(FP+TN) 是不同口径,其阈值是终态门槛,对基线不适用、两侧数字不可比

## 调用与 token 实数(ai_call_log 佐证,CSV 在证据目录)

| 调用类型 | 成功 | 失败 | token(prompt/completion/合计) | 备注 |
| --- | --- | --- | --- | --- |
| CHAT_REVIEW | 32 | 16 | 43,160 / 28,104 / **71,264** | 每例恰 1 次成功调用(无分片);字符量 122,333/82,765,字符↔token ≈2.8:1 |
| EMBEDDING_INDEX | 42 | 0 | —(不计费入 token 列) | 知识文档索引(biz/miss 带 knowledge 例) |
| EMBEDDING_SEARCH | 48 | 9 | — | 检索期查询嵌入;9 次失败即限流事故(下节) |
| MODEL_RISK | 57 | 0 | — | model-service 风险分类器 |

成功调用 p50 时延 40.0s、最大 206.1s(漏报专项巨 diff);D6 纸面预估(55-75 次、0.3-0.6M token)偏保守:实际 32 次成功 chat 调用、7.1 万 token——预估按多分片假设,实测单片covered。

## 运行参数与偏差声明(全部如实)

1. **design D4 写的 `mimo-v2.5-pro` 已过时**:r2 之后 deploy/.env 模型已切换为 `z-ai/glm-5.2`(`.env.bak-r2` 是切换痕迹)。档案与 manifest `fixedRun.model` 以实际为准,本文件名随实际模型取 `baseline-glm-*`。
2. **评测可用性参数**(非模型参数,不影响输出分布):`AI_RETRY_WAIT=15s`、`AI_RETRY_MAX_ATTEMPTS=4`(`evaluation/tools/eval-stack.override.yml`,已入库);r8 各轮沿用同参数保持可比。
3. 运行时旗标:`AI_RUNTIME=langchain4j`——只切 agent 管线/嵌入/检索实现,chat 审查恒走 `OpenAiCompatibleReviewClient`(`LangChain4jRuntime` 包外零引用,已核);RAG 检索(hybrid)+知识库上传照常生效。
4. `fixedRun` 中 toolImage/maxModelCalls/maxToolCalls 是 agent 管线口径,chat 路径不消费,本基线不声明其生效。

## 限流事故记(2026-08-12,已全量收敛)

- 首轮 27/32:嵌入供应商 429(`EMBEDDING_SEARCH` 9 次 RateLimitException)直接杀 3 例(miss-clearing/vcs/pump);连带 `aiReview` 熔断器(COUNT_BASED 窗 10/阈 60%)被打开,级联误杀 2 例(eng-http-client-leak、miss-payhub-refund-cast)。
- `--resume` 一轮补回 4 例;miss-payhub-refund-cast 再挂:**COUNT_BASED 窗口不随时间衰减**,首轮失败残渣仍占窗,它自己一次真实瞬态失败(62.2s 处,ai_call_log id 174)即把失败率顶过阈值,任务四次重试全被熔断拒绝。
- 处置:重试参数改 15s/4 次(上文参数 2)+ 重建 backend 容器(JVM 重启=熔断窗清零)后单例补跑成功。终态 **32/32,失败 0**。
- 后续任务提示:两个教训候选沉淀 spec——(a)评测型长任务与 COUNT_BASED 熔断窗的相性问题;(b)嵌入检索失败即判任务 DEAD 是否过硬(漏报优先原则下可议降级)。

## 分类别裂口(r8 的靶位,数字见 scores-2026-08-12.md)

- **知识文档在起作用**:BUSINESS_RULE_RISK 漏报仅 1/10(有 knowledge/ 判据的业务规则类几乎全中)。
- **清单缺口即漏报**:RESOURCE_LEAK 3/3、PERFORMANCE_RISK 2/2、AUTH_RISK 1/1、PATH_TRAVERSAL 1/1、STARTUP_ASSUMPTION 1/1 全漏——R2 按文件类型清单的弹药方向被基线直接背书。
- **误报主力**:NULL_POINTER 26/30 未匹配(模型见 null 就报)、AUTH_RISK 12/12、TRANSACTION_RISK 11/13——R3 两段式复核的靶子。
- 误报专项 4 例共产出 11 条 findings(全误报),「忍得住不报」当前完全不及格,R2 清单的 nonFindings 护栏措辞 + R3 复核是对症药。

## 附录:5 例人工复算(AC-2,判分器 5/5 一致)

按 d3-v1 手工判定(路径 ∧ 类别∈{标注}∪别名∪equivalents ∧ 行区间相交,贪心 1:1),与 `scores-2026-08-12.md` 逐例行对照:

| 用例 | 手工判定 | 关键推理 | 判分器 | 一致 |
| --- | --- | --- | --- | --- |
| java-sql-resource-leak | 漏 1 / 误 2 | 模型 PERFORMANCE_RISK@4-6 经别名类别可对上,但 [4,6]∩[7,7]=∅,纯行区间判死 | 漏 1 / 误 2 | ✓ |
| biz-fee-rounding-mode(holdout) | 漏 1 / 误 3 | 模型两条 BRR 落 28-29/28-28,与标注 30-32 不相交(贴边不命中,规则锋利度样本) | 漏 1 / 误 3 | ✓ |
| biz-min-net-amount-skipped | 中 1 / 误 3 | BRR@25-31 与 26-32 相交 → 命中;NP/TRANSACTION/AUTH 三条类别不符 | 中 1 / 误 3 | ✓ |
| fp-java-payout-extract-method | 中 0 / 误 3 | 误报专项预期空集,3 条全计误报,nonFindings 违规提示同时点亮 | 中 0 / 误 3 | ✓ |
| miss-ledger-import-leak | 漏 1 / 误 4 | 4 条 findings 全落错文件(Controller/JobService/Validation),埋在 LedgerCsvParser 的泄漏一条未碰 | 漏 1 / 误 4 | ✓ |
