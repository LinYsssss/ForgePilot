# R2 实现笔记：类型化审查清单（代码实现部分）

- **日期**: 2026-08-13
- **执行范围**: implement.md 步骤 2「R2 类型清单」的代码实现；权威规格 = `research/r2-checklist-evidence.md`（§2.1/§2.2/§3.2-§3.5/§2.4/§4）
- **状态**: 代码与测试完成、backend 全量 verify 绿。**评测门禁未执行**——本机无真实模型可用，不做、不假装做；合入前必须先在服务器完成 §5 的复跑清单（prompt 规范规则三）。

---

## 1. 文件清单

**新增（backend）**

| 文件 | 内容 |
|---|---|
| `backend/src/main/resources/prompts/chat/checklist-java-v1.txt` | Java 清单 10 条（§2.1 J1-J10 逐字） |
| `backend/src/main/resources/prompts/chat/checklist-ts-v1.txt` | TS/JS 清单 3 条（§2.2 T1-T3 逐字） |
| `backend/src/main/resources/prompts/chat/checklist-generic-v1.txt` | v1 六大类原文迁入（回落，保证不留空段） |
| `backend/src/main/resources/prompts/chat/review-task-v2.txt` | 任务层 v2：六大类硬编码块 → 清单槽，其余与 v1 一字不差 |
| `backend/src/test/java/com/example/codereview/agent/prompt/ChatReviewChecklistInjectionTest.java` | 注入行为测试 8 例 |

**修改（backend）**

| 文件 | 变更 |
|---|---|
| `agent/prompt/PromptTemplateRegistry.java` | +4 注册项（task-v2 + 三份清单）；v1 保留在册（行为资产，防不可比） |
| `agent/prompt/AgentPromptAssembler.java` | 任务层指向 v2；`chatChecklistVersions()` 复用 `DiffSplitter.splitByFile` 做分片粒度类型判定；`ChatReviewPrompt` 增组件 `checklistTemplateVersions` |
| `ai/OpenAiCompatibleReviewClient.java` | 组装日志行附 `checklists={}`（版本可追溯，promptHash↔版本↔评测数字互查锚点） |
| `agent/prompt/PromptTemplateRegistryTest.java` | LAYER_TEMPLATES 扩至 7；四个新模板的末尾结构换行断言 |
| `agent/prompt/ChatReviewPromptGoldenTest.java` | 重做为 v2 全文钉死（9 例）；generic 回落路径仍与 legacy（旧内联文本）逐字节相同 |
| `git/GitCliServiceProcessTest.java` | 与 R2 无关的环境性修正，见 §4 |

**未动**（硬边界确认）：`frontend/`、`evaluation/cases/`、`evaluation/manifest.json`、`demo-repos/`、`EvaluationCorpusService`、评测重试参数、agent 管线模板（§4 裁决不同步——`prompts/agent/**` 零改动）。无新依赖。

## 2. 机制要点（方案 A 落地形态）

- **唯一拼装点保持**：类型判定与清单注入全部在 `assembleChatReview` 内部，零接口改动；`OpenAiCompatibleReviewClient.review(diffText, ragContext)` 签名不变。
- **分片粒度免费获得**：review() 每次收到的就是单个分片，`splitByFile(chunkText)` 恰好给出该分片文件集；混合 PR 中纯 Java 分片不背 TS 清单（golden `userMessageMatchesPerShardChecklistForShardStyleDiff` 钉死）。
- **扩展名映射（§3.2）**：`java`=.java；`frontend-ts`=.vue/.ts/.tsx/.js/.jsx/.mjs；其余（.py/.md/.sql/.yml/未识别/无文件头分片）回落 generic。sql-migration/config 不建文件——弹药为零，建了也永不触发、必进规则四退役候选（机制层理由已写进代码注释）。
- **有序并集**：固定 java > frontend-ts，与文件在 diff 中出现顺序无关（确定性）；并集拼接符 `"\n"`，各清单自带末尾换行 → 块间恰一空行。
- **字节等价延续**：v2 模板 + generic 清单的组装产出与 v1（=重构前内联文本）逐字节相同——golden 测试双向钉死（组装器输出 vs legacy 生成器；goldenV2+generic vs legacy 生成器自证）。R2 的全部内容性 delta 被隔离在「类型命中的清单块」内，评测对比测的就是清单效应本身。
- **字符预算（§3.4）**：清单是任务层模板内静态文本，不占 `RAG_MAX_CONTEXT_CHARS`、不入 ai_call_log 的 promptChars 记账（与旧六大类一致，零代码改动即合规）；单分片最大注入 java+ts ≈ 1.2k 字符，相对 20000/28000 无压力。清单本身永不被截断（注入测试断言首尾条目完整在场）。
- **判分兼容（§3.5）**：13 条措辞均不指定输出 category，七枚举不扩；与研究稿逐条字节比对通过（脚本核对含标点/引号/em-dash 字形）。

## 3. 分叉决策（研究稿未拍死处，含备选与理由）

