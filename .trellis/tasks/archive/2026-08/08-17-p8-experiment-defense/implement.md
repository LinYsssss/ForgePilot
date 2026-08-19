# Implement：P8 实验、答辩与功能冻结

> 这是实现前的有序执行清单。当前仍处于 planning；没有收到对最终 planning summary 的下一轮明确批准前，不运行 `task.py start`，不实现产品代码。

## 0. 启动前保护与范围门

- [ ] 记录 `git status --short --branch`；确认 `.agents/`、`.codex/`、`.trellis/.template-hashes.json`、`.trellis/tasks/08-16-forgepilot-upgrade/task.json`、P8/P9 规划目录等并行 dirty 内容不进入提交。
- [x] 用户已明确批准本计划并运行 `python ./.trellis/scripts/task.py start 08-17-p8-experiment-defense`；启动前已复核 `prd.md`、`design.md`、本清单和 JSONL context。
- [ ] 加载 `trellis-before-dev`，按实现实际触及的 backend/evaluation、frontend、scripts/docs 层补充上下文；不改 P7 归档内容。

## 1. 语料与 schema（先做确定性数据，再做真实模型）

- [ ] 盘点 38 例现有 `expectedFindings`/`nonFindings`/fixture/split，建立标注工作表；不复制 manifest 为第二事实源。
- [ ] 为 38 例补 Requirement、AC、consistency truth；逐例复核 AC ID、truth 覆盖、dev/holdout 隔离和与 finding 真值的一致性。
- [x] 扩展 `EvaluationReport` DTO 与 `EvaluationCorpusService.validate`；递增 schema version，保留旧字段与既有 finding 校验。
- [x] 补 `EvaluationCorpusServiceTest` 正向/负向用例，生成 manifest schema/标注镜像摘要并落 P8 `eval-runs/`。
- [x] 运行 corpus validator/focused test；16 个 evaluation tests 通过。

## 2. 判分与指标（扩展现有链路，不建第二套）

- [x] 扩展 `evaluation/tools/score.py`：保留 `d3-v1` 两率，增加 coverage 提取、AC exact-hit、按 verdict P/R、split/case 明细、notRun/零分母和 run metadata 一致性检查。
- [x] 扩展 selftest/小矩阵：覆盖 AC 全命中、部分命中、缺失 prediction、非法 verdict、zero denominator、notRun、旧 findings envelope 和 nonFindings 警报。
- [ ] 明确输入仍为 `evaluation/manifest.json` + 真实 run response + ai_call_log 导出；禁止用 mock 结果生成真实模型数字。
- [x] 运行 `python evaluation/tools/score.py --selftest`；30 项通过。invalid verdict 作为 scored miss，缺失 prediction 不进入 scored 分母。

## 3. 五臂运行器与 38 例新基线

- [ ] 以现有 `evaluation/tools/run-baseline.sh` 为入口，加入向后兼容的 `--arm`、`--run-id`、metadata 参数；使用 `evaluation/tools/run-ablation.sh` 依次编排 Baseline/A/B/C/D，五臂仅改变 ContextBuilder feature flags。
- [ ] 固化 arm metadata：Baseline / A / B / C / D 定义、commit SHA、manifest/schema/prompt/finding 版本、model、temperature、tool image、时间、完成/未完成案例。
- [ ] 用 `build-case-repos.sh` 构建确定性用例仓库；验证不触碰 `demo-repos` 43 条植入缺陷和固定 SHA。
- [ ] 在具备真实模型与 Docker 的服务器上先跑 38 例新基线，再按相同参数跑五臂；每个 arm 保存 raw response、scores、ai-call-log 汇总和 limitations。
- [ ] 运行结果矩阵生成 `matrix.json/md`；人工复核 5 例 AC 命中与全部限制声明，确认 P8 数字不引用历史 32 例。
- [ ] 若服务器环境阻断，记录完整 `notRun`/原因和复现命令；不得用 H2/mock 结果替代真实模型实验。

## 4. 品牌与答辩材料（在数字冻结后）

