# 实现要点：反馈闭环（阶段 3）与错误上报（阶段 4）

> 2026-08-14，trellis-implement 执行档。对应 design.md D3/D4；只动 `backend/`。

## 阶段 3：反馈闭环 backend

### 落点与文件

- 迁移 `V28__review_feedback.sql`（V27 后顺延；V22–V25 预留段未占用）。新表 `review_feedback`，
  FK 级联到 `agent_run` / `agent_finding`，type 组合语义由 `ck_review_feedback_target` 在库层兜底。
- `finding/` 包五件（design 指定域归属）：`ReviewFeedback`（实体）、`ReviewFeedbackType`（枚举）、
  `ReviewFeedbackRepository`、`ReviewFeedbackService`、`ReviewFeedbackController` + `ReviewFeedbackDtos`。
- `SecurityConfig`：`/api/feedback/export` 走 `hasRole("ADMIN")`（与 `/api/scm/installations/**` 同模式）。

### 关键决策（均有依据，非偏离）

1. **旧 `feedback/` 包不复用**：旧表挂在 review_issue（旧 UI 报告链）上，语义对象不同；新表挂
   agent_run/agent_finding，且多出「漏报补录」形态。两者路由不冲突（`/api/review-issues/{id}/feedback`
   vs `/api/agent-runs/{id}/feedback`、`/api/feedback/export`）。
2. **run 访问守卫同源**：复刻 `AgentRunService.requireOwnedRun`——先 404 掉不存在 run，再
   `ProjectService.getRequired` 判 403/404。挂靠型反馈额外校验 finding 真实属于本次 run，
   跨 run 的 finding id 一律按 404 处理（不泄露其它项目资源存在性）。
3. **upsert 语义**：
   - FINDING_*：幂等键 = reporter+findingId+type（design 原文），命中即整体覆盖，部分唯一索引
     `uq_review_feedback_finding_reporter_type`（`where finding_id is not null`）作并发安全网。
   - MISS_REPORT：design 只给了带 finding 的键；漏报无 finding，按「幂等不堆重复行」的同一原则
     把键退化为 run+reporter+path+line（同一位置重复补录=覆盖，位置不同=新记录）。line 可空、
     Spring Data 派生查询对 null 生成 `= null` 永不命中，故匹配在内存做（每 run 每人漏报量极小）。
   - **createdAt 在 upsert 时刷新**：字段清单严格保持 design 的十个字段（无 updatedAt），
     createdAt 语义定为「当前内容的提交时间」——这样 `export?since=` 增量导出不会漏掉被更新的旧记录。
4. **导出格式**：`application/x-ndjson`，每行一个完整 JSON 对象，字段集固定
   `{id, runId, findingId, type, path, line, category, note, reporter, createdAt}`（createdAt 为
   ISO-8601 字符串），由 `ReviewFeedbackControllerTest.exportEmitsOneJsonObjectPerLineWithThePinnedFieldSet`
   钉死。`since` 手工 `Instant.parse`——若声明成 Instant 参数，绑定失败会落进兜底 500 而不是 400。
5. **审计**：写路径记专有事件 `REVIEW_FEEDBACK_SUBMIT`（resource=reviewFeedback:<id>，reason=type
   稳定标识，无用户自由文本），与 `SCM_INSTALLATION_UPSERT` 同风格；导出为只读，不加审计
   （与 `ScmInstallationAdminService.list` 先例一致）。校验被拒时无 SUCCESS 审计（有负向单测）。

### 校验矩阵 ↔ 测试名对照

| 矩阵分支 | 服务层单测（ReviewFeedbackServiceTest） | HTTP 层（ReviewFeedbackControllerTest / Matrix） |
|---|---|---|
| run 不存在 404 | unknownRunIs404 | unknownRunIs404 |
| 无项目权限 403 | foreignProjectDecisionFromTheGuardPropagatesAs403 | strangerCannotAttachFeedbackToAForeignRun；矩阵 strangerCannotAttachFeedbackToSomeoneElsesRun |
| 匿名 401 | —（HTTP 层职责） | anonymousCallersAreRejectedOnBothEndpoints；矩阵 anonymous 清单新增两路径 |
| FINDING_* 缺 findingId 400 | findingFeedbackWithoutFindingIdIs400 | invalidTypeFieldCombinationsAre400 |
| MISS_REPORT 缺 path 400 | missReportWithoutPathIs400AndBlankPathCountsAsMissing | 同上 |
| MISS_REPORT 带 findingId 400 | missReportCarryingAFindingIdIs400 | 同上 |
| 未知 type / note>2000 / line≤0 400 | —（入口 Bean Validation 职责） | 同上 |
| finding 不存在/跨 run 404 | unknownOrForeignFindingIs404 | findingOfAnotherRunIs404 |
| upsert 幂等（不 409） | resubmittingSameReporterFindingTypeOverwritesInsteadOfDuplicating / missReportUpsertsOnSamePathAndLineButNotAcrossLocations / pathIsStrippedBeforeMatchingAndStoring | resubmittingSameFindingAndTypeUpsertsToTheLatest / missReportsAtDifferentLocationsStayAsSeparateRecords |
| 导出格式钉死 | exportWithoutSinceIsFullAndWithSinceIsIncremental | exportEmitsOneJsonObjectPerLineWithThePinnedFieldSet / exportSinceFiltersIncrementallyAndRejectsGarbage |
| 导出管理员门 | —（SecurityConfig 职责） | exportIsForbiddenForNonAdmins + 矩阵 anonymous |
| 审计断言 | firstSubmissionPersistsAllFieldsAndAudits / rejectedSubmissionLeavesNoAuditRecord | submitPersistsEchoesAndLeavesAnAuditTrail（ListAppender 实读 security.audit 流） |

