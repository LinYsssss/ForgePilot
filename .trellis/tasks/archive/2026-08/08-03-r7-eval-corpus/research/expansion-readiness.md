# Research: r7 扩充就绪度——PRD 逐条对照 + 素材来源 + 风险清单（2/2）

- **Query**: r7 PRD 要求逐条对照现状（已具备/需扩展/完全缺失）；30-50 例素材来源建议；风险与开放问题
- **Scope**: internal + PRD 对照
- **Date**: 2026-08-10
- 现状细节与代码路径见同目录 `corpus-inventory.md`（下文引用记作【盘点 §N】）

---

## 1. PRD 逐条对照表

| # | PRD 要求 | 现状 | 判定 |
|---|---|---|---|
| R1 | 安全类 8-12 例 | 现有可归入安全类的仅 1-2 例（typescript-known-patch 路径穿越；prompt-injection-comment 是 Agent 行为安全，不是"审查抓缺陷"取向） | **需扩展**（缺 6-11 例） |
| R1 | 业务规则破坏 6-10 例 | 0 例 | **完全缺失** |
| R1 | 工程质量 6-10 例 | 2 例（java-sql-resource-leak、typescript-ambiguous-null） | **需扩展**（缺 4-8 例） |
| R1 | 漏报专项（大 diff 埋单一深藏缺陷）6-8 例 | 0 例；现有 fixture 全部 ≤8 行，形态相反 | **完全缺失** |
| R1 | 误报专项（完全无缺陷正常 diff）4-6 例 | 2 例形态吻合（python-safe-parameterization、java-broken-build；prompt-injection-comment 亦零 finding 但考点是注入） | **需扩展**（缺 2-4 例） |
| R2 | 标注含 line **区间** | `ExpectedFinding.line` 是单点 int【盘点 §2】 | **需扩展**：schema 加字段（如 `lineEnd`），且必须同步改 Java record（见风险 3） |
| R2 | 允许的等价表述 | 无此字段；`nonFindings` 是另一语义（不许报清单） | **完全缺失** |
| R2 | 判定规则（文件+类别+行区间三要素命中） | **零实现**：全仓无匹配代码【盘点 §5】；且三套类别词表互不重合【盘点 §7】 | **完全缺失**（需成文口径 + 等价类映射；可人工执行或补最小脚本） |
| R2 | 漏报率/误报率独立呈报、禁合成分数 | 口径可由现有 recall/precision 换算（漏报率=1−recall、误报率=1−precision）；但现有 `falsePositiveRate` 是 FP/(FP+TN)，**不是** PRD 误报率；无任何计数来源 | **需扩展**（口径成文 + 计数产生方式） |
| R2 | manifest 确定性校验、新用例全过 | `EvaluationCorpusService` + `EvaluationCorpusServiceTest` 机制健全，新 case 按现 schema 添加即被覆盖【盘点 §4】 | **已具备**（新增字段时见风险 3） |
| R3 | 真实模型（MiMo）基线跑分留档 | 运行链路存在且服务器已配 MiMo【盘点 §6】；但 (a) 用例无 diff 表示、无法被审查入口消费 (b) 无跑分驱动器 (c) baseline.{json,md} 是合成数 (d) chat client temperature 硬编码 0.2 与 fixedRun=0 冲突 | **完全缺失**（跑分这件事从未发生过） |
| R3 | 记录模型名与日期 | `ai_call_log` 会记真实 token/时延，可作留档佐证；manifest `fixedRun.model` 现值 `gpt-5` 陈旧 | **需扩展**（结果文档记录 mimo-v2.5-pro + 日期；fixedRun.model 是否更新是开放问题 7） |
| 硬约束 | 素材脱敏 | 现 6 例全合成、demo-repos 素材本就是虚构业务；本仓真实修复（be59ed8）按 PRD 允许"脱敏改编" | **已具备**（新素材按 §3 脱敏注意点执行） |
| 硬约束 | demo-repos 与原 6 例零改动 | 有单源纪律 spec 保护（demo-assets-and-claims.md）；评测用例必须独立副本 | **已具备**（执行纪律，验证命令见 PRD） |
| 硬约束 | 标注"预期最小集" | 现 3 条 expected findings 均单点明确，符合精神 | **已具备**（新标注沿用） |
| Validation | `python3 evaluation/validate_corpus.py` / `evaluation/README` | **两者都不存在**；实际入口是 backend mvn test（EvaluationCorpusServiceTest）+ `scripts/run-agent-evaluation.ps1`【盘点 §4】 | **口径需修正**（要么建 README 指明真实入口，要么补一个 python 校验器——后者算新机制，需主 agent 裁决） |

