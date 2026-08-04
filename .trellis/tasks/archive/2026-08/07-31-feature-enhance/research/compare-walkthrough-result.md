# 对比审查端到端走查结果（2026-08-04，Playwright 自动走查）

- 环境：宿主 Vite dev (localhost:5173，代理 → localhost:8080) → dev profile 后端容器
  （H2 内存库 + mock AI + inline 审查 + **CSRF 开启**，`maven:3.9-eclipse-temurin-17` 挂载
  `/root/reposage:/ws` 以 `spring-boot:run` 启动，种子管理员经 `SEED_ADMIN_*` 注入）。
- 驱动：`compare-e2e.mjs`，Playwright v1.49.1 容器（--network host，`demo-repos` 挂载至 `/repos`，
  依赖为宿主预装的 `playwright@1.49.1` 挂载进容器，避免容器内 npm）。

## 结果：通过（E2E OK，exit 0）

| 步骤 | 结果 |
|---|---|
| 登录（bootstrap → login 带旧令牌 → 轮换新令牌 → initCsrf 重引导） | ✅ 200，写请求全程携带 XSRF |
| 创建项目「对比审查演示」 | ✅（c0-projects.png） |
| 绑定演示仓库 `/ws/demo-repos/mall-order-service`（LOCAL） | ✅（c1-repository.png） |
| 上传 4 篇知识文档并入库 | ✅ 4/4 INDEXED（c2-knowledge.png） |
| 一键「对比审查」→ 双任务创建 → 对比视图 | ✅ 任务 2 条、报告 2 条（c3-compare.png，全页） |
| 页面错误（pageerror） | 0 |
| 非 2xx | 仅预期探测：登录前 `/auth/me` 401、绑定前 `/repository`、`/reviews/tasks` 404 |

## 对比结论的口径（重要）

mock 模式下两侧产出**相同**（带 1 / 不带 1 / 知识库多发现 +0 / 引用文档 0，共有 1 条 HIGH
「管理接口可能缺少权限校验」，来自规则引擎对 M7 形态的兜底信号）。这是**预期行为**：
mock 规则引擎不读知识文档，三档产出相同（见 `docs/演示素材与缺陷对照表.md` §5.3 注、
`docs/11_本地开发与联调手册.md` §9）。本走查验证的是**流程闭环与 UI 正确性**
（双任务幂等键按文档集区分、三栏差异分类、信号徽标）；知识库带来的 Finding 差异
（对照表 M1~M10 B/C 类命中）需 `AI_PROVIDER=openai-compatible` 接真实模型演示。

## 途中发现并修复的真实缺陷（本任务代码改动的来源）

无状态会话 + Spring Security 默认 `CsrfAuthenticationStrategy` 会在每个已认证响应里
清除 XSRF Cookie（延迟重发永不触发，普通 JSON 接口不渲染令牌），浏览器令牌被永久抹掉，
登录后第一个写请求 403 并触发全局登出——MockMvc 的 `csrf()` 后处理器测不出该时序。
修复：CSRF 配置改用 `NullAuthenticatedSessionStrategy`（轮换由登录/登出的 `CsrfTokenRotator`
显式完成）+ 前端登录/登出后 `initCsrf()` 重引导。回归测试
`SpaCsrfBrowserFlowTest` 按真实浏览器时序断言（已认证读不得清 Cookie、登录后首个写成功）。

## 脚本加固记录

- 知识文档上传循环由固定 1200ms 改为等 `.doc-card` 出现（入库中的 busy 态会吞掉下一次点击，曾致 4 篇只入 3 篇）。
- `.app-shell` 等待 15s→30s（冷启动 JVM 首次登录链路可超 15s）；跑前建议 curl 预热一次登录。
- 脚本非幂等（假设全新后端），重跑前须重启后端清空 H2。

截图：shots/c0-projects.png、c1-repository.png、c2-knowledge.png、c3-compare.png（均为最终规范轮产物）。
验证配套：后端容器化 `mvn test` 531 例全绿（含 `SpaCsrfBrowserFlowTest`）；前端 `npm test && npm run build` 通过。
