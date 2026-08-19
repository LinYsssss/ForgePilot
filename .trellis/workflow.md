# ForgePilot Trellis Workflow

## 原则

1. 先读 `docs/v2/AI-HANDOFF.md`，再读当前 Phase 的产品与架构规范。
2. 每次只建立一个活动任务，写清目标、非目标、验收标准和影响范围。
3. 先完成最小纵向切片，再扩展同一能力；禁止多条业务线并行生长。
4. 旧代码只从 RepoSage 按迁移矩阵定向提取，禁止整包搬运。
5. 任务完成必须留下测试或可重复验证命令；未验证不得标记完成。
6. 新决策先写 ADR，随后再改规范和代码。

## 任务生命周期

```text
proposed → planned → in_progress → verified → completed
                         └────────→ blocked
```

每个任务目录建议包含：

```text
.trellis/tasks/<date>-<slug>/
├── task.md       目标、非目标、验收标准
├── design.md     必要时记录接口和取舍
└── result.md     变更、验证和遗留问题
```

## 开始任务

- 确认没有其他活动任务。
- 确认任务属于当前 Implementation Phase。
- 检查相关 ADR 和 Legacy 迁移矩阵。
- 不明确的产品问题先停止实施并更新 PRD。

## 完成任务

- 验收标准逐项通过。
- 测试、构建和静态边界检查通过。
- 文档只在权威位置更新，其他位置使用链接。
- 将可复用经验写入 `result.md`，不要保留冗长聊天记录。
