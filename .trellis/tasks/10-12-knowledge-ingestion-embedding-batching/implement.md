# 执行计划：知识入库 Embedding 分批请求

## 前置

- [ ] 人工审阅 `prd.md` 与 `design.md`，特别是"接受约 100 MB 内存峰值"这一决定。
- [ ] `python ./.trellis/scripts/task.py start 10-12-knowledge-ingestion-embedding-batching`

## 步骤

1. [ ] `application.yml`：在 `forgepilot.knowledge.embedding` 下新增 `batch-size: ${FORGEPILOT_KNOWLEDGE_EMBEDDING_BATCH_SIZE:64}`；`.env.example` 与 `compose.yaml` 登记同名变量（compose 用 `:-64` 默认，理由同 `FORGEPILOT_REVIEW_CONCURRENCY`）。
2. [ ] `KnowledgeIngestionProcessor`：构造器注入 `@Value("${forgepilot.knowledge.embedding.batch-size:64}") int batchSize`，`< 1` 抛 `IllegalStateException`；`processNext` 改为按批调用 `ai.embed` 并累积，保持原有数量校验与单事务写入。
3. [ ] `KnowledgeIngestionProcessorTest`：
   - 新增"切片数 = 2 × batchSize + 1 的文档产生 3 次 embed、每次输入不超 batchSize、全部写入、READY"；
   - 新增"第二批抛 `ApiException` 时无 chunk、FAILED、原因含错误码"；
   - 现有用例不改。
4. [ ] `docs/v2/ARCHITECTURE.md` §5 补一句分批说明；7.2 表不改。
5. [ ] 交付文档按惯例追加带日期的补充段（MANUAL / OPERATIONS 提到入库的位置），不重写快照。

## 验证命令

```bash
# 本机无 JDK 21 / Docker，走容器路径（backend/README.md）
docker run --rm --network host -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" \
  -v /var/run/docker.sock:/var/run/docker.sock -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
git diff --check
grep -ho -- '-Xms128m[^"}]*NativeMemoryTracking=summary' backend/Dockerfile compose.yaml .env.example | sort -u   # 必须仍是一行
```

## 评审门

- 单事务写入与"失败即无派生行"的不变式未被削弱。
- `ai_call_log` 行数与批数一致，可审计。
- 新配置默认值在三处（yml / .env.example / compose）一致。

## 回滚点

- 步骤 2 之后可独立回退（配置项多出来无害）。
- 全部完成后回退整个提交即恢复单次请求行为。