- [ ] 全面搜索用户可见面中的 RepoSage/旧产品标题，先分类：展示名、技术链接、历史归档、内部标识；只修改展示名和指定材料。
- [ ] 切换 frontend title/navigation/empty states、README 产品介绍与截图版位、演示手册、答辩材料索引为 ForgePilot。
- [ ] 保留 Java package、GitHub 远程仓库名/链接、demo-repos 缺陷/patch/noise 与既有诚实边界；不做无差别全仓替换。
- [ ] 每个能力/数字附 `eval-runs` 或演示证据路径；禁止“零漏报”或无证据提升率。

## 5. 双路径演示与离线彩排

- [x] 实现在线/离线共用的 `scripts/rehearse-webhook.ps1`：health、真实 HMAC、delivery、可选 AgentRun 轮询与脱敏证据。
- [x] 增加 PowerShell 主路径的本地签名注入脚本；对原始 payload bytes 做现有 HMAC，走真实 verifier，不加 bypass。shell 兼容路径保留为后续 P9/部署补充。
- [ ] 配置 H2 + Mock AI + inline review，脚本等待 health、注入 payload、轮询 terminal、打印脱敏 run/status/trace；错误返回非零。
- [x] 已完成 2026-08-18 本地 H2/Mock signed webhook 彩排：HTTP 202 / PROCESSED / AgentRun 1；证据明确记录 `AGENT_SCHEDULING_ENABLED=false`、无 RabbitMQ 导致 step PENDING 的限制。
- [ ] 若改动跨进程 payload/canonical bytes，补 backend 与消费侧的 fixed-vector/真实产出驱动契约测试。

## 6. 质量门与冻结检查

- [ ] Backend：先 focused corpus/scorer/contract tests，再按 P8 需要运行全量 `mvn -s .mvn/settings.xml verify`；不把 P7 44 tests 重新作为本轮目标。
- [x] Frontend：85 tests 通过、production build 通过；仅验证品牌 targeted contracts，未重做 P7 视觉 Browser QA。
- [ ] 本地门禁：`pwsh scripts/verify-local.ps1 -SkipSmoke`、`python evaluation/tools/score.py --selftest`、manifest 校验、`git diff --check`。
- [ ] 检查所有对外数字都有产物、两率独立、notRun/限制声明存在、截图/演示标题为 ForgePilot、没有新业务功能混入。
- [ ] 形成最终材料清单和回滚点，完成 `task.py validate`；只有全部通过才允许进入 finish/archive。

## 7. 验证与回滚点

- 语料/schema 回滚：恢复 P8 manifest/DTO/validator 组，不触碰 R7 历史基线。
- scorer/实验回滚：保留旧 `score.py --selftest` 行为；新增字段或 arm 逻辑失败时不生成对外数字。
- 品牌回滚：展示层文件独立回退，技术链接/package/远程名不随之变化。
- 演示回滚：脚本/文档单独回退；任何验签、幂等或状态机失败视为阻断，不以日志手工补绿。

## 8. 完成条件

- [ ] 用户已在最终 planning summary 后明确批准实现。
- [ ] P8 任务已 start，所有实现/检查/证据均落任务目录或明确的 docs 路径。
- [ ] 质量门、材料清单、限制声明和回滚点完成；未把 P7/P9/并行 dirty files 纳入提交。

## 2026-08-18 execution note

- Local gate: `pwsh scripts/verify-local.ps1 -SkipSmoke` passed (backend tests, frontend tests/build); Docker was explicitly skipped because the command is unavailable.
- Local H2/Mock signed webhook rehearsal passed the production verifier with HTTP `202` / `PROCESSED` and AgentRun `1`; evidence: `rehearsal-offline-20260817225246.md`.
- The rehearsal AgentRun remained `PREPARING_REPOSITORY` / step `PENDING` after 45 seconds because it intentionally ran with `AGENT_SCHEDULING_ENABLED=false` and no RabbitMQ. This is recorded as a link/injection rehearsal only.
- The real 38-case model matrix and five-arm scores remain unexecuted until a Docker-enabled isolated stack, `EVAL_BASE_URL`, credentials, and real `AI_PROVIDER=openai-compatible` model credentials are available. No mock result is substituted.
- Honest real-matrix preflight: `.trellis/tasks/08-17-p8-experiment-defense/eval-runs/preflight-2026-08-18/matrix.md` records all five arms as `notRun=38` because Docker and real evaluation credentials are absent.
