# 验证记录：测试顺序依赖定位与修复、前端依赖、文档小账

- 日期：2026-10-17
- 任务：`.trellis/tasks/10-17-cleanup-order-deps-docs`
- 入口：固定容器 `eclipse-temurin:21-jdk` + `./mvnw -B -ntp verify`（见 `docs/v2/DEFENSE-GUIDE.md`）

## 一、测试套件的类执行顺序依赖：根因已定位，测试侧修复

### 根因

污染源不在应用代码，而在测试装配：

1. 应用的 CSRF 是 `CsrfConfigurer::spa`，仓库为 `CookieCsrfTokenRepository`。
   `GET` 不在 `CsrfFilter` 的保护匹配器内，框架本身**不**解析延迟 token；
   响应里之所以出现 `XSRF-TOKEN`，是因为
   `CsrfTokenRequestAttributeHandler.handle()` 调用了 `getParameterName()`，
   顺带把延迟 token 生成并保存了一次。
2. `SecurityMockMvcRequestPostProcessors.csrf()`（各测试类里的 `.with(csrf())`）
   在装配请求时经 `WebTestUtils.setCsrfTokenRepository` 把过滤器链里 `CsrfFilter` 的
   `CsrfTokenRepository` 换成 `TestCsrfTokenRepository`，后者包着一个
   `HttpSessionCsrfTokenRepository`。该替身覆写了 `generateToken`/`saveToken`/`loadToken`，
   **唯独没有覆写 `loadDeferredToken`**，于是 token 落进 session，不再写回响应 cookie。
3. 这个替换作用在**共享的过滤器链**上，并且请求结束后仍留在原处：同一 JVM 中只要有一个类
   用过 `.with(csrf())`，其后所有类（含匿名 `GET /api/auth/me` 的 `XSRF-TOKEN` 下发）
   都改用 session 仓库。谁先谁后由测试类的文件系统顺序决定，因此**仅重命名
   `BatchOneApiTest` 就能换来失败**。

### 证据

| 观测 | 结果 |
|---|---|
| 单跑该类（`-Dtest=CoreApiJourneyTest`） | 7/7 通过 |
| 复现器 `-Dtest='com.forgepilot.review.*Test,CoreApiJourneyTest'`（修复前） | 139 项 / 6 失败，全部为 `GET /api/auth/me must issue the XSRF-TOKEN cookie` |
| 失败请求的响应属性 | 有 `DeferredCsrfToken`、`CsrfToken`、`_csrf`，**没有** `Set-Cookie` |
| 运行期链内容（临时探针，仓库外副本） | `CsrfFilter` 持有 `TestCsrfTokenRepository`；启动期装配的是 `CookieCsrfTokenRepository` |
| 字节码（`spring-security-web` 7.1.0 / `spring-security-test` 7.1.0） | `CookieCsrfTokenRepository` 未覆写 `loadDeferredToken`；`CsrfTokenRepository.loadDeferredToken` 默认返回惰性的 `RepositoryDeferredCsrfToken`；`TestCsrfTokenRepository` 同样未覆写 |

### 修复（只动测试侧）

- `PostgresTestBase.spaCsrf()`：新增共享后处理器，按浏览器真实路径做双提交
  （`XSRF-TOKEN` cookie 与 `X-XSRF-TOKEN` header 回显同一个值）。
- 6 个测试类共 14 处 `.with(csrf())` 改为 `.with(spaCsrf())`：
  `review/FindingLifecycleTest`、`review/ReviewDecisionTest`、`review/ReviewPipelineIntegrationTest`、
  `notification/NotificationChannelTest`、`requirement/RequirementQualityTest`、
  `requirement/ImplementationGuidanceTest`。
- 生产代码未改：`SecurityConfig` 的 SPA 接线本身正确，问题出在测试替身。

### 验收

- 复现器（同一命令、同一顺序）：修复前 139/6 失败 → 修复后 **139/139 全绿**。
- 仓库外副本（类名改为 `CoreApiJourneyTest`，该类仍排在末尾）全量 verify：
  **432 项 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**——顺序无关性成立。
- 仓库全量 `./mvnw -B -ntp verify`：**432 项 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**。

## 二、前端依赖漏洞

- `frontend/package.json`：`vue`、`@vue/compiler-sfc` 3.5.41 → 3.5.44（patch，两者同步）。
- lockfile 刷新：`brace-expansion` 2.1.7、`source-map-js` 1.2.2、`undici` 8.12.2。
- `npm audit`：**0 漏洞**（含 dev 依赖）。
- 四道门（`lint` / `typecheck` / `test -- --run` / `build`）与 `npm ci`：全部通过；
  `test -- --run` 为 **66 项 / 18 个文件**，退出码均为 0（按 9.4 的做法先落日志再查退出码）。

## 三、文档小账

1. `docs/deliverables/TEST-REPORT.html`：§9.1 由「未修复」改为「已修复」，补根因、复现、
   修复与诊断规则；10.4 移除已完成项。
2. `AGENTS.md`：补承重符号链接说明（冻结产物路径是指向 `archive/2026-08/` 的被跟踪符号链接）。
3. `.trellis/workspace/LinYsssss/`：补记 10-16 两轮与本轮。

## 四、部署（2026-10-17）

- 推送后 CI run 39045618266 四个 job 全绿；`docker compose -p fp-demo up --build --detach --wait`
  重建 backend 与 frontend（镜像 `8337b491…` / `6ae8b0cf…`），postgres 容器与数据卷未动，
  Flyway 校验 14 条迁移、无需迁移。
- 部署前备份、镜像 ID 与部署后验证明细见 `deployment-20261017.md`。

## 边界（本轮不做）

- 生产代码语义改动。
- 浏览器人工验收（89 项清单）与线上复测。
- 后端 Maven 依赖的 CVE 扫描与门禁。
