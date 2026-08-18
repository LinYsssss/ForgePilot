# Design：P8 实验、答辩与功能冻结

> 本设计继承父任务 `08-16-forgepilot-upgrade` 的 R11/R12 约束，并以 R7/R8 已落库的评测工具与规范为边界。P8 不建立第二套语料、第二套判分器或第二条生产链路。

## 1. 边界、事实源与不可变约束

### 1.1 唯一事实源

- `evaluation/manifest.json` 是 38 例的唯一标注事实源；`expectedFindings`、`nonFindings`、fixture 和 split 不复制到新 JSON。
- P8 task 下的 `eval-runs/` 只保存某次运行的输入快照、原始响应、日志汇总、判分结果和生成摘要；摘要必须由 manifest/运行结果生成，禁止手工维护第二套指标。
- R7 的 `baseline-glm-2026-08-12.*` 是历史 32 例档案，只读保留；P8 38 例新基线使用独立 run id 和文件名。

### 1.2 冻结契约

- `temperature=0`、model、tool image digest、prompt/finding schema 版本进入每次运行的 metadata，并在 scorer 中做一致性检查。
- 漏报率与误报率沿用 `d3-v1`，独立呈报；后端 `EvaluationMetrics.falsePositiveRate` 不得被错误地当作本口径误报率。
- `development/holdout` 只作为 manifest 已有 split 使用；任何统计必须同时保留全量、split、类别和案例明细。
- 不改变 Java 包名、远程仓库名、历史提交；不把 P7 归档内容重新纳入本任务。

## 2. 语料 schema 扩展与确定性校验

### 2.1 manifest 结构

每个 case 在已有字段旁增加：

```json
{
  "requirement": {
    "title": "...",
    "background": "...",
    "description": "..."
  },
  "acceptanceCriteria": [
    { "id": "AC-1", "text": "..." }
  ],
  "consistencyTruth": [
    { "acId": "AC-1", "verdict": "COVERED" }
  ]
}
```

- `acId` 只允许引用同一 case 的 AC；AC ID 不重复；AC 文本非空。
- truth 必须覆盖全部 AC，且不允许重复 `acId`；verdict 枚举为 `COVERED`、`NOT_FOUND`、`AT_RISK`。
- 38 例均必须有 Requirement、至少一个 AC 和完整 truth；空 AC/空 truth 视为校验失败。
- 递增 `schemaVersion`，不删除旧字段，不改既有 finding 判分语义。

### 2.2 后端校验落点

- 扩展 `backend/.../evaluation/EvaluationReport.java` 的 record DTO，增加 Requirement、AcceptanceCriterion、ConsistencyTruth 类型。
- 扩展 `EvaluationCorpusService.validate`：字段完整性、ID 引用、枚举、覆盖关系和既有 fixture/温度/镜像门禁统一在这里校验。
- 扩展 `EvaluationCorpusServiceTest`：至少覆盖完整 case、缺失 requirement、重复 AC、truth 引用不存在 AC、truth 不完整、非法 verdict、temperature 非零等负面路径。
- 生成 `manifest-summary` 时只读 manifest，不能在生成脚本里另行定义案例数字。

## 3. 五臂实验的数据流

### 3.1 arm 定义

| Arm | 生产 ContextBuilder flags | 注入内容 | 目的 |
|---|---|---|---|
| Baseline | 全部关闭 | diff | 观测无增强上下文基线 |
| A | `knowledge=true` | diff + project knowledge | 测量知识库增益 |
| B | `requirement/ac=true` | diff + Requirement + AC | 测量需求上下文增益 |
| C | `knowledge=true` + `requirement/ac=true` | diff + knowledge + Requirement + AC | 测量组合效果 |
| D | C + `evidenceVerification=true` | C + 现有证据验证链 | 测量证据校验的增益/代价 |

flag 组合必须复用父任务设计中的生产代码路径；实验不得复制 prompt 组装或手工拼装上下文。

### 3.2 单次运行封套

每个 run 保存：

```text
run-metadata.json
manifest-summary.json / manifest-summary.md
<arm>/raw/<case-id>.json
<arm>/scores.json / scores.md
<arm>/ai-call-log-summary.csv
<arm>/limitations.md
matrix.json / matrix.md
```

`run-metadata.json` 至少包含 `runId`、commit SHA、corpus/schema/prompt/finding 版本、model、temperature、tool image、arm、startedAt/finishedAt、scoredCases、notRunCases、环境前置摘要。secret 只记录是否配置和脱敏 fingerprint，不记录值。

运行器继续以 `evaluation/tools/run-baseline.sh` 为入口，向后兼容旧参数，增加 arm/run/output 选择；`evaluation/tools/run-ablation.sh` 只负责按同一配置循环调用五臂，不复制 API 调用和判分逻辑。`score.py` 保留 `--selftest`，扩展为同时读取 findings、coverage 和运行 metadata。

### 3.3 原始结果与复现

- 用 `build-case-repos.sh` 从 manifest 构建确定性 fixture 仓库；不修改 `demo-repos/` 刻意缺陷素材。
- 每个 case 的原始响应保留完整 JSON，判分器只读取标准 `report.issues`/兼容 envelope；coverage 采用同一 envelope 的 `report.coverage`（必要时兼容已有 `report.data.coverage`/顶层 coverage），不为实验另建 endpoint。
- 运行器从同一 manifest 为每个隔离项目创建 Requirement 和 AC，并通过 `BRANCH=main` link 绑定；生产 API 按序生成 `AC1`、`AC2` 等稳定 ID，manifest 与 scorer 使用同一 ID，不做隐式翻译。
- token/耗时优先从现有 `ai_call_log` 导出按 run/case/arm 聚合；如某次运行缺日志，结果标记缺失，不用估算值填充。
- 38 例基线与五臂必须使用相同 `fixedRun` 约束；如果服务器运行失败，记录 `notRun` 和阻断原因，不把 mock 结果写成真实模型结果。

