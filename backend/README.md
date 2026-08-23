# Backend

ForgePilot V2 的 Spring Boot 模块化单体，Phase 2–8 的业务实现已全部落地：8 个顶层业务包、
16 张业务表、7 个 Flyway 迁移，`./mvnw -B -ntp verify` 307 个测试全绿。

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

允许的顶层业务包仅为下列八个，各自职责见同名 `package-info.java`：

```text
common       统一错误模型与异常处理
auth         账号、会话、CSRF、鉴权入口
project      项目、成员、角色与项目级 SCM 身份
requirement  需求、验收条件、不可变修订、质量检查与实现建议
scm          GitHub/GitLab Provider、Webhook、PR 快照与需求关联
knowledge    项目知识文档、分块与 pgvector 检索
ai           统一 AI 网关、Prompt 净化与调用审计
review       唯一 Review Engine、Finding 生命周期与人工决策
```

新增顶层包、业务表或运行时依赖必须先补充并批准 `docs/v2/DECISIONS.md` 的决策记录；
ArchUnit 会强制顶层包无环且 `scm` 不依赖 `review`。
