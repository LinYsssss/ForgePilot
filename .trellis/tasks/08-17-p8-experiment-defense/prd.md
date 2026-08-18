# P8 实验、答辩与功能冻结

> 父任务：`08-16-forgepilot-upgrade`（R11/R12、implement P8）
> 当前状态：planning（2026-08-18）
> 规划边界：只规划 P8；不重复 P7，不启动 P9，不修改 Java 包名或远程仓库名。

## Goal

在冻结新增业务功能的前提下，以现有 R7/R8 评测资产为唯一基础，完成 38 例可复现语料标注、Baseline/A/B/C/D 五臂消融、38 例新基线、论文数据、ForgePilot 品牌切换和在线/离线双路径答辩彩排，形成能被复核的实验与演示材料。

## Background / confirmed facts

- `evaluation/manifest.json` 当前已有 38 例，分为 development 26 例、holdout 12 例；唯一固定运行配置为 `z-ai/glm-5.2`、`temperature=0`、digest-pinned tool image、`finding-v1`。
- R7 已建立 `evaluation/tools/{build-case-repos.sh,run-baseline.sh,score.py}` 与 `d3-v1` 两率口径；历史 32 例基线只能作为历史趋势，不能冒充 38 例结果。
- 父任务已冻结五臂定义：Baseline = diff；A = Baseline + knowledge；B = Baseline + requirement/AC；C = A + B；D = C + evidence verification。实验必须复用生产 ContextBuilder/feature flags 代码路径。
- R8 已完成部分 prompt/清单实现，但 38 例服务器复跑、终版对比和漏报回灌仍是待办；P8 不另建第二套语料或第二套判分器。
- 当前用户可见材料仍有 RepoSage 文案；P8 只切换 UI、README、演示/答辩材料与截图版位，保留 Java 包名、GitHub 远程仓库名及必要技术历史引用。
- 在线 webhook 验签、outbox/AgentRun、Mock AI、H2、inline 审查和本地脚本基础设施已存在；离线路径必须走真实 HMAC 签名校验，不得增加验签后门。

## Requirements

### P8-REQ-1：38 例语料与真值扩展

- 为 38 例全部补齐 `requirement`、`acceptanceCriteria` 和 `consistencyTruth`。
- 保持现有 `expectedFindings`、`nonFindings`、fixture、语言、`development/holdout` 划分不变，除非发现能阻断实验的标注错误并留下修订记录。
- `acceptanceCriteria` 的 ID 在案例内唯一；`consistencyTruth.acId` 必须引用本案例 AC；verdict 使用已冻结的 `COVERED / NOT_FOUND / AT_RISK`。
- 增加后端确定性校验和测试，保持 `temperature=0`、tool image digest、fixture 越界防护等既有门禁。
- 从 manifest 生成只读的 schema/标注“镜像摘要”，镜像摘要不是第二事实源，不能手工维护出第二套数字。

### P8-REQ-2：五臂消融与 38 例新基线

- 在同一 38 例、同一模型、同一 tool image、同一 `temperature=0` 和同一判分规则下运行 Baseline/A/B/C/D。
- 每个 arm 必须记录 arm 定义、manifest/corpus 版本、prompt/finding schema 版本、模型、镜像摘要、运行时间、已完成/未完成案例数。
- 新基线单独落档，不覆盖 R7 历史 32 例档案；R8 的最终对比只能引用 P8 生成的 38 例结果。
- 复用现有 `run-baseline.sh`/判分链路并扩展 arm 选择，不新建平行评测框架；若需跨平台调用，只增加薄包装，不改变判分语义。

### P8-REQ-3：统一指标与诚实限制声明

- 每个 arm 输出：漏报率、误报率、AC 一致性命中率；并按 development/holdout、类别和案例提供明细。
- 漏报率与误报率独立呈报，不合成单一分数；保留 `notRun`，未跑成案例不进入分母。
- AC 一致性主指标定义为 `predicted verdict == consistencyTruth verdict` 的 exact-hit rate；同时输出按 verdict 的 precision/recall，避免“命中率”掩盖类别偏差。
- 从既有 `ai_call_log`/运行导出统一汇总 token 与耗时，至少支持总量、每案例和每 arm 对比；不能用 mock 运行伪造真实模型效果数字。
- 所有论文/答辩/README 数字必须能回指 P8 `eval-runs/` 产物，并主动声明模型、语料范围、未跑案例、R7 历史基线不可比项和限制；禁止“零漏报”及等价承诺。

