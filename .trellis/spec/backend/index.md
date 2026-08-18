# Backend Development Guidelines

> RepoSage 后端(`backend/`,Spring Boot 3.5 / Java 17 / PostgreSQL+pgvector / RabbitMQ)的开发规范。
> 每条规则都以仓库内真实代码为锚点;发现规则与代码不符时,先查证代码,再修正规范。

---

## Guidelines Index

| Guide | 内容 |
|-------|------|
| [Directory Structure](./directory-structure.md) | 领域包组织、分层与依赖方向、命名约定 |
| [Frozen Contracts](./frozen-contracts.md) | 跨线冻结契约:ErrorCode / PageResponse / ProjectAuthorization / Flyway 不可变迁移 / REST 与 MQ 载荷 |
| [Database Guidelines](./database-guidelines.md) | Flyway 迁移纪律、事务边界与 `@Transactional` 不自调用 |
| [Error Handling](./error-handling.md) | ErrorCode 词汇表、BusinessException、no-blind-errors、瞬态错误重试映射 |
| [Logging Guidelines](./logging-guidelines.md) | traceId 全链路纪律(HTTP → MDC → MQ)、级别约定、脱敏 |
| [Quality Guidelines](./quality-guidelines.md) | 测试写法与目录、授权矩阵准入、Spring 上下文测试路径规则 |
| [Security Guidelines](./security-guidelines.md) | SPA CSRF 契约、供应链门禁(trivy)与 CVE 修复模式 |
| [Agent Model Contracts](./agent-model-contracts.md) | 模型输出约束的两级防御、共享防御单源、合法姿态显式降级 |
| [Prompt Management](./prompt-management.md) | Prompt 资产治理五规则:宁精勿多 / recall-first / 评测门禁 / 退役 / 禁承诺红线 |

| [Assistant Streaming Contracts](./assistant-streaming-contracts.md) | Requirement 内嵌只读助手：POST SSE、上下文来源、Prompt 信任边界、容量与错误矩阵 |
| [Dashboard Projection Contracts](./dashboard-projection-contracts.md) | workbench/metrics/AI 日志的项目边界、raw JDBC 可移植性和跨层测试契约 |
跨包共享的纪律(契约测试、演示资产口径)见 `.trellis/spec/guides/`。