数量缺口汇总：现有 6 例中能计入五类配额的约 4-5 例，达到下限 30 需新增 **≥25 例**，达到五类下限配比（8+6+6+6+4=30）同样如此；上限口径（12+10+10+8+6=46）需新增 ~40 例。

---

## 2. 素材来源建议（每类 2-3 个可执行方向）

> 前提共识：demo-repos 素材一律**复制改编**成独立副本（可以改名、改域词），原仓与 patch 零接触。

### A. 安全类（8-12 例）

1. **demo-repos 缺陷切片改编**（最高性价比，答案齐全）：从三个 patch 里按缺陷编号切成单缺陷小 diff——越权类 M7/M8（管理接口无 ADMIN 校验、无归属校验）、T1/T3（tenant_id 取自请求参数/更新缺租户过滤）、T9（JWT 不验签）；注入类 M5/P10/T5（SQL 拼接三种语言形态）、T14/T15（XSS innerHTML+内联 onclick）；密钥泄漏类 T13（前端硬编码 API Key）。位置/行级答案直接抄 `docs/演示素材与缺陷对照表.md` 第四节。
2. **本仓真实修复反演**：`be59ed8` CSRF 案例——把修复 diff 反向（引入"CsrfAuthenticationStrategy 清 cookie 未换 NullAuthenticatedSessionStrategy"缺陷）+ 类名/包名脱敏改编；这是 PRD 点名的素材。
3. **常见漏洞模式手工构造补缺**：现有 typescript-known-patch（路径穿越）就是这种手法的先例；CSRF token 缺失、回调不验签（可参考 P7 形态但换域）、密钥进日志等 OWASP 常见形态，按现 fixture 的"极小文件"风格写。

### B. 业务规则破坏（6-10 例）

1. **payment-settlement-service 规则场景**（最密集）：P1 费率硬编码与文档 0.6% 不符、P3 四舍五入 vs 文档向下取整、P4 未校验最小结算净额 100 分、P6 币种未校验、P14 金额强转截断、P15 状态机绕过——每条都有 `settlement-rules.md` 对应条款可裁剪为用例私有知识文档。
2. **mall-order-service 订单流程**：M1（只判 status 不判 pay_status）、M4（金额用 double）、M10（状态跳变未写 shipped_at）——判据在 `order-flow.md`/`db-schema.md`。
3. **构造方式注意**：这类缺陷"只有读了文档才成立"，用例需自带裁剪后的规则文档（先例：prompt-injection-comment 的 `knowledge/` 子目录【盘点 §1】）；跑分时这些文档要能进上下文（见风险 10）。

### C. 工程质量（6-10 例）

1. **审计发现改编**（PRD 点名）：F-02 → "Bean 构造器里 `Files.createDirectories(默认绝对路径)`，非 root 环境必炸"（`.trellis/spec/backend/quality-guidelines.md:24-46` 有完整叙事与代码样式）；F-03 → "同一编码逻辑两处实现，一处改一处没改"的契约漂移 diff（`agent-model-contracts.md:98-111`）。
2. **r2 战役衍生形态**：`AgentFindingModelService` 缺共享 fence-stripper 那类"第二条解析路径绕过共享防御"（agent-model-contracts.md run15）；事务边界 `@Transactional` 自调用（prompt-management.md 规则一里的可验证清单示例）。
3. **既有形态变体**：资源泄漏（java-sql-resource-leak 的 try-with-resources 缺失换语言/换资源类型：文件句柄、HTTP client）、空指针（typescript-ambiguous-null 的 Java Optional.get 形态）。

### D. 漏报专项（6-8 例，人工构造）

1. **"无害化大 diff + 单点回植"**：取 payment 的 302 行 patch 复制改编——把 15 条缺陷全部修正得到"干净大改动"，再回植 1 条不显眼缺陷（如 P14 的 `(long)(amountYuan * 100)`）。既有大 diff 真实感，又有精确答案。
2. **本仓真实重构提交做载体**：r5 批A/B/C 的纯移动重构提交（955a088、b0e7514 等，本就是"行为零变化"的大 diff）脱敏改编为载体，中间埋一处缺陷（如删掉一个判空）。
3. **构造约束**（来自链路事实【盘点 §6】）：单片上限 20000 字符、40 文件——大 diff 会被 DiffSplitter 切片分别审查，"深藏"的缺陷必须完整落在一个切片内，且缺陷不能依赖跨切片上下文才能识别；建议 3-8 文件、8k-18k 字符量级（单片内最大化"稻草堆"）。

