# 知识入库 Embedding 分批请求 验证记录（2026-10-14）

## 状态

已实现（提交 `b63bb37`）、推送（分支 `fix/review-audit-fixes`）并通过 CI 全量验证，任务归档。本机无 JDK 21 与 Docker，本地从未编译或执行；CI 是第一次真实执行。未部署，未触发真实模型调用。

## 后端

CI run 38670011837（2026-10-15），backend verify job 116210677901：

```text
Tests run: 432, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`KnowledgeIngestionProcessorTest` 新增两例：切片数超过批大小的文档分批请求（断言批次数、每批输入不超过批大小、全部 chunk 落库、文档 READY）；后续批次向量数不符时整篇 FAILED 且零 chunk 写入。新增 2 项与既有 430 项、ArchUnit、真实 PostgreSQL/pgvector 约束测试同批通过。

## 其余门（同一 run）

Evaluation contract（含更正后语料的全部检查）、frontend 18 个测试文件、空库 Compose 冷启动全部通过。

## 边界

- 配置项 `forgepilot.knowledge.embedding.batch-size`（默认 64）登记于 application.yml、compose.yaml、.env.example；ARCHITECTURE.md §5 更新为分批请求、单事务写入语义。
- 峰值内存的决定与两项不立项（下调上传上限、逐批落库）见 prd.md Constraints（2026-10-14 决定）。
- 未部署：部署身份仍是 `54e2953`，本分支含未部署改动。