### P8-REQ-4：ForgePilot 品牌与答辩材料

- 截图前将前端页面、浏览器标题、README 产品介绍、演示手册和答辩材料中的产品展示名切换为 ForgePilot。
- 保留 Java package、GitHub 远程仓库名/链接和必要的历史归档引用；不在本 Phase 做仓库重命名或全局技术标识迁移。
- 能力表述只引用已经存在的测试、评测或演示证据，并保留“AI 不保证全中、存在误报、demo 缺陷为植入素材”的诚实边界。

### P8-REQ-5：在线/离线双路径演示

- 在线路径：真实 SCM webhook → HMAC 验签 → `WebhookAgentRunService` → outbox/MQ/AgentRun；不绕过生产验签和状态机。
- 离线路径：本地构造 payload，使用配置 secret 生成真实签名后 POST；运行 `H2 + Mock AI + inline review`，完成一次完整彩排并留下脱敏证据。
- 在线和离线脚本都必须可重复执行、显式打印前置条件、对 secret 脱敏，不将凭据写入仓库。
- 演示脚本必须说明 mock/H2 仅用于链路彩排，不能作为真实模型质量或生产性能证据。

### P8-REQ-6：功能冻结与最终质量门

- P8 期间只允许实验阻断缺陷、数据/指标口径修正、演示阻断缺陷和答辩材料修正；不新增业务功能、不扩大产品范围。
- 通过语料校验、P8 评测/指标自测、在线/离线脚本检查、后端/前端/仓库现有质量门；不重新执行已归档 P7 的视觉 Browser QA，除非 P8 的品牌改动直接破坏其已冻结版位。

## Out of scope

- Java 包名、数据库领域模型、远程 GitHub 仓库名和历史 commit 重写。
- P7 工作台、研发度量、Ink 前端的重新分析、重构或重复视觉验收。
- 新增业务域、自动开发/自动 commit/自动 push 能力、新中间件或第二套评测/指标体系。
- 把历史 32 例基线、mock 结果或未完成运行当作 38 例真实模型结论。
- 为了赶进度把正式目标裁剪到 28 例；“≥28 例”仅保留为阻断风险应急口径，不是计划目标。
- 将离线彩排包装成在线生产可用性、模型效果或零漏报证明。

## Acceptance Criteria

- [ ] 38 例 manifest schema、Requirement/AC/consistency truth、fixture/标签镜像摘要和 `temperature=0` 确定性校验全部通过。
- [ ] Baseline/A/B/C/D 与 38 例新基线可重复运行；结果包含两率、AC 命中率、token、耗时、split/category/case 明细、notRun 和限制声明。
- [ ] 五臂定义与父任务 §7/§13 一致，生产 ContextBuilder/feature flags 与实验共用同一代码路径；没有平行判分器。
- [ ] 截图、前端标题、README、演示手册和答辩材料显示 ForgePilot；能力声明均有可追溯证据且不承诺零漏报。
- [ ] 在线 webhook 路径和离线签名注入路径均有可执行脚本；H2 + Mock AI + inline 离线彩排至少成功一次，并保存脱敏记录。
- [ ] 后端 focused/full 质量门、前端 test/build、`pwsh scripts/verify-local.ps1 -SkipSmoke`、评测 selftest 和 `git diff --check` 通过；P7 视觉 QA 不重复执行。
- [ ] P8 任务归档前完成最终材料清单、限制声明、复现命令、证据路径和回滚说明。

## Artifact contract

- 语料唯一事实源：`evaluation/manifest.json`。
- 评测原始运行与汇总：`.trellis/tasks/08-17-p8-experiment-defense/eval-runs/<run-id>/`，按 arm 分目录保存，不覆盖 R7 历史档案。
- P8 计划产物：本目录 `prd.md`、`design.md`、`implement.md`，以及真实 spec/research context manifests。
- 演示彩排记录、脱敏日志、截图索引和最终答辩材料清单均落在 P8 task 目录或已有 docs 目录，不新增第二个产品事实源。

## Blocking open questions

无。当前服务器真实模型凭据/Docker 是否可用属于实现阶段的环境前置条件；若未满足，必须如实标记 blocked/notRun，不改变计划口径，也不得用 mock 数字替代。
