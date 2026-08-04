# Project reality audit: defects and overclaimed capabilities

## Goal

对 RepoSage 主仓库做一次全量"现实核对"审计：找出（a）真实功能缺陷，（b）README/docs 中与代码实际不符或夸大的声称（口径失实），并交付一份带证据的结构化审计报告 + 按优先级排序的**修复与改进路线图**，作为后续修复任务的直接输入。本任务不实施修复。

## Background

- 仓库为 RepoSage（简历项目）。用户长期原则：写实不造假、先核对代码再改口径。
- main 已快进到 `b59c098`，含 Track A/B 合并后的完整代码。
- README（448 行）声称大量能力：真实 LLM 审查、大 Diff 分片、PR 守门 Agent（HMAC 验签、状态机+事务 Outbox、签名 Docker 沙箱、补丁人工审批）、可观测（Prometheus 指标、traceId MDC 贯穿）、限流、深度健康检查、SARIF 导出、钉钉通知等。
- README 已含多处"诚实声明"（mock 对比两侧一致、质量门详情页未实施、无 Docker 时容器安全未验证、单机 Compose 非多租户边界），近期提交 b69e1c3 / 5248db9 / ad96766 均为口径修正——审计需避免把已声明的限制重复报为"失实"。
- README 声称测试基线（标注"最近一次记录"）：后端 190 通过 + 3 跳过；sandbox-runner 37 通过；前端 4 测试 + 构建通过。
- 已有对照材料：`docs/演示素材与缺陷对照表.md`、`docs/完整功能测试方案.md`、`docs/数据库完整性预检与约束盘点.md`。
- 模块：backend（Spring Boot 3.5 / Java 17）、frontend（Vue 3 / Vite）、model-service（FastAPI / sklearn）、sandbox-runner、deploy（Compose）、demo-repos（3 演示仓库）、evaluation、scripts、docs。

## Key Decisions

- **交付形态**：审计报告 + 修复/改进路线图；修复后置为独立任务（用户授权我裁量，并要求"交付出来后看看目前要怎么修改、怎么改进"）。
- **验证深度**：实测复核（跑测试/构建/校验脚本），因为"不符合实际"需要运行证据；Docker 依赖项先探测可用性，不可用则标注"本机无法验证"而非判失实。
- **维度权重**：功能缺陷与口径失实并重，另附改进建议维度；服务于"简历项目、可演示、写实"定位。

## Requirements

- **R1 实测复核声称基线**：后端 `mvn test`、sandbox-runner 测试、前端测试+构建、model-service 可训练/可启动检查、`scripts/init-demo-repos.ps1 -Verify` SHA 校验、Docker 可用性探测。记录实际数字与 README 声称的差异。
- **R2 口径逐条比对**：对 README 四张声称清单——核心特性（~14 条）、PR 守门 Agent 安全边界、API 速查表、配置项表——逐条与代码实现比对，标注：属实 / 失实 / 部分属实 / 本机无法验证。docs/ 主要文档抽查与代码一致性。
- **R3 功能缺陷扫描**：backend / frontend / model-service / sandbox-runner / deploy 的代码级缺陷（安全、并发、事务一致性、边界条件、配置漂移、部署脚本与实际不符）。
- **R4 改进建议**：结合项目定位提出改进方向（只列不做）。
- **R5 报告落盘**：`.trellis/tasks/08-03-project-reality-audit/audit-report.md`。每条发现含：严重度（P0 阻断演示/失实必修 → P3 锦上添花）、类别（缺陷 | 口径失实 | 改进）、证据（file:line 锚点或实测输出）、建议修法。
- **R6 路线图**：报告末尾给出按 P0→P3 排序的修复与改进路线图，标注每项的预估工作量档位（小/中/大），供用户挑选后续任务。

## Out of Scope

- 实施任何修复或文档改写（不改产品代码与 docs）。
- 服务器/Docker 部署联调（本机 Docker 不可用时相关项记为"未验证"）。
- demo-repos 三个演示仓库内部代码质量（仅做 SHA 一致性校验）。
- 简历文本本身的修改（审计结论可作为后续简历口径核对的输入）。

## Acceptance Criteria

- [ ] README 声称的测试基线全部实测复核，报告记录实际数字；环境不可用项注明原因。
- [ ] README 四张声称清单逐条核对完毕，每条有 属实/失实/部分属实/无法验证 结论；"失实/部分属实"必须带 file:line 或实测证据。
- [ ] 审计报告按 P0–P3 分级，每条发现有证据锚点与建议修法。
- [ ] 报告末尾有按优先级排序、带工作量档位的修复与改进路线图。
- [ ] 全程未修改任何产品代码、文档、配置（仅写任务目录内的产物）。
