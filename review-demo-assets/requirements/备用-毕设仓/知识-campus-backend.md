# Campus Platform Backend — 项目规范

> 适用范围：`LinYsssss/campus-platform-backend`
> 用途：ForgePilot 项目知识。审查 Finding 会引用本文条款作为判定依据。

## 1. 工程结构

Maven 多模块，Java 17，Spring Boot 3.x（`jakarta.*` 命名空间）。

| 模块 | 职责 |
|---|---|
| `campus-common` | 跨模块契约：`api/`（`Result`、`PageResult`）、`entity/BaseEntity`、`enums/`、`exception/`（`BusinessException`、`GlobalExceptionHandler`） |
| `campus-server` | 应用主体：`config/`、`annotation/`、`modules/{auth,edu,svc,sys}` |

`campus-server/src/main/java/com/campus/system/modules/` 下四个业务域：

- `auth` —— 登录、注册、验证码、用户信息（`AuthController` / `AuthServiceImpl`）
- `edu` —— 教务：课程、选课、成绩、考勤、请假、评教、课表、资料
- `svc` —— 校园服务：通知、图书、一卡通、宿舍、报修、看板
- `sys` —— 系统管理：用户、角色、菜单、字典、日志

**新增业务代码必须落到上述某一个域内，不得在 `campus-common` 里放业务逻辑。** `campus-common` 只承载被两个以上模块共享的契约。

技术栈：Sa-Token（认证鉴权）、MyBatis-Plus（持久层）、JetCache（缓存）、Lombok、springdoc-openapi（Swagger）、Hutool。

## 2. 统一响应契约

所有 Controller 返回 `Result<T>`：

```java
Result<T> { Integer code; String msg; T data; }
```

- 成功一律 `code = 200`，`msg = "操作成功"`，走 `Result.success()` / `Result.success(data)`。
- 失败走 `Result.error(code, msg)`。`Result.error(msg)` 的单参重载默认 500，**新代码不要用它**，错误码必须显式。
- 分页一律返回 `PageResult<T> { total, list, pageNum, pageSize }`，不得自造分页结构，不得直接返回 `List` 冒充分页。

**HTTP 状态码恒为 200，业务语义全部落在 `code` 字段上。** 这是既成契约，前端 `request.js` 的响应拦截器按 `code` 分支处理，改动它会同时打断前端。

### 已知缺口

`Result` **没有 traceId 字段**。跨前后端定位一次失败请求目前只能靠时间戳翻日志。补齐时必须保持 `code/msg/data` 三个字段的名字与语义不变，只做新增。

## 3. 异常处理

`GlobalExceptionHandler`（`@RestControllerAdvice`）是唯一的异常出口，拦截优先级：

```
Sa-Token 鉴权异常 → 参数校验异常 → 业务异常 → 兜底异常
```

| 异常 | 返回 |
|---|---|
| `NotLoginException` | 401「Token已失效或未提供，请重新登录」 |
| `NotPermissionException` | 403「当前账号权限不足，拒绝访问」 |
| `NotRoleException` | 403「当前账号角色不足，拒绝访问」 |
| `BusinessException` | 透传 `e.getCode()` 与 `e.getMessage()` |
| `MethodArgumentNotValidException` / `BindException` | 400 + 首个字段的 `getDefaultMessage()` |
| `Exception`（兜底） | 500「系统繁忙请求异常，请稍后再试或联系管理员」 |

**硬规则：**

1. **兜底分支绝不回显异常原文。** `e.getMessage()`、堆栈、SQL、内部路径、完整请求体一律不得进入响应体。异常细节只进 `log.error`。
2. 业务失败抛 `BusinessException`，**不要在 Controller 里 `try/catch` 后自己拼 `Result.error`** —— 那会绕开统一出口，也绕开日志。
3. 不得新增第二个 `@RestControllerAdvice`。

## 4. 认证与授权

Sa-Token，`StpUtil.isLogin()` / `StpUtil.getLoginIdAsLong()` 取当前登录人。角色由 `StpInterfaceImpl` 提供。

- **「已登录」不等于「已授权」。** 任何按资源 ID 操作的接口，必须校验资源归属于当前用户，或调用方具备管理角色。仅有登录校验的按 ID 接口视为越权缺陷。
- 用户类型 `userType`：`0=学生`、`1=教师`、`2=管理员`（与前端 `utils/permission.js` 一致，不得各自定义）。
- 越权返回 403，资源不存在返回 404。**不得因为"查不到"就返回 200 空结果** —— 那会把越权掩盖成正常响应。

## 5. 操作审计

审计走既有切面，**不要另写一套**：

- `@LogRecord(module = "...", type = "...")` 标注在 Controller 方法上；
- `LogRecordAspect` 环绕通知捕获操作人、入参、耗时；
- `AsyncLogService` 异步落盘。

操作人通过 `StpUtil.getLoginIdAsLong()` 拿 ID 后**查用户表取真实用户名**，不要直接把 loginId 当用户名写进日志——这是已经修正过的问题，不要回退。

**需要审计的动作**：增、删、改、导入、导出，以及任何管理端强制操作。纯查询不写审计。

审计日志中不得包含密码、Token、完整手机号等敏感字段明文。

## 6. 测试

`campus-server/src/test/java/` 下有 19 个测试文件，形态是 Spring Boot 切片/MockMvc 控制器测试（`CampusNoticeControllerTest`、`EduTimetableControllerTest`、`OpenApiDocumentationTest` 等）。

- **新增或修改 Controller 行为，必须同步该 Controller 的测试。** 契约变了而测试没动，属于缺陷。
- `OpenApiDocumentationTest` 校验接口文档，新增接口需补 `@Operation` / `@Schema` 注解，否则该测试会挂。
- 另有独立仓 `campus-platform-tests`（Python + pytest）做黑盒 API 回归，覆盖 RBAC、安全基线、异常输入、性能基线。它跑在已部署环境上，不在本仓 CI 内。

## 7. 兼容性

本仓已有前端在线消费（`campus-platform-frontend`）。

- 接口路径、`Result.code` 取值、字段名的**任何变更都属于破坏性变更**，必须在 PR 描述里显式说明并给出前端配套改动。
- 新增字段可以，删改既有字段不行。
- 分页参数命名统一 `pageNum` / `pageSize`，不得引入 `page` / `size` 等别名。
