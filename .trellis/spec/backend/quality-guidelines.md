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

### 打开后台调度的测试上下文必须 `@DirtiesContext`

`app.agent.scheduling.enabled=true` 的测试类跑完后,Spring **不会**关掉它的上下文——上下文进缓存复用,
里面的 `@Scheduled` 调度器就一直在后台按原节奏跑。而所有测试类共用同一个库,于是这个"别人家的"调度器
会持续排空 `agent_outbox_event`:后面任何「存一条事件并断言它保持 PENDING」的用例都在跟它抢,
断言读到的是被它标掉的 `SENT`。

- 规则:任何把后台调度/watchdog 打开的测试类,一律加 `@DirtiesContext(classMode = AFTER_CLASS)`,用完即拆。
- 这类缺陷**跟测试执行顺序绑定**,而 surefire 默认 `runOrder=filesystem` 在 Windows 与 Linux 上顺序不同——
  本机全绿、CI 红是典型症状,别当成偶发 flake 重跑掉。复现方式是把顺序固定成 CI 的那一种:
  ```bash
  mvn test -Dtest='<调度类>,<受害类>' -Dsurefire.runOrder=reversealphabetical
  ```
  修完再用同一条命令验证,并跑一次全量确认没有别的类依赖那个被缓存的上下文。
- 泛化:凡是**跨测试类共享的活动物**(调度线程、消息监听器、后台 executor),判据都是「它会不会在本类结束后
  继续动共享状态」——会,就必须随类销毁。

---

## Code Review Checklist

按影响面核对,均有专门规范可引:

- 动了 `ErrorCode` / `PageResponse` / `ProjectAuthorization` / REST 路径字段 / MQ 载荷?→ [frozen-contracts.md](./frozen-contracts.md)
- 动了历史迁移或占用 V22–V25?→ [database-guidelines.md](./database-guidelines.md)
- 新校验规则没同步提示词?摘要消息丢了原因?→ [agent-model-contracts.md](./agent-model-contracts.md) / [error-handling.md](./error-handling.md)
- 新异步边界没带 traceId?→ [logging-guidelines.md](./logging-guidelines.md)
- 跨进程/跨模块数据格式变更没有双向契约测试?→ `.trellis/spec/guides/contract-testing.md`
