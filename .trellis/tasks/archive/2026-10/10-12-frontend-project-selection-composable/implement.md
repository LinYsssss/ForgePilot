# 执行计划：前端项目选择 composable

## 前置

- [ ] 人工审阅 `prd.md` 与 `design.md`，确认 `projectPath` 落点与"首项自动选中"按现状保留的原则。
- [ ] `python ./.trellis/scripts/task.py start 10-12-frontend-project-selection-composable`

## 步骤

1. [ ] 逐页记录现状：五个页面的项目加载时机、无 `project` 参数时的默认行为、切换时用 `push` 还是 `replace`、加载失败时的展示。写入本任务 `research/current-behaviour.md`，作为"零行为变化"的对照表。
2. [ ] 新增 `composables/useProjectSelection.ts`，按 `design.md` 契约实现，`autoSelectFirst` 等选项逐页对应第 1 步的记录。
3. [ ] 迁移 `WorkspacePage` → `RequirementsPage` → `ReviewsPage` → `KnowledgePage` → `RepositoryPage`，每迁一页跑一次 `npm run typecheck`；`ProjectsPage` 只替换项目列表加载。
4. [ ] `lib/http.ts` 导出 `projectPath`；删除 `knowledge/api.ts`、`review/api.ts`、`scm/api.ts` 的三份定义并改为 import。
5. [ ] 去掉未被导入的 `export`：以 `grep -rhoE "import \{[^}]+\}" frontend/tests frontend/src` 的符号集合为白名单，不在集合内且只在本文件使用的才去掉。
6. [ ] 更新 `.trellis/spec/frontend/directory-structure.md`（`composables/` 现在有两个共享原语）与 `state-management.md`（登记 `useProjectSelection` 的职责与边界）。

## 验证命令

```bash
cd frontend
npm run lint
npm run typecheck
npm run build
npm run test -- --run     # 本机 Windows 下 vitest 有环境问题，以 CI 结果为准
grep -rn "function projectPath" src | wc -l   # 期望 1
```

## 评审门

- 对照第 1 步的现状表，每页行为逐项勾销。
- `journey.spec.ts` 覆盖的按钮可见性与跳转全部通过。
- 规范文档已更新且与代码一致。

## 回滚点

- 每迁移一页一个提交，任何一页出问题可单独回退。
- 步骤 4、5 各自独立成提交。
