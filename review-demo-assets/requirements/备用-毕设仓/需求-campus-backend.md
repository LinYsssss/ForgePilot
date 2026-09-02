# Campus Backend Demo — 需求清单

> 目标项目：ForgePilot「Campus Backend Demo」（项目 1）
> 目标仓库：`LinYsssss/campus-platform-backend`
>
> **使用方式**：每条需求的「标题 / 背景 / 描述」直接粘进 ForgePilot 需求表单，「验收条件」逐条录入。
> 分隔线以下的「改动范围」「预期发现」**不要粘贴** —— 那是给你自己核对审查质量用的。

三条需求都对应仓库里**当前真实存在的缺口**，不是编出来的场景。建议按顺序做：REQ-1 安全类最出效果，REQ-2 逻辑最刁钻，REQ-3 面最广。

---

## 需求一：公告详情越权访问与定向可见性

**标题**

```
公告详情接口补齐可见性校验
```

**背景**

```
GET /svc/notice/{id} 目前直接 noticeService.getById(id) 并返回原始 CampusNotice 实体，没有任何可见性判断。分页接口 GET /svc/notice/page 对非管理员追加了 status = 1 过滤，但详情接口没有对应约束，学生按 ID 直接请求即可读到 status = 0 的未发布草稿。

CampusNoticeController 里已经存在私有方法 canCurrentUserView(notice)，完整实现了「管理员全见 / 仅已发布可见 / noticeType 定向到角色或班级」的规则，但它在整个文件中没有任何调用点，是死代码。

分页接口同样只过滤了 status，没有应用 noticeType、targetRole、targetClass 的定向规则，因此定向公告也会出现在不相关用户的列表里。
```

**描述**

```
让公告的读取路径统一走既有的可见性规则，消除按 ID 直接读取的越权，并让列表与详情的可见范围一致。

规则以 canCurrentUserView 的现有实现为准：管理员可见全部；非管理员仅可见 status = 1；noticeType 为 0 或空表示全体可见，为 1 表示仅 targetRole 角色可见，为 2 表示仅 targetClass 班级可见。

详情接口同时不再返回原始实体，改为返回与列表一致的视图对象，避免把内部字段直接暴露给前端。
```

**验收条件**

```
AC-1  GET /svc/notice/{id} 在返回前调用 canCurrentUserView 判定，该方法不再是死代码。
AC-2  非管理员请求一条 status = 0 的公告详情，返回 403 且响应体不包含公告标题与正文；不得返回 200 空对象，也不得复用「公告不存在」的提示掩盖越权。
AC-3  公告不存在返回 404 语义的错误码，与上一条的 403 明确区分。
AC-4  noticeType = 1 的公告，仅 targetRole 匹配的角色能取到详情；noticeType = 2 的公告，仅 targetClass 匹配的班级能取到详情。
AC-5  GET /svc/notice/page 对非管理员应用与详情相同的定向规则，列表与详情的可见集合一致。
AC-6  详情接口返回视图对象而非 CampusNotice 实体，字段集合与列表 NoticeVO 保持一致。
AC-7  CampusNoticeControllerTest 补充覆盖：未发布公告越权、定向角色命中与未命中、公告不存在三种情形。
AC-8  管理员的既有行为不变，原有通过的测试全部仍然通过。
```

---
**改动范围（不要粘贴）**

- `campus-server/src/main/java/com/campus/system/modules/svc/controller/CampusNoticeController.java`
- `campus-server/src/test/java/com/campus/system/modules/svc/controller/CampusNoticeControllerTest.java`

**预期 ForgePilot 会报出的问题**

如果你故意只改详情、不改列表提 PR，审查应命中 AC-5 未覆盖。如果你返回 200 空对象而不是 403，应命中项目知识 §4「不得因为查不到就返回 200 空结果」。如果你新写一套可见性判断而不复用 `canCurrentUserView`，应命中重复实现。

---

## 需求二：公告更新操作补齐审计，堵住绕过发布接口的审计空洞

**标题**

```
公告更新接口补齐操作审计
```

**背景**

```
CampusNoticeController 的四个写操作里，add（新增）、publish（发布）、delete（删除）都标注了 @LogRecord，唯独 update（PUT /svc/notice）没有。

这不只是少一条日志。update 方法内部有一段逻辑：当传入 status 为 1 且库中原值为 0 时，自动补 publishTime —— 也就是说 update 可以完成与 publish 完全相同的状态变更。publish 有 @LogRecord(module = "公告管理", type = "发布") 留痕，而走 update 达成同样效果时不留任何痕迹。审计因此可被绕过。

同时这两处「状态改为已发布就补发布时间」的逻辑在 update 和 publish 里各写了一遍。
```

