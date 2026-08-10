# 立规范：编码规范+prompt管理规范+契约测试纪律

> 前置：r1-r3 完成（规范要基于修复后的真实代码提炼，而不是给带病代码拍照）。使用 `trellis-spec-bootstrap` skill 执行，产出落 `.trellis/spec/`，供 hook 在后续所有任务中自动注入。

## Goal

把"什么是这个仓库的好代码"从口口相传变成可执行文档，使 r5/r6 重构与全部后续开发对着同一标准干活；把审计暴露的两类系统性教训（契约漂移、prompt 无治理）固化为纪律。

## Requirements

### R1 包级编码规范（trellis-spec-bootstrap 标准流程）

- 对 backend / frontend / sandbox-runner / model-service 四个包，基于真实代码提炼：分层与依赖方向、命名、错误处理与 ErrorCode 使用、事务边界（含 `@Transactional` 不自调用等既有约束）、测试写法与目录、日志与 traceId 纪律。
- 规范条目必须引用仓库内真实代码为例证（spec-bootstrap 的反占位符要求），禁止空话模板。
- 现有跨任务冻结契约（ErrorCode / PageResponse / ProjectAuthorization、Flyway 不可变迁移、授权矩阵测试准入）写入 spec 成为常驻约束。

### R2 契约测试纪律（F-03 教训的固化）

- 新增 spec 条目：**任何跨进程/跨模块传递的数据格式（MQ 载荷、共享字符串引用、REST 契约）必须有"生产方真实产出驱动消费方"的双向契约测试**；禁止两侧各自用手造数据测自己。
- 以 r2 落地的 `WorkspaceArchiveReference` 契约测试为范例引用。

### R3 Prompt 资产管理规范（用户硬约束的固化）

写入 `.trellis/spec/`（r8 的执行前提）：

- **宁精勿多**：每模板单一职责；检查清单条目设上限（建议 ≤10/清单）且每条可验证（能落到行号/规则）；新增条目须附"它能抓住什么漏报案例"，否则不准入。
- **漏报 recall-first**：初审层宁多报，复核层压误报；规则引擎+分类器为确定性兜底；禁止以降低召回换误报率好看。
- **版本化与评测门禁**：模板变更必须带评测集对比结果才能合入；漏报率相对上一版本不得上升。
- **退役机制**：连续 N 轮评测无捕获贡献的条目标记退役候选，定期清理。
- **禁承诺红线**：对外文档表述用"漏报率实测持续下降+多层兜底"，禁止"零漏报"。

### R4 演示资产与写实口径纪律

- demo-repos 故意缺陷保留义务、README 诚实声明不可回退、能力表述以实测为准——收敛成一条 spec，避免散落在各任务 prd 里。

## Out of Scope

- 重构本身（r5/r6）；prompt 模板的实际编写（r8）；对 demo-repos 内部代码立规范。

## Acceptance Criteria

- [ ] `.trellis/spec/` 四包规范就位，每条有真实代码例证锚点，`spec/guides/index.md` 索引更新。
- [ ] 契约测试纪律条目存在并引用 r2 范例。
- [ ] prompt 管理规范五条（精简/召回/门禁/退役/红线）全部成文。
- [ ] 新开任务时 hook 能注入相应 spec（实测一次 `get_context.py --mode packages`）。
- [ ] 规范总量克制：宁缺毋滥，禁止为凑条目写显而易见的通识（规范本身也适用"宁精勿多"）。

## Validation

```bash
python3 ./.trellis/scripts/get_context.py --mode packages
ls .trellis/spec/
```