### E. 误报专项（4-6 例）

1. **"看着危险实则正确"**：python-safe-parameterization 的既有手法推广——参数化 SQL、白名单后的 ORDER BY、带 try-with-resources 的资源使用、有 @PreAuthorize 的管理接口（与安全类用例互为镜像，专测"忍得住"）。
2. **本仓真实正常提交脱敏**（PRD 点名）：r5 的 move-only 重构、e7d350e 这类 chore 提交，改包名/类名脱敏后作为"正常 diff"。
3. **demo-repos main 基线切片**：payment/tenant 的 main 分支代码"基本正确"（对照表 §2.2 明说），切一段做正常 diff 素材（同样是复制改编）。

### 脱敏注意点

- 禁真实密钥/个人信息/非本项目真实业务代码（PRD 硬约束）。demo-repos 与现 6 例均为虚构，安全；**本仓真实提交改编时**去掉：真实包名 `com.example.codereview` 换虚构域、贡献者姓名/邮箱、内部路径（`F:\202605New` 这类）、commit 引用。
- 用例里如需"硬编码密钥"缺陷,密钥值造假且形如假值（如 `sk-demo-000...`），避免扫描器误报真实泄漏。
- 知识文档裁剪副本不携带 demo-repos 的事故编号体系原文（INC-/BUG- 编号可换新前缀），避免评测集与演示资产在叙事上互相污染。

---

## 3. 风险与开放问题（按阻塞度排序）

