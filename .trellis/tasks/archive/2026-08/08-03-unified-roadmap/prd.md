# 统一路线图：修复-规范-重构-美化-评测-提示词

> 父任务。合并「现实核对审计」修复批次（报告：`.trellis/tasks/archive/2026-08/08-03-project-reality-audit/audit-report.md`，基线 `a04b518`）与既定改进方案。实现工作全部在子任务中进行，本任务只维护顺序、约束与跨任务验收。

## Goal

在审计基线上，把 RepoSage 推进到：CI 绿且门禁真实运行、PR 守门链路端到端可用、文档口径与实测一致、规范成文、后端结构可维护、前端 Element Plus 视觉体系、评测集可度量漏报率、提示词精炼且有评测门禁。

## 子任务地图（按执行顺序）

| 序 | 任务 | 优先级 | 依赖 | 一句话交付 |
| --- | --- | --- | --- | --- |
| 1 | `08-03-r1-fix-unblock-ci` | P0 | — | CI 转绿，供应链门禁自建立以来首次真正运行 |
| 2 | `08-03-r2-fix-sandbox-chain` | P0 | r1（需要绿的 CI 验证） | 沙箱归档链路端到端打通 + 契约测试 |
| 3 | `08-03-r3-fix-consistency` | P1 | r1（F-07 取 CI 数字） | 8 项口径/一致性小修一次合并 |
| 4 | `08-03-r4-spec-bootstrap` | P1 | r1-r3（规范基于修复后的代码） | `.trellis/spec` 编码规范 + prompt 管理规范 + 契约测试纪律 |
| 5 | `08-03-r5-backend-refactor` | P1 | r4（对着规范重构） | 后端分批重构，测试全绿 |
| 6 | `08-03-r6-frontend-element-plus` | P1 | r4；与 r5 可并行 | Element Plus + tokens 逐页替换 Observatory |
| 7 | `08-03-r7-eval-corpus` | P2 | 无硬依赖；可与 r5/r6 并行 | 评测集 6→30-50 例，含漏报专项 |
| 8 | `08-03-r8-prompt-tuning` | P2 | r7（评测地基）+ r4（prompt 规范） | 分层模板/两段复核/动态 few-shot，过评测门禁 |

已裁决不做：原 Phase 7 可选增强（自举守门、级联路由、误报飞泼、增量门禁、LLMOps CI、知识库自动生长）——用户 2026-08-03 明确砍掉；将来要做需另立任务重新规划。

## 跨子任务约束（全部子任务必须遵守）

- **冻结契约**：不改 `ErrorCode` / `PageResponse` / `ProjectAuthorization` 签名；已执行的 Flyway 迁移不可修改，新迁移接当前最大版本号之后（动手前实测确认）。
- **安全底线**：新/改 ID 型端点必须纳入 `ObjectLevelAuthorizationMatrixTest` 反向授权矩阵；新增前端依赖必须过 `npm audit --audit-level=high` + trivy 门禁。
- **写实口径**：README/docs 任何能力表述以实测为准；审计已确认"诚实声明"仍准确，修改时不得回退这些声明。
- **演示资产**：demo-repos 三仓库及其知识文档、`docs/演示素材与缺陷对照表.md` 登记的故意缺陷必须保留。
- **提交纪律**：小步提交，风格沿用 `type(scope): 中文摘要`；作者为 LinYsssss，禁止 AI 署名（服务器上执行时先 `git config user.name/user.email`，审计报告提交曾以 root 身份提交、已在本地修正，勿再发生）。
- **skill 纪律**：写码前 `trellis-before-dev`；收尾 `trellis-check`（Agent 形式）→ 提交 → `/trellis:finish-work`；各子任务 prd 中标注的专用 skill 必须使用。
- **环境**：改码可在任意机器；需要 Docker 的验证（r2 端到端、容器类测试）在部署服务器执行。本机 Windows Node 为 24，动前端前需切 Node 22（`engines >=22 <23`）。

## 跨子任务验收（父任务完成的定义）

1. GitHub Actions 全作业绿；trivy 供应链扫描有真实产出（非 skipped/2 秒失败）。
2. 部署服务器上 PR 守门 Agent 端到端演示可走通：webhook（或手动触发）→ Agent Run → 沙箱取证 → Findings → 门禁裁决，无 `ENVIRONMENT_INCOMPLETE`、无 SecurityException。
3. README 声称（测试基线、Node 版本、配置默认值、API 表）与实测/代码一致。
4. `.trellis/spec/` 有可执行的编码规范与 prompt 管理规范，后续任务被 hook 注入。
5. 前端 21 个视图全部迁移到 Element Plus 体系，`npm test` + `npm run build` 绿。
6. 评测集 ≥30 例且 manifest 校验通过，漏报率作为独立指标可计算。
7. 提示词调优至少完成一轮"改动→评测对比→合入"闭环，漏报率相对基线不升。

## 执行方式（部署服务器）

```bash
git pull
python3 ./.trellis/scripts/task.py start 08-03-r1-fix-unblock-ci   # 按序启动子任务
# 或用 /trellis:continue 让会话自行接续
```

每个子任务完成即独立提交推送，不攒大提交。