**描述**

```
给 update 补齐操作审计，并消除「同一状态变更存在有痕与无痕两条路径」的问题。

发布语义收敛到单一入口：状态由未发布变为已发布这件事，只允许通过一条代码路径完成，两个接口都复用它，审计随之统一。
```

**验收条件**

```
AC-1  PUT /svc/notice 标注 @LogRecord，module 与其余三个写操作一致为「公告管理」，type 为「修改」。
AC-2  通过 update 把 status 由 0 改为 1 时，产生的审计记录能反映出发生了发布这一事实，不与普通字段修改混为一谈。
AC-3  「状态由未发布变为已发布则写入 publishTime」的逻辑只保留一处实现，update 与 publish 共用，两处不得各写一份。
AC-4  publish 接口的现有行为、路径与审计 type 均不变。
AC-5  已经是 status = 1 的公告再次经 update 提交，publishTime 不被刷新。
AC-6  审计记录中不含公告正文全文，只记录必要的操作标识。
AC-7  CampusNoticeControllerTest 覆盖：经 update 完成发布、重复发布不刷新时间两种情形。
```

---
**改动范围（不要粘贴）**

- `campus-server/src/main/java/com/campus/system/modules/svc/controller/CampusNoticeController.java`
- 可能涉及 `modules/svc/service/impl/CampusNoticeServiceImpl.java`（若把发布逻辑下沉到 Service）
- `campus-server/src/test/java/com/campus/system/modules/svc/controller/CampusNoticeControllerTest.java`

**预期 ForgePilot 会报出的问题**

只加注解、不合并重复逻辑的 PR，应命中 AC-3。把逻辑下沉到 Service 却让 `@LogRecord` 失效（切面只切 `@annotation`，注解必须留在被 Spring 代理调用的方法上）是这条需求最容易踩的坑，审查应该指出。

---

## 需求三：统一响应契约补齐 traceId

**标题**

```
统一响应体补齐 traceId
```

**背景**

```
campus-common 的 Result<T> 只有 code、msg、data 三个字段，没有请求追踪标识。一次失败请求在前端只能看到一句文案，要定位到后端日志只能靠时间戳翻查，跨前后端排障成本很高。

GlobalExceptionHandler 的兜底分支返回固定文案「系统繁忙请求异常，请稍后再试或联系管理员」，异常细节只进 log.error —— 这个行为是正确的，不要为了方便排障就把 e.getMessage() 放进响应体。正确做法是给出一个可以拿去查日志的标识。
```

**描述**

```
在统一响应体中增加 traceId，并保证同一次请求的响应 traceId 与该请求所有日志行中的标识一致，使得用户报障时提供 traceId 即可定位到完整日志。

Result 的 code、msg、data 三个字段的名称与语义保持不变，只做新增，前端现有的按 code 分支处理不受影响。
```

**验收条件**

```
AC-1  Result<T> 增加 traceId 字段，code、msg、data 三个字段的名称、类型与语义均不变。
AC-2  成功响应与失败响应都携带 traceId，不存在只有报错才有的情况。
AC-3  同一次 HTTP 请求内，响应体的 traceId 与该请求产生的所有日志行中的追踪标识相同。
AC-4  traceId 的生成与注入是统一的，不要求各 Controller 自行赋值；任何新增接口无需改动即可携带。
AC-5  GlobalExceptionHandler 兜底分支的响应体仍不包含异常原文、堆栈、SQL 或内部路径，只增加 traceId。
AC-6  并发请求之间 traceId 不串用，异步执行的操作日志沿用发起请求的 traceId 或明确标注为异步。
AC-7  OpenApiDocumentationTest 通过，新增字段在接口文档中有描述。
AC-8  前端无需改动即可继续正常工作，既有测试全部通过。
```

---
**改动范围（不要粘贴）**

- `campus-common/src/main/java/com/campus/system/common/api/Result.java`
- `campus-common/src/main/java/com/campus/system/common/exception/GlobalExceptionHandler.java`
- 新增过滤器或拦截器（`campus-server/src/main/java/com/campus/system/filter/` 目录已存在但为空）
- 日志格式配置（MDC pattern）

**预期 ForgePilot 会报出的问题**

在每个 Controller 里手动 set traceId 的写法应命中 AC-4。用成员变量或静态变量存 traceId（线程不安全）应命中 AC-6。把 `e.getMessage()` 一并塞进响应体应命中 AC-5 与项目知识 §3。
