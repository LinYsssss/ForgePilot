# SCM PR decision actions

## Goal

在人工 Review 终局决策成功后，对 GitHub PR 执行最小 SCM 动作：通过则合并，退回则关闭；动作完成后删除对应远程分支。

## Requirements

- 仅 GitHub Provider；GitLab 保持现有行为。
- `APPROVE` 调用 GitHub merge API。
- `REQUEST_CHANGES` 调用 GitHub close API。
- 合并或关闭成功后删除 head 分支；默认分支不可删除。
- 不新增审计表、队列或独立状态机；已有 Review 决策记录作为业务记录。

## Acceptance Criteria

- [ ] APPROVE 的已完成 Review 能合并对应 GitHub PR。
- [ ] REQUEST_CHANGES 的已完成 Review 能关闭对应 GitHub PR。
- [ ] 成功后删除非默认远程 head 分支。
- [ ] GitLab 决策路径不受影响，现有测试保持通过。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