1. **【阻塞级】判分执行器不存在**：命中匹配、TP/FP/FN 计数、两率计算全无实现【盘点 §5】。PRD Out-of-Scope 写"评测执行框架改造（现有机制够用）"，但"现有机制"只有 manifest 校验 + 指标算术纯函数——**跑分中段是空的**。验收只要求"两指标可独立计算、口径成文、抽查 5 例可人工复算"，故最小解可以是"人工判定协议 + 计分表"；但 50 例 × 每例多条 findings 的全量基线人工匹配量在百次级，且 r8 要反复跑对比。开放问题：补最小 scorer 脚本算不算"框架改造"？需主 agent/用户裁决。
2. **【阻塞级】用例没有可执行的 diff 表示**：现 fixture 是单态文件树，审查入口只吃"git 仓库 + commitId"，无直接投 diff 的 API【盘点 §6】。跑基线必须把每例变成可审查对象。可行方向：仿 `init-demo-repos.sh` 的确定性重建脚本，把每例做成 base→head 两提交的临时 git 仓库（新用例存 base/ + head/ 两态或 .patch，向后兼容：老 6 例可视为"全新增文件"diff）。这是 r7 设计里最大的结构决策，PRD 未明说。
3. **【设计约束】manifest 加字段会打爆现校验**：`EvaluationCorpusServiceTest` 用裸 `new ObjectMapper()`（FAIL_ON_UNKNOWN_PROPERTIES 默认开启）对 record 做 `treeToValue`——给 manifest 的 case/finding 对象加任何新 JSON 键（如 `lineEnd`、`equivalents`）都会直接 UnrecognizedPropertyException → "evaluation manifest is unreadable"。所谓"向后兼容扩展字段"**必然伴随 EvaluationReport record + EvaluationCorpusService 的 Java 改动**（PRD 允许，但要有预期：这不是纯数据工作）。
4. **【设计约束】类别词表三方错位**：标注词表 vs chat prompt 七枚举 vs Agent 自由串互不重合【盘点 §7】。命中规则若做严格串匹配，漏报率会被词表 artifacts 灌水（模型报 SQL_INJECTION、标注写 SQLI 就算漏报）。"允许的等价表述"字段必须覆盖类别等价类；建议标注类别直接采用 chat prompt 的七枚举为主词表（模型被 prompt 约束只会输出这七个），否则每例都要写映射。
5. **【可信度】temperature 冲突**：manifest 强制 0，但真实跑分走的 `OpenAiCompatibleReviewClient` 硬编码 0.2（`:65`）【盘点 §6】。基线"确定性"口径受损：要么如实记录 0.2（写进跑分档案），要么改代码对齐（一行改动但属行为变更，r6 正用此链路截图——改动需排期在 r6 之后）。开放问题。
6. **【成本量级】6→50 例的调用量**：传统路径每例 1 片 ≈ 1 次模型调用；漏报专项大 diff 每例 2-3 片。全量一轮 ≈ **55-75 次 MiMo 调用**；单次 prompt ≈ 模板 1.5k 字符 + RAG ≤6k 字符 + diff（小例 1-3k、大例 ≤20k）→ 粗估全轮 0.3-0.6M token（**不确定**：MiMo 计费单价仓内无记载；ai_call_log 会留真实数）。时间量级：读超时上限 300s/次，串行最坏 ~5-6 小时，常规应在 1 小时内。r8 要求"每项改动跑对比"，此成本会 ×N 次——量级可接受但不是免费。
7. **【元数据漂移】fixedRun 与实际运行脱节**：`model: "gpt-5"`、`promptVersion: pr-gatekeeper-v1`（注册表实为 review-v1、chat prompt 根本未版本化）、`toolImage` 假 digest。校验器不比对实际运行时,这些是"声明"而非"事实"。基线留档必须以 ai_call_log/结果文档记实际值；manifest 的 model 字段改不改是开放问题（改 = 原 6 例所在 manifest 变更,与"原 6 例零改动"的字面口径需澄清:PRD 验证命令只 diff 了 `evaluation/cases/{原6例}` 目录,manifest 本身必然要改以加新用例）。
8. **【指标语义】现有 QualityGate 阈值会把基线判 FAIL**：recall≥0.80 等门槛是终态目标,未调优 MiMo 首跑大概率不过——若复用 `EvaluationReportExporter`,产出会带 "Gate: FAIL" 字样。基线的用途是"原点数字"而非门禁,落档口径要写清,避免误读。另 `falsePositiveRate`（FP/(FP+TN)）与 PRD 误报率定义不同,报告里两者并存会造成口径混乱——落档文档必须显式给出漏报率/误报率的计算式与计数来源。
9. **【流程】results/.gitignore 白名单**：`evaluation/results/` 只放行 baseline.json/baseline.md,新跑分文件默认被忽略。PRD 要求基线"落档本任务目录"（`.trellis/tasks/08-03-r7-eval-corpus/`）——两处口径需在实施时定死（建议：任务目录为准,results/ 若要留副本需扩白名单）。
10. **【知识依赖】业务规则类用例的文档注入路径**：B 类缺陷只有模型读到规则文档才可能命中。运行时文档来自"项目知识库上传 + INDEXED",不会自动读用例的 `knowledge/` 子目录;且服务器全量注入上下文截断在 `RAG_MAX_CONTEXT_CHARS=6000` 字符——每例文档必须裁得很小,跑分流程必须包含"按例上传文档"步骤（或每例一个项目）。这直接影响跑分脚本/操作手册的形态。
11. **【split 纪律】**：校验器强制 development/holdout 双侧非空,新用例的分配比例 PRD 未规定。r8 要拿评测门禁做调优,若全放 development,holdout 就失去防过拟合意义——建议扩充时按类别分层保留 holdout 份额（开放问题:比例)。
12. **【并行约束】**（用户已授权的边界,记录为执行事实): 部署栈正被 r6 截图占用,基线跑分（唯一需要动运行时的一步）必须等 r6 归档;r7 的语料构造/标注/脚本编写全部可离线先行。
13. **【素材一致性】漏报/误报专项与 mock 的交互**：demo/mock 环境跑评测集会天然全灭（mock 无行号、不读文档【盘点 §6】)——文档要预防"有人用 mock 跑出 0 分当成回归"的误用,跑分口径必须绑定 `AI_PROVIDER=openai-compatible`。

## Caveats

- 风险 3 的 Jackson 行为（裸 ObjectMapper 对 record 未知字段抛异常）基于 Jackson databind 默认值判断,未实际运行验证（任务禁运行时调用)——实施首日建议用一次 mvn test 快速证实。
- 调用量/token 估算是纸面推演,置信度中等;MiMo 单价未知,成本金额无法给出。
- "原 6 例零改动"与"manifest 必然变更"之间的字面张力（风险 7）是 PRD 措辞层面的,按验证命令的实际范围（只 diff cases 目录）理解应无冲突,但建议实施前与用户确认口径。
