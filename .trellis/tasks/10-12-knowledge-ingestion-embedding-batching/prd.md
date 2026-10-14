# 知识入库 Embedding 分批请求

## Goal

`KnowledgeIngestionProcessor.processNext` 把一篇文档的全部切片放进**一次** `ai.embed` 请求。切片 1200 字符、`KnowledgeUploadValidator` 允许 5 MB，极限是约 4,370 个输入；常见 OpenAI 兼容 provider 的单请求上限约 2,048 个输入，超出直接 4xx，文档被标为 FAILED 且"只能删除重传"，重传必然再次失败。即便 provider 接受，单次响应是几十 MB 的 4096 维 JSON，在 384 MB 堆里解析有 OOM 风险；`OutOfMemoryError` 不被 `catch (RuntimeException)` 捕获，文档会停在 PENDING 被每秒重试。

## Requirements

- Embedding 请求按固定上限分批（新配置 `forgepilot.knowledge.embedding.batch-size`，默认 64），每批一次 `ai.embed`，复用同一个 `AiCallContext`，于是 `ai_call_log` 每批一行，重试与失败仍可审计。
- 保持 ARCHITECTURE.md §5 的写入语义：全部批次成功后，仍以**一个**事务写入全部 chunk、Profile、向量并标记 READY；任一批失败则不写任何派生行，在新事务标记 FAILED。
- 任一批返回的向量数与该批输入数不符时，整篇文档失败（沿用现有校验）。
- 不改 `AiGateway.embed` 的契约，不改 schema，不改 5 MB 上传上限（ARCHITECTURE.md 7.2 冻结值）。

## Constraints

- 向量在内存中累积直到最终事务：5 MB 极限文档约 4,370 × 16.4 KB ≈ 72 MB，加上切片正文与 chunk 实体，峰值估计 100 到 120 MB。入库串行，与两个 Review worker 共享 384 MB 堆。**这是需要你拍板的点**：接受此峰值（本任务范围），还是另开任务下调上传上限或改为逐批落库（后者要重写 §5 的"一个事务"措辞并补失败清理）。
- 同一文档的多批请求之间不续租、不加锁：入库由单个 fixed-delay 调度保持串行，与现状一致。
- "队首坏文档阻塞后续文档"（始终取 id 最小的 PENDING）是另一个问题，不在本任务内。

## Acceptance Criteria

- [ ] `KnowledgeIngestionProcessorTest` 新增：切片数超过批大小的文档触发 N 次 `embed`、每次输入不超过批大小、所有 chunk 与向量写入、文档 READY；第二批抛 `ApiException` 时无 chunk 写入、文档 FAILED、原因受控。
- [ ] `ai_call_log` 行数等于批数（通过真实网关的测试或 mock 调用计数之一证明）。
- [ ] 现有 `KnowledgeIngestionProcessorTest`、`KnowledgeServiceTest`、`AiGatewayTest` 通过。
- [ ] ARCHITECTURE.md §5 增加一句："向量按批请求 provider，批大小可配置；写入仍是一个事务"。
- [ ] `.env.example` 与 `application.yml` 登记新配置项及默认值。

## Notes

- 复杂任务：`design.md` 与 `implement.md` 已备，经人工审阅后再 `task.py start`。
- 本机无 JDK 21 与 Docker，验证走 CI 或容器路径。
