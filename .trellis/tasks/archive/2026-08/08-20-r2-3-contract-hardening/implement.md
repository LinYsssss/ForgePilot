# R2.3 执行计划

## 顺序

1. 修订 PRD、ARCHITECTURE 与 DECISIONS，补齐数据、事务、身份、权限、activity、SCM 和 Finding 契约。
2. 将阶段闸门、统一 result 模板、测试/评测纪律和 Phase 1 下一步并入 IMPLEMENTATION-PLAN。
3. 更新根 README、AGENTS、CLAUDE 与目录入口，只指向六份权威文档。
4. 删除 FINAL-EXECUTION-PLAN、AI-HANDOFF、CLEANUP-AND-LEGACY 和 `docs/v2/adr/`；确认唯一内容已迁移且可由 Git 恢复。
5. 归档旧 R2 任务，执行全仓检索、Markdown 链接、表数、包边界、授权状态和业务源码零变更检查。

## 验证命令

```bash
git diff --check
rg -n "尚未收到|仍等待|当前指令仅|14 张|14表|context_revision|自动补建|无 Review.*自动|ADR-[0-9]+|FINAL-EXECUTION-PLAN|AI-HANDOFF|CLEANUP-AND-LEGACY" README.md docs/v2 AGENTS.md CLAUDE.md backend frontend evaluation
git status --short
git diff --stat
```

另外检查：

- `docs/v2/` 只剩六份权威 Markdown 文档，且 16 张表仍只在 ARCHITECTURE 完整定义。
- `scm` 不依赖 `review`、Finding 不形成顶层包、Phase 1 禁止业务代码的表述一致。
- 所有新增契约都有对应未来 Phase 5/6 集成测试条目。
- 旧 R2 与当前 R2.3 任务最终均归档；归档前当前任务有完整 result.md。

## 风险点

- 不把具体数据库触发器或 provider API 字段提前写死成产品契约。
- 不因修订 activity 或 Diff fingerprint 而新增业务表、第二 Pipeline 或 MQ。
- 不修改现有业务源代码（当前仓库应无业务源代码）。

## 回滚点

若文档交叉检查发现边界扩大，回滚本任务文档提交；不得继续实施 Phase 1，先由用户重新裁决范围。
