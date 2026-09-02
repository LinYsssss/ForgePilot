# Tenant User Center — 项目规范

> 适用范围：`LinYsssss/reposage-demo-tenant-user-center`（私有）
> 用途：ForgePilot 项目知识。审查 Finding 会引用本文条款作为判定依据。
> 来源：整合自仓库内 `docs/tenant-isolation.md`、`docs/auth-policy.md`、`docs/api-contract.md`、`docs/bug-history.md`，并补充当前代码状态。

## 0. 架构

**Python + JavaScript 双语言**，用于验证多语言审查能力。

| 目录 | 语言 | 职责 |
|---|---|---|
| `src/app/` | Python（FastAPI） | 接口、认证、数据访问 |
| `web/` | JavaScript | 管理后台前端片段 |

**多租户共享库**：所有租户数据在同一套表里，靠 `tenant_id` 隔离。**少一个过滤条件就是一次跨租户泄露**——不是「可能」是必然，因为不同租户下的自增主键会重叠。

## 1. 租户隔离

1. **所有表必须含 `tenant_id`**，且为复合索引首列。
2. **所有查询必须带 `tenant_id` 过滤**，包括 `count`、`exists`、`delete`、`update`。
3. **`tenant_id` 必须来自认证上下文，不得来自请求参数**——否则等于让调用方自选租户。
4. 跨租户操作只允许平台管理员执行，且必须走独立的、显式标注的接口。
5. 任何 `JOIN` 都要确认两侧 `tenant_id` 一致，仅靠外键防不住交叉归属。
6. 数据访问层统一经 `TenantScopedRepository`，它强制注入 `tenant_id`。**禁止绕过该层直接拼 SQL。**
7. 缓存键必须包含 `tenant_id`。
8. 单元测试必须包含「B 租户读不到 A 租户数据」的反向用例。

## 2. 导出与报表

批量导出风险最高，一次泄露整表。导出必须：强制 `tenant_id` 过滤；记录审计日志（谁、何时、导出多少行）；**单次上限 10000 行**（`repository.py` 已定义 `MAX_EXPORT_ROWS = 10_000`）。

## 3. 密码

- **必须使用 bcrypt，cost 因子不低于 12。**
- **严禁 MD5、SHA1、SHA256 等快速哈希**——它们为速度设计，正适合暴力破解。
- 严禁自己实现「加盐 + 哈希」，用成熟库。
- 密码最短 12 位，含大小写与数字。

## 4. 令牌

- 有效期不超过 24 小时。
- **校验令牌时必须验证签名与过期时间，只解码不校验等于没有认证。**
- 载荷中不得包含密码、手机号、邮箱等敏感信息。
- 用户改密或禁用后已签发令牌必须失效（依赖 `session_version`）。
- 签名密钥从环境变量读取（`TOKEN_SIGNING_KEY`），缺失时直接启动失败。

## 5. 登录防护

- 同一 IP 每分钟最多 10 次；同一用户名每 5 分钟最多 5 次。
- **用户不存在与密码错误必须返回完全相同的响应，且耗时相近**，否则可用于枚举账号。
- 触发限流返回 429 并带 `Retry-After`。

## 6. 会话

- Cookie 必须 `HttpOnly`；生产必须 `Secure`；`SameSite` 为 `Lax` 或 `Strict`。
- **令牌不得返回给 JavaScript，不得写入 localStorage。**

## 7. 权限

- 「已认证」不等于「已授权」。每个按 ID 操作的接口都必须校验资源归属。
- 角色变更必须写审计日志。

## 8. 接口约定

响应格式：

```json
{ "code": 0, "errorCode": "OK", "message": "success", "traceId": "...", "data": {} }
```

错误时 `code` 非零，`errorCode` 为稳定字符串。**客户端基于 `errorCode` 分支，不解析 `message`。**

分页默认每页 20、上限 100，返回 `{ items, page, size, totalElements, totalPages }`。**排序字段必须走白名单**（`repository.py` 已定义 `SORTABLE_COLUMNS`），禁止把客户端字段名拼进 SQL。

输入校验：字符串限长；用户名字符集 `[A-Za-z0-9._-]`、长度 3~64；邮箱手机号格式校验；数值校验范围禁止负数。

## 9. 输出与渲染

- **用户可控内容（昵称、备注、标签）一律按文本渲染，禁止 `innerHTML`。**
- 手机号、邮箱在列表接口脱敏，仅详情接口对有权者返回完整值。
- 错误响应不得回显 SQL、内部路径、堆栈。

