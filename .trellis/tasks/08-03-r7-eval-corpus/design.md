# Design：评测集扩充 6→30-50 例

> 调研结论(research/)推翻了 PRD 的两个前提:判分执行中段为空、用例格式无法被审查入口消费。
> 本设计在不违背 PRD Out-of-Scope 精神(不改造后端评测框架)的前提下补齐这两块,全部裁决点在此定案。

## D1 判分执行:独立工具链,零后端框架改动

`evaluation/tools/`(新目录)三件套,全部可离线重跑:

1. `build-case-repos.sh` — 确定性把每个用例构建成 base→head 两提交的 git 仓库(输出到工作目录,不入库);老 6 例视为"空 base 全新增"。仿 `init-demo-repos.sh` 手法(固定作者/时间戳,保 SHA 稳定)。
2. `run-baseline.sh` — 跑分驱动器(bash+curl,对隔离栈 API):每例 创建项目 → 绑定 LOCAL 仓库 → (B类)上传用例私有知识文档并等 INDEXED → 建审查任务(commitId=head,baseCommitId=base) → 轮询报告 → 导出 findings JSON 到任务目录。
3. `score.py` — 最小判分器(python3 标准库):读 manifest 标注 + findings JSON,按 D3 命中规则做贪心 1:1 匹配,产出漏报率/误报率(全量+分类别)+ 逐例明细表(json+md)。

不改 `EvaluationMetrics`/`EvaluationReportExporter`(其 QualityGate 阈值是终态门槛,基线必挂 FAIL,徒增误读);两率直接由 score.py 计算并成文口径。`evaluation/README.md` 新建,写清真实校验入口(backend mvn test)与工具链用法,修正 PRD Validation 引用失实的问题。

## D2 用例格式:base/ + head/ 两态文件树(向后兼容)

- 新用例目录:`cases/<id>/base/`、`cases/<id>/head/`、可选 `knowledge/*.md`(B类判据,单文件 ≤2000 字符,总量受 RAG_MAX_CONTEXT_CHARS=6000 约束)。
- manifest case 新增可选字段 `fixtureLayout: "base-head"`(缺省 `"single"` = 老格式,老 6 例零改动)。
- **Java 同步改动**(PRD 允许"扩展字段保持向后兼容";裸 ObjectMapper 未知字段即炸,故必须同步):`EvaluationReport` record 增 `fixtureLayout`、`ExpectedFinding` 增 `lineEnd`(缺省=line)与 `categoryEquivalents`(缺省空);`EvaluationCorpusService.validate` 增:base-head 时两目录必须存在、`lineEnd>=line`;`EvaluationCorpusServiceTest` fixture 随升。实施首日先跑一次 mvn test 证实裸 ObjectMapper 行为(调研 Caveat)。

## D3 命中规则(计算口径,成文进 evaluation/README + 基线档案)

```
hit(f, e) := samePath(f.filePath, e.path)
          ∧ f.category ∈ ({e.category} ∪ globalAlias(e.category) ∪ e.categoryEquivalents)
          ∧ [f.lineStart, f.lineEnd] ∩ [e.line, e.lineEnd] ≠ ∅   (f 行号为 null ⇒ 不命中)
```

- 贪心 1:1 匹配,按(文件,行)排序保证确定性;每个预期至多配一个模型 finding,反之亦然。
- **漏报率 = 未命中预期 / 预期总数;误报率 = 未匹配模型 findings / 模型 findings 总数**。两指标独立呈报(=1−recall / 1−precision 口径,与现有 `falsePositiveRate`=FP/(FP+TN) 是不同定义,档案里显式声明防混淆)。分类别分 split 各出一份。
- 词表主基准 = chat prompt 七枚举(NULL_POINTER/SQL_INJECTION/AUTH_RISK/TRANSACTION_RISK/PERFORMANCE_RISK/BUSINESS_RULE_RISK/UNKNOWN);新标注 category 尽量取自七枚举。双层等价:`tools/category-aliases.json` 全局别名表(兜老 6 例:RESOURCE_LEAK→PERFORMANCE_RISK|UNKNOWN、NULLABILITY→NULL_POINTER、PATH_TRAVERSAL→AUTH_RISK|UNKNOWN,老例零改动即可判分)+ manifest 每例 `categoryEquivalents` 补例外。
- `nonFindings` 维持"不许报"语义:天然计入误报率,另在明细表单列违规行提示。
- mock provider 无行号不读文档,跑分口径**绑定** `AI_PROVIDER=openai-compatible`,README 明示"mock 跑出全灭属误用"。

