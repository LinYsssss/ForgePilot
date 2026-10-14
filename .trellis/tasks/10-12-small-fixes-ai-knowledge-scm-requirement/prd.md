# ai/knowledge/scm/requirement 四处小修

## Goal

四处互不相关、各自可独立验证的小缺陷，合并为一个轻量任务：

1. `AiGateway.embed` 不像 `chat` 那样检查模型名是否为空：Embedding Profile 未配置时把空模型名发给 provider，provider 的 4xx 被映射成 502 `ai_unavailable`，而不是部署问题该有的 503 `ai_unconfigured`。
2. `KnowledgeService.deleteProjectKnowledge` 为了拿一个计数，把该文档的全部 chunk 实体连正文加载进内存。
3. `ScmSecretCipher.decrypt` 对短于 IV + GCM 标签的损坏密文抛 `ArrayIndexOutOfBoundsException`，非 Base64 输入抛 `IllegalArgumentException`，二者都绕过了"An SCM credential could not be processed"这条受控失败。
4. `RequirementService.reviewerNames` 在已 import `HashSet` 的文件里写 `new java.util.HashSet<>` 并用 `var`，与全文件风格不一致。

## Requirements

- `embed` 在模型名为 null 或空白时抛 `unconfigured()`（503 `ai_unconfigured`），与 `chat` 对 `chat-model` 的处理一致。
- `KnowledgeChunkRepository` 新增 `countByProjectIdAndDocumentId`，删除留痕的 `chunks: N` 改由它计算；`findByProjectIdAndDocumentIdOrderBySeqAsc` 保留给测试使用。
- `decrypt` 对非 Base64、长度不足 IV + 标签、认证失败三种输入都抛同一个 `IllegalStateException`，不泄露细节。
- 风格修正不改变行为。

## Acceptance Criteria

- [x] 新增 `ScmSecretCipherTest`（已编写，未执行）：往返加解密正确且两次加密 IV 不同；非 Base64、过短、篡改、错误密钥四种输入均抛 `IllegalStateException`。
- [ ] `AiGatewayTest`、`KnowledgeServiceTest`、`KnowledgeIngestionProcessorTest`、`ResourceRemovalTest`、`RequirementLifecycleTest` 保持通过。（待 CI：本机无 JDK 21 与 Docker）
- [x] 不新增端点、表、列；API.md 无需改动（`ai_unconfigured` 已是既有错误码）。

## Notes

- 轻量任务，PRD-only。
- 本机无 JDK 21 与 Docker，`./mvnw -B -ntp verify` 需由 CI 或容器路径执行。
