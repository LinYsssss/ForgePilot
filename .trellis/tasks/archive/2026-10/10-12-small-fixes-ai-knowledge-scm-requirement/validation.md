# ai/knowledge/scm/requirement 小修 验证记录（2026-10-14）

## 状态

已实现（提交 `729b0e1`）、推送（`fix/review-audit-fixes`）并通过 CI 全量验证，任务归档。本地从未编译或执行；CI 是第一次真实执行。未部署。

## 后端

CI run 38555642951（2026-10-14），backend verify job 115824136668：

```text
Tests run: 430, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

相关类：`ScmSecretCipherTest` 2 项（新类：往返加密与四种损坏输入）、`AiGatewayTest`、`AiGatewayConfigurationTest`、`KnowledgeServiceTest`、`KnowledgeIngestionProcessorTest`、`ResourceRemovalTest` 均同批通过。`embed` 空模型名检查的回归风险已提前排查：所有触及 embedding 路径的集成测试都以 `@MockitoBean` 替换网关，只有 `AiGatewayTest` 与 `AiGatewayConfigurationTest` 走真实网关且传非空模型名。

## 其余门（同一 run）

frontend 18 个测试文件全过；evaluation 契约三项通过；两次空库 Compose 冷启动通过。

## 边界

- `KnowledgeServiceTest` 的 `chunks: 1` 留痕断言在 count 查询下保持不变。
- 无端点、表、列变化；API.md 无需改动（`ai_unconfigured` 是既有错误码）。
