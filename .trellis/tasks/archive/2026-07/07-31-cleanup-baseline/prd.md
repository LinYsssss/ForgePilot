# PRD：仓库清理与基线整理

> 轻量任务（PRD-only）。父任务：07-31-tri-improve。

## 目标

给后续前端重构、美化、功能增强一个干净、可复现、无密钥风险的仓库基线。

## 需求

1. **密钥备份移出**：`deploy/.env.bak.1785202135`、`deploy/.env.bak.seed-1785225396` 移到 `~/reposage-secrets/`（仓库外）。检查 `deploy/backup.sh` 的 `.env` 备份落点：确保今后备份写入已被 ignore 的位置（`deploy/backups/` 或仓库外），`.gitignore` 覆盖 `.env.bak*` 模式。
2. **工具缓存**：`.ua/` 加入 `.gitignore`（本地保留不入库）。
3. **工作流文件入库**：检视后提交 `.trellis/`、`AGENTS.md`、`.gitattributes`（确认无敏感信息；`.trellis/.developer`、runtime 等应被其自带 ignore 规则排除）。
4. **docs 归档**：新建 `docs/archive/`，移入已完成阶段的过程文档（答辩整改方案、TrackA 交接、TrackB 计划、并行拆分方案、实施进度、开发 Prompt、中文开发规划、前端优化方案、跨线协商若存在）；保留活跃文档（01~12 编号设计文档、运维验收、功能测试准备清单、完整功能测试方案、演示素材与缺陷对照表、数据库完整性预检）。归档前把《PR守门Agent前端优化方案》中未完成 backlog 摘录进 07-31-feature-enhance 的 prd.md，不丢信息。docs/README.md 如有索引需同步。
5. **scripts 补充**：新增 `scripts/verify-local.sh`（backend verify → sandbox-runner test → frontend npm ci/test/build，任一失败即非零退出并汇总结果）；`.ps1` 原样保留。
6. **环境探测**：确认本机 docker / node / java / maven / python 版本，写入本任务 `research/environment.md`，为后续任务定验证深度。
7. **基线验证**：跑一遍能跑的全量测试，结果如实记录（作为后续步骤的对照基线）。

## 验收标准

- [ ] 仓库目录内不再有 `.env.bak.*` 文件；`git status` 不再显示 `.ua/` 为未跟踪。
- [ ] `.trellis/`、`AGENTS.md`、`.gitattributes` 已提交且不含敏感信息。
- [ ] `docs/` 顶层只剩活跃文档，过程文档在 `docs/archive/`，无信息丢失（backlog 已转录）。
- [ ] `scripts/verify-local.sh` 可执行且在本机产出真实结果汇总。
- [ ] `research/environment.md` 记录工具链版本与可验证范围结论。
- [ ] 基线测试结果记录在案；与整改文档口径一致（跑不了的标"未验证"）。

## 不做

- 不动 demo-repos、evaluation、deploy 的功能性内容；不改任何产品代码。
