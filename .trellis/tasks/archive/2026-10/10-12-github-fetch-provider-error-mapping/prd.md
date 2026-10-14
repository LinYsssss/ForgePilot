# GitHub 权威快照读取的错误映射对齐 GitLab

## Goal

`GitHubClient.fetch` 的三类 HTTP 读取（PR 元数据两次、files 分页）不包装 RestClient 异常。GitHub 答 4xx/5xx 或网络超时时，`RestClientResponseException` / `ResourceAccessException` 一路漏到 `ApiExceptionHandler` 的兜底分支，投递以 500 `internal_error` 结束并打印堆栈；`GitLabClient.request` 对同类失败已映射为 503 `provider_unavailable`（429/5xx/网络）和 502 `provider_error`（其他 4xx）。文档宣称两家 Provider 遵循同一 contract，这里没做到。

## Requirements

- 新增 `GitHubClient.request(Supplier<JsonNode>)`，只包装 `fetch` 路径上的读取：429/5xx 与 `ResourceAccessException` → 503 `provider_unavailable`；其他 `RestClientResponseException` → 502 `provider_error`；空响应 → 422。
- files 分页若不是 JSON 数组，显式 422 拒绝，而不是迭代出零个文件后存下空清单。
- `merge()` 的处理保持不变：5xx 后只读确认，其他拒绝答 409，响应丢失答 503 `merge_outcome_unknown`。
- `API.md` webhook 行记录两家 Provider 共同的错误契约。

## Acceptance Criteria

- [x] `GitHubClientGuardTest` 新增（已编写，未执行）：PR 元数据读取由桩服务答 500 与 429 时，投递返回 503 且 `code=provider_unavailable`；答 404 时返回 502 且 `code=provider_error`；三种情形数据库均无写入。
- [x] `GitHubClientGuardTest` 新增（已编写，未执行）：files 分页答非数组 JSON 时返回 422 且无写入。
- [x] 现有 GitHub 与 GitLab 的 ingestion / guard / merge 测试全部通过。（CI run 38555642951，2026-10-14，backend verify job 通过）
- [x] `docs/v2/API.md` 已更新；`.trellis/spec/backend/error-handling.md` 登记了两家 Provider 共同的错误码。

## Notes

- 轻量任务，PRD-only。
- 本机无 JDK 21 与 Docker，`./mvnw -B -ntp verify` 需由 CI 或容器路径执行。
