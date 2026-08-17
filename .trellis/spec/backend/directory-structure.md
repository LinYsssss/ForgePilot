# Directory Structure

> 后端代码组织方式,提炼自 `backend/src/main/java/com/example/codereview/` 的现状(348 个类)。

---

## 布局:按业务领域分包,领域包内不再分技术层目录

```text
com.example.codereview/
├── common/            # 全局共享,不依赖任何领域包
│   ├── api/           #   ApiResponse / ErrorCode / PageResponse(冻结契约,见 frozen-contracts.md)
│   ├── exception/     #   BusinessException / GlobalExceptionHandler
│   ├── security/      #   CurrentUser / ProjectAuthorization
│   └── web/           #   TraceIdFilter / RateLimitFilter / ClientIpResolver / SecurityAuditFilter
├── project/ review/ repo/ pullrequest/ knowledge/ finding/ patch/ feedback/ report/
│                      # 业务领域包:Controller+Service+Repository+Entity+Dtos 同包平铺
├── agent/             # Agent 执行闭环,按机制再分子包:
│   ├── api/ run/ queue/ outbox/    # 对外接口、运行状态、MQ 步骤调度、事务性 outbox
│   ├── orchestration/steps/       # 每个 AgentRunStatus 一个 <State>StepExecutor
│   ├── model/ plan/ prompt/ tool/  # 模型调用与校验、计划校验、提示词组装、只读工具
│   └── budget/ error/ observability/ compat/
├── ai/ rag/ context/ language/     # AI 客户端(langchain4j)、检索、上下文、语言插件(java/javascript/python)
├── scm/ webhook/      # GitHub/GitLab 接入、webhook 验签与 Agent Run 触发
├── git/ sandbox/      # git CLI 边界、SandboxJob 同构镜像(与 sandbox-runner 对偶)
├── auth/ config/ mq/ notify/ evaluation/
└── CodereviewApplication.java
```

## 规则

- **领域包平铺,不建 controller/service/repository 顶层目录。** 新业务功能 = 新领域包或并入既有领域包,包内放 `XxxController` / `XxxService` / `XxxRepository` / `XxxEntity` / `XxxDtos`。范例:`project/` 五件套(`ProjectController.java` 等)。
- **DTO 是聚合类里的嵌套 record。** 请求/响应体定义在 `XxxDtos` 内(`ProjectDtos.CreateProjectRequest`),不散落为独立文件。
- **依赖方向:controller → service → repository;`common/` 不得反向依赖领域包。** Controller 只做参数绑定 + `currentUserProvider.getRequired()` + 委托 service,不写业务逻辑(`ProjectController` 每个方法一行委托)。
- **Agent 步骤执行器一态一类。** 新增运行状态 = `agent/orchestration/steps/<State>StepExecutor implements AgentStepExecutor`,`state()` 返回对应 `AgentRunStatus`;同时遵守 [agent-model-contracts.md](./agent-model-contracts.md) 的提示词/校验两级防御。
- **配置按所有权拆文件。** `application.yml` 以非 optional 方式 import `config/app-agent.yml`(Agent/AI 线)与 `config/app-boundary.yml`(安全与外部边界线,文件头声明"Track A 不得修改本文件")。新 `app.*` 键加进所属文件,缺失文件必须启动失败,不用 `optional:`(见 `application.yml` 的 `spring.config.import` 注释)。
- **测试镜像主包结构。** `backend/src/test/java/com/example/codereview/<同名包>/`,类名 `XxxTest`;跨领域的安全矩阵测试放 `common/security/`(`ObjectLevelAuthorizationMatrixTest`)。
