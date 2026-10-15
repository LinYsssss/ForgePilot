# 技术设计：知识入库 Embedding 分批请求

## 边界

- 只改 `knowledge` 包：`KnowledgeIngestionProcessor` 与 `application.yml` 的 `forgepilot.knowledge.embedding.*` 配置。
- `ai.AiGateway.embed(List<String>, model, context)` 契约不变：仍是"每段文本一个向量，顺序与传入一致"。分批是调用方的事，网关不知道批。
- 不触碰 `ChunkSearchRepository.writeEmbedding` 的维度校验，它仍是每个向量写入前的唯一防线。

## 契约

```text
processNext():
  pending = 取 id 最小的 PENDING（短事务）
  pieces  = KnowledgeService.split(text)
  vectors = []
  for batch in chunksOf(pieces, batchSize):
      vectors += ai.embed(batch, model, context)   # 每批一次 HTTP、一行 ai_call_log
      require(size == batch.size)
  一个事务：锁文档行 → 仍 PENDING 才写入全部 chunk + 向量 + Profile → READY
  任一步 RuntimeException：新事务标记 FAILED（受控原因）；清理失败则保留 PENDING
```

- `batchSize` 来自 `forgepilot.knowledge.embedding.batch-size`，默认 64，`< 1` 启动失败（与限流配置"0 fails startup"同一态度）。
- `chunksOf` 是本类的私有静态方法，不引入工具库。

## 数据流与失败形态

| 情形 | 行为 |
|---|---|
| 单批成功 | 与现状完全一致 |
| 多批全部成功 | 向量累积后一次写入；`ai_call_log` N 行 |
| 第 k 批 provider 失败（`ai_unavailable` 等） | 之前批次的向量丢弃，不写任何 chunk；文档 FAILED，原因 `文档处理失败（ai_unavailable）。` |
| 第 k 批数量不符 | 同上，原因 `unprocessable` |
| 写入事务中途失败 | 现有行为：派生行回滚，FAILED |

## 取舍

1. **单事务写入 vs 逐批落库。** 选单事务：保住 §5"READY 之前不可检索、无半入库文档"的不变式，也保住"失败即无派生行"的清理语义；代价是向量在内存累积（见 PRD 约束）。逐批落库要新增"失败后删除已写 chunk"的补偿路径，并重写 §5，不在本任务做。
2. **批大小默认 64。** 远低于常见 2,048 上限，单次响应约 1 MB 级，调用次数在极限文档下约 70 次；更大的默认值节省调用但放大单次响应，不值得。
3. **不下调 5 MB 上限。** 它是 7.2 的冻结实测值；要动它应单独立项并更新表格、TESTING-GUIDE 与 SRS。

## 兼容性

- 无 schema 变化，无端点变化。
- 新配置项有默认值，旧部署不加配置即生效。
- 历史 FAILED 文档不自动重试，与现状一致（PRD §7 已声明无重试端点）。

## 发布与回滚

- 发布：随下一次后端镜像；无需迁移。
- 回滚：把 `batch-size` 设为足够大的值即退化为单次请求，或回退提交。