## D4 基线跑分:隔离栈,不碰演示环境

- `docker compose -p reposage-eval`(独立 project name + 独立卷 + 错开端口,同镜像),MiMo 凭据经环境注入(沿用 deploy/.env 变量名,不落盘不打印);跑完 `down -v` 即弃。演示栈全程零接触(项目列表零污染、无需 backup.sh——那条规则护的是演示库)。
- **temperature 对齐**:`OpenAiCompatibleReviewClient` 硬编码 0.2 → 改读 `app.ai.temperature`(默认 0.0,LangChain4j 路径同名配置已有先例),使 manifest fixedRun.temperature=0 成为事实;一行为变更,带测试,r6 归档后进行。
- 基线档案落 `.trellis/tasks/08-03-r7-eval-corpus/baseline-mimo-<date>.{json,md}`:模型名(mimo-v2.5-pro)/日期/两率(全量+分类别+分 split)/调用与 token 实数(ai_call_log 佐证)/temperature 实值/QualityGate 不适用声明。`evaluation/results/` 白名单不动。
- manifest `fixedRun.model` 更新为 `mimo-v2.5-pro`(manifest 本就因加例必改;"原 6 例零改动"按 PRD 验证命令口径 = `cases/{原6例}` 目录零 diff)。

## D5 用例构成（实际新增 32 例，总量 38 ≥30）

> 实施结果：原计划新增 36 / 总量 42，首轮仅落地业务、工程、漏报与误报四批 26 例；收尾阶段再补 6 个安全正例。最终新增 32、总量 38，安全类计入原有 2 例后共 8 例，达到 PRD 下限但未达到设计阶段的目标值 10。

| 类别 | 数 | 素材(全部独立副本改编,详见 research/expansion-readiness.md §2) |
| --- | --- | --- |
| 安全 | 新增 6（总计 8） | 独立脱敏构造：Java IDOR×2、SQL 注入×2、CSRF×1、路径穿越×1；连同原有 AUTH/PATH_TRAVERSAL 两例达到 PRD 下限 |
| 业务规则 | 8 | payment P1/P3/P4/P6/P14/P15 + mall M1/M4/M10,各带裁剪版规则文档(knowledge/) |
| 工程质量 | 8 | F-02 启动路径假设、F-03 双实现漂移、fence-stripper 旁路、@Transactional 自调用、资源泄漏/空指针变体 |
| 漏报专项 | 6 | 大 diff(8k-18k 字符、3-8 文件,单片内完整)埋单一缺陷:payment patch 无害化+单点回植、r5 纯移动重构脱敏载体 |
| 误报专项 | 4 | "看着危险实则正确"镜像例 + 本仓正常提交脱敏 + demo-repos main 切片 |

- 脱敏纪律照 research §2(假密钥形如假值、包名换虚构域、事故编号换前缀)。
- split:按类别分层 ~70/30(每类 holdout ≥1,漏报专项 ≥2),防 r8 过拟合。
- 标注"预期最小集":每例预期 findings 1 条为主(漏报专项恰 1 条),复合缺陷例外但逐条可指认。

## D6 顺序与依赖

语料构造/标注/Java 字段扩展/工具链全部离线先行(与 r6 收尾并行安全);唯一动运行时的基线跑分排在 r6 归档之后。成本量级(纸面):全量一轮 55-75 次 MiMo 调用、0.3-0.6M token、常规 1 小时内。

## 回滚

评测集纯增量(新目录+manifest 追加+可选字段),回滚=revert 对应提交;Java 字段扩展向后兼容,单独提交可独立 revert;隔离栈用后即弃无残留。
