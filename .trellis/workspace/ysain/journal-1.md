# Journal - ysain (Part 1)

> AI development session journal
> Started: 2026-07-31

---

## 2026-08-04 · feature-enhance 收尾（F7/F8）

- 合并远程：`origin/fix/track-a-core` 13 个演示材料提交干净合入;`origin/main` 登录限流提交与本分支冲突 4 处,全取本分支侧（已含其带 ClientIpResolver/csrf 的演化版,测试为超集）。合并后领先 main 93/落后 0。
- 验证上会话遗留的 CSRF 修复：后端容器化 `mvn test` 531 例全绿（含新 `SpaCsrfBrowserFlowTest` 浏览器时序回归）;前端 `npm test && npm run build` 通过。
- F7 E2E：dev 后端（H2+mock+inline,CSRF 开）+ vite 代理 + Playwright 容器,`compare-e2e.mjs` 全流程 OK（登录→建项目→绑 demo 仓库→4 篇知识文档入库→一键对比→三栏对比视图）,截图 c0~c3 落 research/shots/。mock 两侧产出一致为预期口径,记录于 research/compare-walkthrough-result.md。
- 脚本加固：上传循环等 `.doc-card` 出现（固定 sleep 会吞点击致 4 篇只入 3）;app-shell 等待 15s→30s（冷启动首登录超 15s）;脚本非幂等,重跑须重启后端。
- F8：README 核心特性 +2（审查工作台/对比审查）、使用流程重排（新步骤 7 对比审查,原 7-10→8-11,修正 webhook 段"第 9 步"引用）、常见问题 +2（mock 两侧一致解释;A5 质量门详情页明确"未实施"）。
- 环境注记：`.claude/settings.json` 增加 `worktree.bgIsolation=none`（后台会话在本检出续作既有任务的豁免口）。



## Session 1: feature-enhance 收尾:远程合并、CSRF 修复验证、对比审查 E2E 与文档同步

**Date**: 2026-08-04
**Task**: feature-enhance 收尾:远程合并、CSRF 修复验证、对比审查 E2E 与文档同步
**Branch**: `integration/track-ab`

### Summary

合并 origin/fix/track-a-core(13 个演示材料提交)与 origin/main(登录限流,4 处冲突取本分支演化版);验证上会话遗留的 SPA CSRF 时序修复(后端容器化 531 例全绿含 SpaCsrfBrowserFlowTest,前端测试+构建通过);dev 后端+Playwright 跑通对比审查 E2E(4 篇知识文档入库、双任务三栏对比视图),截图与走查记录落 research/,mock 两侧一致口径写明;README 增补工作台/对比审查特性与 A5 未实施口径;CSRF 跨层契约沉淀至 spec/backend/security-guidelines.md

### Git Commits

| Hash | Message |
|------|---------|
| `be59ed8` | (see git log) |
| `a16dc46` | (see git log) |

### Status

[OK] **Completed**


## Session 2: r1: 解除 CI 阻塞——12 天连红终结,供应链门禁首跑即咬合

**Date**: 2026-08-04
**Task**: r1: 解除 CI 阻塞——12 天连红终结,供应链门禁首跑即咬合
**Branch**: `integration/track-ab`

### Summary

F-01 trivy tag 修复途中发现上游删除 setup-trivy 旧 tag,升至 v0.36.0;F-02 测试归档路径隔离到 tmpdir;F-13 关闭 PR #3。门禁首跑咬出 CVE-2026-41695(HIGH),以 spring-data-bom 2025.0.12 属性覆盖当场修复。run 30888394125 三作业全绿,前端 21 项与 model-service 9 项测试首次在 CI 实际执行。证据存档 trivy-evidence.md,规范沉淀至 security/quality-guidelines。

### Git Commits

| Hash | Message |
|------|---------|
| `cd09a9d` | (see git log) |
| `31373e6` | (see git log) |
| `bb11cd7` | (see git log) |
| `0a369c3` | (see git log) |
| `ff4edde` | (see git log) |
| `f9e97c4` | (see git log) |

### Status

[OK] **Completed**


## Session 3: r2: 沙箱归档断链修复收官——run18 全链路首次 COMPLETED

**Date**: 2026-08-09
**Task**: r2: 沙箱归档断链修复收官——run18 全链路首次 COMPLETED
**Branch**: `integration/track-ab`

### Summary

F-03/F-04/F-05 契约核心落地(同构 WorkspaceArchiveReference+双向金标、WorkspaceArchiveService 唯一生产者原子落盘、SANDBOX_TOOL_IMAGE 生产快速失败)。当日 e2e 四轮点火修四截+复查自修四处:规划提示词数值约束同源、clampOverBudget 服务端裁剪+回执预算修正、claims/findings 条目形状钉死、瞬态错误接通重试、ModelJsonOutputs 防线单源、空知识白名单分档、无凭证发布显式跳过。run18 八步全绿:6 findings 持久化、发布记录 skipped 留痕、归档就地清理。backend 575/0 runner 75/0。规范沉淀 agent-model-contracts.md+error-handling.md 两硬约定。

### Git Commits

| Hash | Message |
|------|---------|
| `28a12b9` | (see git log) |
| `d5a3cd3` | (see git log) |
| `570c82c` | (see git log) |

### Status

[OK] **Completed**


## Session 4: r6 校正收官归档 + r7 语料 6→32 例合并落地 + r8-R1 字节等价模板入库

**Date**: 2026-08-12
**Task**: r6 校正收官归档 + r7 语料 6→32 例合并落地 + r8-R1 字节等价模板入库
**Branch**: `integration/track-ab`

### Summary

衔接 trellis-check 校正后的断点会话。r6:三处校正(bundle 口径补 html 列、color-scheme 亮色定稿、注释对齐 styles.css 退役)提交并归档收官。r7:按创作规约执行主会话合并职责——26 例标注碎片并入 manifest(6→32,dev 22/holdout 10),schema 三字段+base-head/行区间校验规则、判分工具三件套(build-case-repos 32/32 确定性建仓、score.py 自测 14 项、README 真实校验口径)落盘,抽查两例行号区间精确命中;遗留 temperature 对齐与真实基线跑分,任务保持 in_progress。r8-R1:分层模板字节等价搬迁入库(11 模板注册表+步骤内联指令清零,golden 钉死),上会话遗漏的 6 处执行器单测以 spy 真组装器修复(instruction 走真模板,内容断言连带钉住模板文件),任务保持 in_progress。质量门:后端全量 606/0、前端 21/21+build 绿、作者纪律核验通过。补录:r6 十二提交(e9b0ff7..b56a9b1)与 r7 规划两提交(b8ae66c/29f2b79)此前未入日志,并入本条;r3-r5 会话日志历史欠账仍在。

### Git Commits

| Hash | Message |
|------|---------|
| `33f8c75` | (see git log) |
| `6972a47` | (see git log) |
| `cf55fb7` | (see git log) |
| `840ee92` | (see git log) |
| `bdf07d4` | (see git log) |
| `392b0c9` | (see git log) |
| `e9b0ff7` | (see git log) |
| `aa5ccac` | (see git log) |
| `08ac7b0` | (see git log) |
| `1c2d629` | (see git log) |
| `2519b4e` | (see git log) |
| `3cbb677` | (see git log) |
| `39df3ab` | (see git log) |
| `1a4f3d0` | (see git log) |
| `f743c37` | (see git log) |
| `70d9e90` | (see git log) |
| `119f9a8` | (see git log) |
| `b56a9b1` | (see git log) |
| `b8ae66c` | (see git log) |
| `29f2b79` | (see git log) |

### Status

[OK] **Completed**
