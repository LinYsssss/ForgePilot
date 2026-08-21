# Backend

Phase 1 已进入执行，当前目录是单个 Spring Boot 模块化单体底座。

本地入口是 `./mvnw`；它使用仓库锁定的 Maven 3.9.16，不要求全局 Maven。分发包 URL 与
sha256 校验值只在 `.mvn/wrapper/maven-wrapper.properties` 中定义。需要 Java 21
和 Docker 才能完成完整验证：

```bash
./mvnw -B -ntp verify
```

宿主机没有 JDK 时，用 Java 21 容器执行同一个仓库内 wrapper（Testcontainers 通过宿主
docker socket 启动 PostgreSQL，因此需要 `--network host` 与 socket 挂载）：

```bash
docker run --rm --network host \
  -v "$PWD:/workspace" \
  -v "$HOME/.m2:/root/.m2" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

数据源凭据只能由环境变量注入：`FORGEPILOT_DB_PASSWORD` 没有兜底默认值，缺失时应用
启动失败而不是使用弱口令。

数据库测试故意使用真实 `pgvector/pgvector:0.8.6-pg15-bookworm` Testcontainers，不能以
H2、跳过测试或 Docker 条件分支替代。

允许的顶层业务包仅为：

```text
common
auth
project
requirement
scm
knowledge
ai
review
```

Phase 1 只建工程底座，不生成业务实体、业务表或空分层目录；登录、项目、需求、知识、SCM 和 Review 从 Phase 2 起按授权纵向实现。
