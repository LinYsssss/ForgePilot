# 代码缺口改进方案(2026-10-18)

上一轮盘点出四个代码缺口;本方案的取舍原则不变:**最小改动、只锁承重不变量、不动冻结资产**。

## 一、本轮实现(三项)

### 1. 知识文档失败重试

- **现状**:`KnowledgeIngestionProcessor` 串行消费 PENDING 文档,失败即标记 FAILED(带受控原因);
  没有任何接口能把 FAILED 放回队列,只能删除重传。
- **设计**:`KnowledgeDocument.requeue()`(PENDING + 清 failureReason);`KnowledgeService.retryProjectKnowledge`
  (LEADER 门,与上传同一道权限;行锁后**只允许从 FAILED 进入**,否则 409);
  `POST /api/projects/{id}/knowledge/documents/{documentId}/retry` 返回文档视图。
- **为什么不需要清理分块**:失败时结果事务整体回滚,不存在半成品分块(处理器注释即此语义)。
- **承重不变量(测试)**:PENDING/READY 重试一律 409;FAILED 重试后回到 PENDING 且原因为空,
  后台处理器能按同一条流程重新消费成 READY;非 LEADER 403。

### 2. 审查列表补「提示词 · 模型」

- **现状**:`prompt_version` 与 `model` 是 `review` 表的真实列,详情页展示,但列表行
  (`ReviewViews.ProjectReviewRow`)不带,列表上分不清一行是谁审的。
- **设计**:`ProjectReviewRow` 增加 `promptVersion`、`model` 两个字段,`ReviewDecisionService.listForProject`
  映射时带上;前端审查表新增「提示词 · 模型」列(mono 小字,未记录时为「未记录」)。
- **不做**:失败原因文本——`review` 表没有该列,补它要动迁移与执行器写入,超出最小改动;
  失败行仍可见「执行失败」状态与详情页的执行次数。

### 3. AI 调用台账只读接口 + 工作台面板

- **现状**:`ai_call_log` 682 次调用只在数据库里;页面无法回答「模型花了多少、多快、败了几次」。
- **设计**:读模型沿用 `ReviewInsightRepository` 的朴素 SQL(JdbcTemplate,项目硬过滤):
  按 `use_case` 聚合调用数、成功数、未成功数(含 TIMEOUT)、token 总量与
  `percentile_disc` 的 P50/P95(离散分位,小样本下取的必是真实样本值);
  `GET /api/projects/{id}/ai-usage`(成员可读,与校准同一理由:不含 prompt 与回答正文);
  工作台在校准面板下新增一个折叠面板「AI 调用台账」。
- **承重不变量(测试)**:聚合只数本项目(另一个项目的调用绝不混入,跨项目成员 404)、
  按用途分组、成功/未成功口径与 token 求和正确。

## 二、本轮不做(附理由与未来最小路径)

### 列表分页

- **理由**:①两个大列表(需求、审查)的筛选都在前端做,先分页会让筛选只见已加载页,
  等于用分页换一个更隐蔽的错;②把筛选移到服务端是一个横跨接口契约、前端筛选器与
  测试的改造,规模远超本轮;③演示数据量(≤94 条)下分页没有收益。
- **未来最小路径**:列表端点加**可选** `offset`/`limit`(不带参数行为不变,契约向后兼容),
  先把审查列表的四个前端筛选下沉到 SQL,再做「加载更多」;需求列表同理。

### 其余已知项(非本轮代码缺口)

- 两处千行 SFC:既定决策不拆。
- 89 项浏览器人工验收:属于验收动作,单独排期。
- GitLab/钉钉真实联调:环境证据问题,不是代码缺口。

## 三、改进后的数据生成方案(作为本轮的交付后半程)

目标:让工作台主页上的 KPI、动态、校准表出现**真实**数据,而非 0 与两个月前的旧值。

1. **新审查(review-5 首个线上产出)**:以 `rev01`(REVIEWER)在 halo-replay 触发 2 到 3 轮真实审查,
   每轮真实消耗模型调用;完成后「进行中 / 待最终决定 / 最近动态」都有当前版本的数据。
2. **Finding 裁决**:逐条阅读最近一轮的 OPEN finding(说明、证据、类别),以审阅人身份
   做 CONFIRM / REJECT;每步都有 `finding_event` 审计记录。校准表因此获得非零分箱。
3. **审查决定**:只对理由充分的审查做 `REQUEST_CHANGES`(强制填理由,无远端副作用);
   **不做 APPROVE**——桥接会按被审 SHA 真实合并 GitHub PR,属于外部写操作,不在本轮授权内。
   留一批「已完成未决定」的审查在队列里,让工作台「待最终决定」保持真实队列形态。
4. **验证**:工作台 KPI、最近动态、校准表与 AI 台账面板逐项截图核对;数据快照用
   `docs/thesis/export-live-data.py` 复核。

## 验收

- 后端:定向测试(KnowledgeServiceTest / ReviewInsightTest)通过,随后固定容器全量 432 项。
- 前端:lint / typecheck / 66 项 / build 四门。
- 部署:frontend + backend 重建;公网核对;主页数据截图存档。