## 10. 密钥

- API Key、数据库密码、令牌签名密钥一律从环境变量读取。
- **禁止出现在前端代码中**——前端产物是公开的。

## 11. 限流

普通接口每 IP 每分钟 120 次；登录见 §5；**导出接口每租户每小时 5 次**。

## 12. 历史事故台账

**每条都已固化成上文规则。新代码重复同类问题视为高危。**

| 编号 | 事故 | 原因 | 固化规则 |
|---|---|---|---|
| INC-2024-09 | 用户列表跨租户泄露 12 万条 | `list_users` 只按 `role` 过滤，漏了 `tenant_id` | §1 |
| INC-2024-12 | 密码哈希被离线破解，两周还原 63% | 密码用 MD5 加固定盐 | §3 |
| INC-2025-03 | 手工构造的令牌通过认证 | 调了 decode 但没传 `verify` 参数 | §4 |
| INC-2025-04 | 租户 A 用户看到租户 B 资料 | 缓存键只用 `user_id`，跨租户重复 | §1.7 |
| INC-2025-07 | 调用方改 `tenant_id` 读到别人数据 | 从 query string 取 `tenant_id` | §1.3 |
| INC-2025-10 | 昵称 XSS，打开列表即被执行 | 管理页用 `innerHTML` 拼昵称 | §9 |
| INC-2026-01 | 导出拉 400 万行，全站不可用 20 分钟 | 导出无行数上限、无审计 | §2 |

## 13. 当前代码状态

**本仓 `main` 上同时存在两类代码，必须分清：**

### 正确的参照实现（改动时以它们为准）

| 文件 | 状态 |
|---|---|
| `src/app/auth.py` | ✅ 规范实现。bcrypt `rounds=12`；`verify_token` 显式传 `verify_signature/verify_exp/require=["exp","iat"]`；签名密钥从 `TOKEN_SIGNING_KEY` 环境变量读取且缺失即抛错 |
| `src/app/repository.py` | ✅ 规范实现。`TenantScopedRepository` 每条 SQL 都带 `tenant_id = %s` 参数绑定；`SORTABLE_COLUMNS` 排序白名单；`MAX_EXPORT_ROWS = 10_000`；`TenantContext` 注释写明「绝不从请求参数构造」 |

### 2026-09-01 并入的缺陷代码（**待修复，不得视为正确实现照抄**）

`src/app/ops_console.py`（运营后台批量管理，`/api/ops` 前缀）：

| 位置 | 问题 |
|---|---|
| `search_users` | `tenant_id`、`keyword`、`sort` 全部 f-string 拼进 SQL —— SQL 注入 + `ORDER BY` 无白名单；且 `tenant_id` 来自请求参数（INC-2025-07） |
| `export_users` | `select *` 拼接 `tenant_id`；无行数上限、无审计、无限流（INC-2026-01） |
| `user_stats` | **完全没有 `tenant_id` 过滤**，全平台聚合（INC-2024-09） |
| `reset_password` | **`hashlib.md5`** 存密码（INC-2024-12）；SQL 拼接；**任何调用方可重置任意用户密码**，无鉴权无租户校验 |
| `_who` | **`jwt.decode(token, options={"verify_signature": False})`** —— 关掉签名校验（INC-2025-03） |
| 整个 router | `/api/ops` 下全部端点**没有任何认证或授权** |

`web/ops-console.js`：

| 位置 | 问题 |
|---|---|
| `OPS_API_KEY` | **硬编码凭据** `'ops-live-8f3a2b91c7d64e05a1f2'` 写在前端源码里（§10） |
| `renderSearchResults` | `innerHTML` 拼接 `nickname`/`username`/`email`/`phone` —— XSS（INC-2025-10）；且列表直接渲染完整手机号邮箱，未脱敏（§9） |
| `exportAll` | `console.log` 输出全部用户明细含手机号邮箱 |
| `searchUsers` | 查询参数未 URL 编码直接拼进 URL |
| `applyUserPreferences` | `for...in` 直接赋值 —— 原型污染 |
| 行内 `onclick` | `onclick="resetPassword(${u.id})"` 引用了未导入的全局函数 |

**这批代码的每一条都能在 §12 的事故台账里找到对应条目**，这不是巧合——它是照着台账反向构造的审查素材。
