# Quality Guidelines

> Code quality standards for backend development.

---

## Testing Requirements

### 目录与写法

- 测试镜像主包结构(`backend/src/test/java/com/example/codereview/<同包>/XxxTest.java`),JUnit 5 + AssertJ + Mockito;Web 层用 MockMvc 驱动真实请求序列(范例:`SpaCsrfBrowserFlowTest` 按浏览器真实时序断言,而非仅用 `csrf()` post-processor)。
- 本机无 Java/Maven 时容器化执行(实测口径,见 `scripts/verify-local.sh` 与 CI 同源):

```bash
docker run --rm -v "$PWD":/ws -v reposage-m2:/root/.m2 \
  -w /ws/backend maven:3.9-eclipse-temurin-17 mvn -s .mvn/settings.xml test
```

### 新增带 id 端点必须进授权矩阵(冻结准入规则)

任何路径带资源 id 的新端点,必须在 `common/security/ObjectLevelAuthorizationMatrixTest` 增加用例:
用第二个账号真实请求第一个账号的资源。矩阵的两条铁律(类头 Javadoc):**陌生人永不 2xx**(403/404 均可,不许泄露存在性)、**匿名恒 401**。"controller 收了 CurrentUser"不等于做了对象级校验,必须演示而非声明。

### Spring context tests must not write outside the workspace or tmpdir

CI runners are **non-root**; local Maven containers run as root. Any bean that
creates directories at startup (e.g. `Files.createDirectories` in a constructor)
will pass locally and fail in CI with `AccessDeniedException` if its configured
path defaults to an absolute system location (the F-02 incident: default
`/app/archives` kept `main` red for 12 days).

Rule: every `@SpringBootTest` must override path-like properties to a tmpdir,
following the existing inline-properties style:

```java
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.sandbox.signing-secret=test-signing-secret",
        // CI runner 非 root,默认 /app/archives 会因 AccessDeniedException 拉不起上下文(F-02)
        "app.sandbox.archive-root=${java.io.tmpdir}/reposage-test-archives"
})
```

`${java.io.tmpdir}` is resolved by the Spring `Environment` (systemProperties
source), so it works in inline test properties. When adding a new configurable
path, add the override to the affected context tests in the same change.

### 测试属性的三层优先级(surefire 全局回落)

`backend/pom.xml` 的 surefire `systemPropertyVariables` 为存量测试统一回落两项部署默认值:
`app.security.csrf.enabled=false`(存量测试不带 token)、`app.ratelimit.login-limit=1000`
(多测试类共享缓存上下文时不互相耗光限流预算)。优先级:**内联测试属性 > surefire 系统属性 > 配置文件**——
要验证"开启后"的行为,在测试类用内联属性重新打开(`CsrfProtectionTest` 样式),不要改全局回落。

---

## Code Review Checklist

按影响面核对,均有专门规范可引:

- 动了 `ErrorCode` / `PageResponse` / `ProjectAuthorization` / REST 路径字段 / MQ 载荷?→ [frozen-contracts.md](./frozen-contracts.md)
- 动了历史迁移或占用 V22–V25?→ [database-guidelines.md](./database-guidelines.md)
- 新校验规则没同步提示词?摘要消息丢了原因?→ [agent-model-contracts.md](./agent-model-contracts.md) / [error-handling.md](./error-handling.md)
- 新异步边界没带 traceId?→ [logging-guidelines.md](./logging-guidelines.md)
- 跨进程/跨模块数据格式变更没有双向契约测试?→ `.trellis/spec/guides/contract-testing.md`