## 4. 指标与报告

### 4.1 Finding 两率

沿用 `evaluation/tools/score.py` 的 `d3-v1` 贪心 1:1 匹配：路径、类别别名/例外和行区间三者同时满足。每个 arm 生成：

- overall：expected、missed、missRate、model、unmatched、falseReportRate；
- by split：development/holdout；
- by category：漏报按标注类别，误报按模型类别；
- by case：匹配对、missed、unmatched、nonFindings 警报、notRun。

### 4.2 AC 一致性

- 对每个 `acceptanceCriteria` 取模型 `coverage` 中同 `acId` 的 predicted verdict，与 `consistencyTruth` 做 exact match；已识别 AC 但 verdict 非法的输出计入 scored miss，完全缺失的 prediction 才计入 missing。
- 主指标 `acHitRate = exactMatchedAC / scoredAC`；`scoredAC=0` 时为 `n/a`。
- 同时输出按 `COVERED`/`NOT_FOUND`/`AT_RISK` 的 precision/recall、overall、split、case 明细；缺失 prediction 不默认为某个 verdict。
- 报告必须标注这是 AC consistency 指标，不与 Finding 漏报率或后端 `falsePositiveRate` 混称。

### 4.3 Token、耗时和限制

- 汇总 `ai_call_log` 的 input/output/total token 与 wall-clock duration，保留 per-call、per-case、per-arm 三层；注明是否包含重试、分片和证据验证调用。
- 结果表显式列出 `notRun`、缺日志、mock/offline 运行和模型/版本差异；没有真实产物就不生成对外提升百分比。
- `matrix.md` 只引用生成的 scores/CSV，不手工重抄数字；README/答辩材料引用 `matrix.md` 的稳定路径并带运行日期与 commit SHA。

## 5. ForgePilot 品牌切换边界

### 5.1 允许变更的用户可见面

- 前端 app title、导航/登录/空状态等产品展示名和截图版位。
- 根 `README.md` 的产品介绍、快速开始、能力边界和截图说明。
- `docs/12_服务器部署与演示手册.md`、`docs/演示素材与缺陷对照表.md`、答辩材料/截图索引等用户可见材料。
- `VITE_APP_TITLE` 或现有 title 配置的默认展示值；具体切点先搜索所有引用再改，避免覆盖无关历史资料。

### 5.2 明确保留

- `com.example.codereview` 等 Java package、数据库/REST/MQ 内部标识和历史任务文档中的事实记录。
- GitHub 远程地址 `LinYsssss/reposage`（P9 再处理仓库收尾）；如 README 中仍需链接仓库，允许保留技术链接但展示名必须为 ForgePilot。
- `demo-repos` 的 43 条植入缺陷、patch、noise 文档和固定 SHA。

## 6. 在线/离线演示设计

### 6.1 在线 webhook

生产路径保持：SCM webhook → provider verifier → `WebhookAgentRunService.startFromEvent` → AgentRun/outbox/MQ → 状态/报告。在线演示只增加可复现的前置检查、payload 模板和记录，不修改验签/幂等/状态机语义。

### 6.2 离线签名注入

- 新增薄脚本（PowerShell 为本机主路径，shell 为服务器兼容路径）构造固定 pull request payload。
- 脚本用部署配置中的 test secret 对**原始 payload bytes**计算现有 `WebhookSignatures` 兼容 HMAC，并设置 provider 对应 signature/header，再 POST 到本地 webhook endpoint。
- 后端仍执行正常 verifier；签名错误、重复 delivery、错误 provider 和旧 head 的负面路径保留测试。
- 离线配置为 H2、Mock AI、inline review；脚本启动/检查后端，等待 health，注入 payload，轮询 AgentRun 到 terminal，输出脱敏的 run id/status/trace id。
- 彩排记录包含 commit SHA、命令、配置键名（不含 secret）、服务版本、成功响应和限制声明；不上传密钥、不把 mock 指标混入真实实验矩阵。

## 7. 质量、契约与回滚

- 跨层新增 manifest/coverage/run metadata 形状，按 `.trellis/spec/guides/contract-testing.md` 由真实生产输出驱动消费方测试；禁止 scorer 和 API 各自只测自造 JSON。
- 评测代码测试：backend corpus validation、Python scorer selftest/AC cases、run metadata consistency、notRun/zero denominator、旧 envelope 兼容。
- 演示脚本测试：HMAC fixed vector、payload canonical bytes、duplicate delivery idempotency、H2/mock/inline smoke；脚本失败要明确返回非零。
- 品牌改动以用户可见范围为单独回滚点；语料 schema/判分扩展以单独回滚点；演示脚本与答辩文档以单独回滚点。任何一组阻断性失败都不能伪装成通过。
- 遵守 `demo-assets-and-claims`：对外数字必须有实测产物，不能写零漏报；遵守 backend/frontend quality/security specs 与 deployment/observability 记录要求。

## 8. 未决技术风险（不阻塞 planning）

- 服务器真实模型凭据、Docker、PostgreSQL/RabbitMQ 是否可用，需要在实现前置检查中确认；不可用时只记录阻断，不用 mock 替代。
- 现有 API response/ai_call_log 的 coverage 与 token 字段形状需要在实现阶段由真实运行/测试确认；若字段落点不同，优先做兼容提取和契约测试，不改变指标定义。
- 全量品牌材料可能包含历史/归档技术引用；实现时按用户可见与历史证据分层，避免无差别全仓替换。