### curl 操作序列（服务器验收用）

```bash
# 登录拿 cookie（CSRF 开启的部署需先 GET /api/auth/csrf 并带 X-XSRF-TOKEN 头）
curl -c /tmp/rs.jar -H 'Content-Type: application/json' \
  -d '{"username":"<user>","password":"<pass>"}' https://<host>/api/auth/login

# 误报（挂 finding）
curl -b /tmp/rs.jar -H 'Content-Type: application/json' \
  -d '{"type":"FINDING_FALSE_POSITIVE","findingId":21,"note":"参数已在上游校验"}' \
  https://<host>/api/agent-runs/7/feedback

# 漏报（path+line+category+note）
curl -b /tmp/rs.jar -H 'Content-Type: application/json' \
  -d '{"type":"MISS_REPORT","path":"src/OrderService.java","line":88,"category":"security","note":"退款未鉴权"}' \
  https://<host>/api/agent-runs/7/feedback

# 重复提交同 finding 同 type：返回同一条记录 id，note 变为最新（200，不 409）

# 管理员导出（全量 / 增量）
curl -b /tmp/admin.jar https://<host>/api/feedback/export
curl -b /tmp/admin.jar 'https://<host>/api/feedback/export?since=2026-08-01T00:00:00Z'
```

## 阶段 4：错误上报

### 落点与文件

- 新领域包 `clienterror/`：`ClientErrorController`（绑定+委托）、`ClientErrorReportService`
  （清洗+截断+落日志）、`ClientErrorReport`（载荷 record，@NotBlank message/url、@NotNull ts、stack 可空）。
- `RateLimitFilter`：按登录桶的既有样式扩第三个独立桶 `client-errors:`，新全参构造器带
  `clientErrorsLimit`；旧构造器回落默认 10（与配置默认一致），既有行为零变化。
- `app-boundary.yml`：`app.ratelimit.client-errors-limit: ${RATE_LIMIT_CLIENT_ERRORS:10}`（配置族样式）。
- `SecurityConfig`：`/api/client-errors` permitAll + CSRF 豁免（`ignoringRequestMatchers` 追加）。

### 关键决策

1. **CSRF 豁免是必需而非便利**：前端接线走 sendBeacon（页面卸载时也能发），它无法携带自定义头，
   CSRF 的 header 回传机制物理上走不通；端点匿名可达且不改服务端状态（只落日志），豁免无被利用面。
   `CsrfProtectionTest.clientErrorReportIsExemptFromCsrf` 钉住，防未来收窄豁免清单时静默弄死上报。
2. **4KB 截断的预算分配**：总预算 4096 字符 = url(≤512) + message(≤1024) + stack(吃剩余)；
   截断带可见标记 `...(truncated)`。清洗在截断之前（换行转义会增长字符串，先截后洗会超预算）。
3. **日志注入防御**：换行转字面 `\n`（栈帧可离线还原）、`\r` 丢弃、其余控制字符转 `_`——
   一次上报恒为一行日志，无法伪造第二条记录。独立 logger 名 `client.error` + marker `CLIENT_ERROR`
   （与 security.audit 独立事件流同思路），级别恒 WARN（前端报错是降级信号，非后端故障）。
4. **不入库**：无 repository 依赖；校验不过的载荷不落日志（空 message 无诊断价值，只是免费写入口）。

### 测试对照

- 截断/预算：`ClientErrorReportServiceTest.oversizedStackIsTruncatedWithinTheTotalBudget` /
  `urlAndMessageHaveTheirOwnCapsSoStackKeepsMostOfTheBudget`
- 日志注入：`newlinesAreEscapedSoTheRecordStaysOneLine` / `otherControlCharactersAreNeutralized`
- marker+WARN：`writesOneWarnLineWithTheDedicatedMarker`；stack 可空：`missingStackFallsBackToAPlaceholder`
- 匿名可达：`ClientErrorControllerTest.anonymousReportIsAcceptedAndLogged`
- 字段校验：`missingRequiredFieldsAre400AndNothingIsLogged`
- 限流：`floodFromOneSourceIsThrottledWithRetryAfter`（429+Retry-After）；
  桶独立与旧构造器回落：`RateLimitFilterTest.clientErrorReportsHaveTheirOwnBudgetSeparateFromLoginAndGeneralTraffic` /
  `legacyConstructorsDefaultTheClientErrorBudgetToTen`
- CSRF 豁免：`CsrfProtectionTest.clientErrorReportIsExemptFromCsrf`

## 与 design.md 的偏离

零。两处 design 未详述、由实现补全并在代码注释与本档记录的解释性决策：
MISS_REPORT 的幂等键退化（同一幂等原则的自然延伸）、upsert 刷新 createdAt（保证 since 增量不漏更新）。

## 边界遵守

- 未触碰：deploy/、docs/、frontend/、evaluation/、demo-repos/、backend `prompts/`、既有迁移文件。
- 既有端点行为零变化（RateLimitFilter 旧构造器语义不变；SecurityConfig 仅新增匹配项）。
- 前端接线（main.js sendBeacon 挂载）按计划留在尾段阶段（墨境步骤 6/7 落库后）。