1. **文件/注册键名 `checklist-ts-v1`**：dispatch 指定 `checklist-ts-v1.txt`，研究稿 §3.3 写 `checklist-frontend-ts-v1.txt`。按 dispatch（直接指令，且更短）；`frontend-ts` 类型键概念保留在扩展名常量与注释中。备选（frontend-ts 全名）无行为差异。
2. **holdout-only 条目（J4/J10/T2）准入**：dispatch「正好这 13 条」已含三条 → 准入。处置组合 = §2.4 选项 (a)+(c)：措辞保持家族级通用（照抄研究稿即满足）+ 模板头注释标注 holdout-only 条目编号，供规则四退役追踪与过拟合排查时区别对待。
3. **条目保留反引号**：「一字不差」的最严格读法（反引号也是字）；prompt 中 code span 对模型无害且常见。备选（去反引号）被否——引入不必要的偏离举证义务。
4. **编号样式 `1.`-`10.`**：沿用 v1 六大类样式；模板条目 N 与研究稿 J N / T N 顺序一一对应，背书映射查研究稿表即可，不另建映射文件。
5. **清单块标题**：`审查重点（Java）：`/`审查重点（TS/JS）：`；generic 保留原 `审查重点：`（generic 路径字节等价所需，一个字都不能动）。
6. **扩展名判定细则**：取路径末段最后一个 `.` 之后、`Locale.ROOT` 小写（`.JAVA` 也命中；dotfile/无扩展名 → 不命中 → 回落）。研究稿未规定大小写口径，选宽松且确定的。
7. **引号字形订正**：研究稿 J8/J10/T1 的引号实测为 ASCII 直引号（0x22）而非中文弯引号（首稿誊抄误用弯引号，已按 0x22 订正并脚本复核）；`——` 为 U+2014×2 无误。
8. **`chat-review-task-v1` 保留在册**：注册表不删旧版本，旧版可随时按版本号复载（评测复现/回滚需要）。

## 4. 偏离清单（目标为零；实际一项，性质为环境阻断而非规格偏离）

- **`GitCliServiceProcessTest.sanitizeRedactsTokenInlineCredentialsAndAskpassPath` 修正**：该测试硬编码 POSIX 路径字面量，而 `GitCommandRunner.sanitize` 按 `Path.toString()` 原文替换——Windows 下 `Path.of("/tmp/...")` 渲染为反斜杠，替换永不命中，测试恒定误报失败（CI 与既往审计均在 Linux 执行，故从未暴露；与 R2 改动完全无关，但阻断本任务「verify 绿」完成门）。最小修正：模拟输出改由 Path 自身渲染拼接——Linux 下产物与原字面量逐字相同，被测契约不变，无产品代码改动。备选 `@DisabledOnOs(WINDOWS)` 被否（会白白跳过一条纯逻辑测试）。
- 对研究稿 §2/§3 的实现偏离：**零**。

## 5. 评测门禁待办（服务器复跑清单——R2 完成门的另一半，未跑不得合入）

- [ ] 服务器全量评测：真实模型（manifest `fixedRun`: z-ai/glm-5.2、temperature 0），评测重试参数保持 15s/4 次不动（可比性）；对比对象 = r7 基线（R1 为字节等价搬迁，r7 基线数字对 R1 直接有效）。
- [ ] 两率独立呈报：**漏报率不升**方可合入（规则三红线）；误报率变化如实记录，禁止合成单一分数。结果 markdown 落任务目录 `eval-runs/`（design.md 约定），提交说明引用文件名。
- [ ] **重点观察：`sec-java-*` 六例**（r7 收尾新增，晚于本研究稿的语料盘点——研究稿 Caveat 5 预警的情形已发生）。java 清单十条无 SQL 注入/越权/路径穿越条目，而 v2 在 java 分片上用十条**替换**了含「SQL 注入风险/权限校验缺失」的六大类；这六例（2×SQL_INJECTION、3×AUTH_RISK、1×PATH_TRAVERSAL，全 JAVA）是本次变更最可能的漏报回归点。若漏报升：按规则三回炉，候选处置 = 以 sec 用例为准入依据经 R6 回灌流程补安全类条目（≤10 名额内权衡或触发退役清理），而非临时手写无背书条目。
- [ ] holdout 侧单独复核 J4/J10/T2 的命中情况（研究稿 §2.4 的过拟合探针）。
- [ ] 命中归因按条目记录一轮（规则四退役追踪的第 1/N 轮起点）。

## 6. 验证记录（本机可做的部分）

- `mvn -s .mvn/settings.xml verify`（backend/，Windows 11 + JDK 17.0.2 + Maven 3.9.9）：**Tests run: 619, Failures: 0, Errors: 0, Skipped: 6**（跳过项为 Testcontainers 门控集成测试，与既档口径一致），BUILD SUCCESS。
- 新增测试：`ChatReviewChecklistInjectionTest` 8 例；`ChatReviewPromptGoldenTest` 6→9 例（v2 全文钉死 + generic/legacy 等价保留）；`PromptTemplateRegistryTest` 断言扩充。
- 模板字节预验证（构建外脚本复核，已清理）：`v2+generic == v1` 逐字节成立；13 条与研究稿表列逐条字节相同；三份清单末尾结构换行在位；全部模板文件无 CR（`.gitattributes` 已有 `prompts/** eol=lf` 保护，R1 遗产）。
