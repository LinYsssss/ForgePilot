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
