# ADR-001 Embedding Schema：无维度向量列与延迟索引

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §5 · §2.1、[PRD.md](../PRD.md) §4

## 背景

`knowledge_chunk.embedding vector(N)` 建表必须给定固定维度 N，HNSW/IVFFlat 索引同样要求固定维度；
而方案将 Embedding provider/model/dimension 定义为部署配置，Phase 4 之前不选定供应商。
若用 `${embedding.dimension}` 之类占位符按环境渲染 `V1__init.sql`，不同环境的 schema 将不可比、不可复现。

## 决策

1. `knowledge_chunk.embedding` 使用 pgvector **无维度 `vector` 类型**（不带 typmod）。
2. `V1__init.sql` 不绑定任何具体 Embedding 模型和 dimension。
3. provider/model/version/dimension 作为知识 Chunk 的**审计元数据**列保存。
4. V1 不创建依赖固定维度的 HNSW/IVFFlat 索引。
5. Phase 4 确定生产 Embedding Profile 后，再通过**独立 Flyway migration** 创建对应的
   HNSW expression index（如 `USING hnsw ((embedding::vector(N)) vector_cosine_ops)`）。
6. **禁止**在不同环境下用 `${embedding.dimension}` 之类机制改变 `V1__init.sql` 的结构。

## 后果与实施注记

- 索引建立前检索为顺序扫描；MVP 语料规模下可接受。
- 检索 SQL 必须使用与 expression index 一致的 cast 表达式（`embedding::vector(N)`），否则索引不生效。
- DB 不再校验维度：应用层写入时必须校验向量维度与当前配置 Profile 一致，不一致显式失败
  （沿用 Legacy `EmbeddingClient` 的 dimension 校验思想；Phase 4 退出条件已含维度不匹配测试）。
- 距离算子（cosine/L2/inner product）与 HNSW 参数在 Phase 4 的 migration ADR 中一并确定。
- 更换 Profile 仍是维护操作：停写 → reindex → 重建 expression index；失败保留旧数据。
