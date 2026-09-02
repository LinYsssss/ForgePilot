# Tenant User Center — 需求清单

> 目标仓库：`LinYsssss/reposage-demo-tenant-user-center`（私有，Python + JavaScript）
>
> **使用方式**：每条需求的「标题 / 背景 / 描述」直接粘进 ForgePilot 需求表单，「验收条件」逐条录入。
> 分隔线以下的「改动范围」「预期发现」**不要粘贴**——那是答案，进了系统等于喂给模型。

三条需求都在修 2026-09-01 并入 `main` 的 `feature/ops-console` 那批代码。**每一条缺陷在仓库自己的 `docs/bug-history.md` 里都有对应的历史事故**，审查应当能引用它作为判定依据。

> **仓库现状约束**：`_raw_query` 抛 `NotImplementedError`，是演示用空实现，不连真实数据库。因此验收条件只要求代码结构、SQL 构造方式与校验逻辑正确，不要求真实落库。`src/app/auth.py` 与 `src/app/repository.py` 是**已经符合规范的参照实现**，改动时以它们为准、优先复用，不要另起炉灶。

---

## 需求一：运营后台补齐认证授权与租户来源

**标题**

```
运营后台接口补齐认证授权与租户隔离
```

**背景**

```
src/app/ops_console.py 新增的 /api/ops 路由下有 5 个端点，全部没有任何认证或授权。任何能访问该服务的调用方都可以直接调用，其中包括 reset_password —— 它能重置任意用户的密码。

同时有三处租户隔离问题：

1. search_users 与 export_users 的 tenant_id 来自 query string 参数，而不是认证上下文。调用方把它改成别人的租户号就能读到别人的数据。这正是 INC-2025-07 的成因，docs/tenant-isolation.md 第 2 节第 3 条已明令禁止。
2. user_stats 完全没有 tenant_id 过滤，直接对 app_user 全表 group by role，返回的是全平台统计。这是 INC-2024-09 泄露 12 万条的同类问题。
3. reset_password 按 user_id 直接更新，不校验该用户是否属于调用方租户。

另外 _who 函数用 jwt.decode(token, options={"verify_signature": False}) 解析令牌 —— 关闭了签名校验，等于没有认证。INC-2025-03 的成因与此完全一致，而同仓的 src/app/auth.py 里 verify_token 已经给出了正确写法。
```

**描述**

```
让 /api/ops 下的所有端点都经过认证与授权，并让租户上下文只能来自登录态。

租户信息统一用 repository.py 已定义的 TenantContext 承载，它的注释写明「绝不从请求参数构造」。令牌校验复用 auth.py 的 verify_token，不再自己解析。

跨租户的平台级统计如果确有需要，走独立的、显式标注的平台管理员接口，不与租户内接口混在一起。
```

**验收条件**

```
AC-1  /api/ops 下全部端点都要求有效认证，未认证返回 401。
AC-2  令牌校验复用 auth.py 的 verify_token，校验签名与过期时间；仓库中不再存在 verify_signature 为 False 的调用。
AC-3  运营类端点校验调用方角色，非授权角色返回 403；403 与 401 语义区分清楚。
AC-4  tenant_id 一律取自认证上下文（TenantContext），请求参数中传入的 tenant_id 被忽略；函数签名中不再有从 query 接收的 tenant_id。
AC-5  user_stats 按当前租户过滤；确需全平台统计时另立平台管理员专用端点并显式标注，不复用本端点。
AC-6  reset_password 校验目标用户属于当前租户，跨租户目标返回 403。
AC-7  reset_password 校验调用方有权重置该用户密码，不是「认证即可重置任意人」。
AC-8  重置密码、导出、跨租户统计三类动作写审计日志，含操作人、时间、目标、结果。
AC-9  审计日志中不含密码明文、令牌、完整手机号邮箱。
AC-10 auth.py 与 repository.py 的现有实现不被修改破坏，其既有语义保持不变。
```

---
**改动范围（不要粘贴）**

- `src/app/ops_console.py`
- 可能新增 FastAPI 依赖项（`Depends`）用于注入 `TenantContext`

**预期 ForgePilot 会报出的问题**

只加认证不加授权（AC-1 过了但 AC-3/AC-7 没过）是最可能的漏项。把 `tenant_id` 参数保留成「可选，未传则取登录态」同样不合格——只要还能从请求传入就等于没修，审查应命中 AC-4 与 INC-2025-07。自己写一遍 JWT 校验而不复用 `verify_token` 应命中重复实现。

---

## 需求二：运营后台数据访问收敛到 TenantScopedRepository

**标题**

```
运营后台消除 SQL 注入并改用 bcrypt
```

**背景**

```
ops_console.py 的四个端点全部用 f-string 拼接 SQL：

  search_users：tenant_id、keyword 拼进 where，sort 拼进 order by
  export_users：tenant_id 拼进 where，且是 select *
  reset_password：md5 摘要与 user_id 拼进 update

docs/tenant-isolation.md 第 3 节要求数据访问统一经 TenantScopedRepository，并明令「禁止绕过该层直接拼 SQL」。同仓的 src/app/repository.py 已经实现了这一层：每条 SQL 都用 %s 参数绑定，SORTABLE_COLUMNS 是排序白名单，MAX_EXPORT_ROWS 是导出上限，list_users 里 size 被 min(max(size,1),100) 收窄。ops_console 完全绕过了它。

reset_password 另有一个独立问题：用 hashlib.md5 计算密码摘要。docs/auth-policy.md 第 1 节要求 bcrypt cost≥12 并严禁快速哈希，INC-2024-12 就是 MD5 导致数据库快照泄露后两周内 63% 密码被还原。同仓 auth.py 的 hash_password 已经是正确实现。

export_users 还缺行数上限与审计 —— INC-2026-01 一次导出拉了 400 万行导致全站不可用 20 分钟。
```

**描述**

```
把运营后台的数据访问收敛到 TenantScopedRepository，消除所有 SQL 拼接；密码哈希改用 auth.py 已有的 bcrypt 实现；导出补齐行数上限与审计。

需要新查询能力时，扩展 TenantScopedRepository 而不是在 ops_console 里另拼一套。
```

**验收条件**

```
AC-1  ops_console.py 中不再有任何 f-string 或字符串拼接构造的 SQL，全部走参数绑定。
AC-2  新增的查询能力作为方法加在 TenantScopedRepository 上，ops_console 不直接持有连接对象拼 SQL。
AC-3  排序字段走 SORTABLE_COLUMNS 白名单，白名单外的取值回落到默认排序，不报错泄露字段名，也不拼进 ORDER BY。
AC-4  分页参数受限：每页默认 20、上限 100，页码非负，与 repository.py 现有约束一致。
AC-5  导出单次行数上限使用已定义的 MAX_EXPORT_ROWS，超出则截断并在响应中明确告知，不静默截断。
AC-6  导出写审计日志，记录操作人、时间、导出行数。
AC-7  导出不再 select *，只返回明确列出的字段；手机号、邮箱在导出与列表中脱敏。
AC-8  密码哈希改用 auth.py 的 hash_password（bcrypt cost≥12）；仓库中不再出现 hashlib.md5 或其他快速哈希用于密码。
AC-9  密码长度与字符集校验复用 auth.py 的既有规则，不另写一套。
AC-10 所有查询含 tenant_id 条件，包括 count 与 update。
```

---
**改动范围（不要粘贴）**

- `src/app/ops_console.py`
- `src/app/repository.py`（新增方法）

**预期 ForgePilot 会报出的问题**

用转义或过滤引号来「防注入」而不是参数绑定，应命中 AC-1 与规范 §1.6。把 MD5 换成 SHA256 仍然不合格（同属快速哈希），应命中 AC-8 与 INC-2024-12。给 `sort` 加正则校验而不是用已有的 `SORTABLE_COLUMNS` 枚举，应命中 AC-3。

---

## 需求三：管理后台前端安全整改

**标题**

```
运营后台前端消除 XSS 与凭据泄露
```

**背景**

```
web/ops-console.js 有五个问题：

1. OPS_API_KEY = 'ops-live-8f3a2b91c7d64e05a1f2' 硬编码在前端源码里。docs/api-contract.md 第 5 节明确写着「密钥禁止出现在前端代码中 —— 前端产物是公开的」。
2. renderSearchResults 用 innerHTML 拼接 nickname、username、email、phone。用户昵称是用户可控内容，INC-2025-10 就是有人把昵称改成 <img src=x onerror=...>，管理员打开列表即被执行。
3. 同一处还直接渲染完整手机号与邮箱，未脱敏，违反 api-contract 第 4 节「列表接口脱敏」。
4. exportAll 用 console.log 输出全部用户明细，含手机号邮箱。
5. applyUserPreferences 用 for...in 遍历后直接赋值到目标对象，可被 __proto__ 之类的键污染原型。

另外 searchUsers 把 keyword 与 sort 未编码直接拼进 URL；renderSearchResults 生成的行内 onclick="resetPassword(...)" 引用了一个模块里并不存在、也未导入的全局函数。
```

**描述**

```
按 docs/api-contract.md 第 4、5 节整改运营后台前端：用户可控内容一律按文本渲染，凭据不进前端产物，敏感字段脱敏，对象合并不污染原型。
```

**验收条件**

```
AC-1  仓库中不再有硬编码的 API Key、令牌或密码；调用凭据由后端会话承载，前端不持有。
AC-2  用户可控内容（nickname、username、email、phone、备注、标签）全部按文本渲染，不再使用 innerHTML 拼接。
AC-3  列表中手机号与邮箱脱敏显示，完整值仅在详情接口对有权者返回。
AC-4  console 中不再输出用户明细、手机号、邮箱或令牌。
AC-5  对象合并不写入继承自原型的键，__proto__、constructor、prototype 三个键名被显式拒绝。
AC-6  URL 查询参数经编码后拼接，不直接插入原始字符串。
AC-7  操作按钮的事件绑定改为通过 JS 注册，不使用引用全局函数的行内 onclick。
AC-8  改动不破坏现有的搜索、导出、渲染三个功能的调用契约。
```

---
**改动范围（不要粘贴）**

- `web/ops-console.js`

**预期 ForgePilot 会报出的问题**

把 `innerHTML` 换成手写的转义函数而不是 `textContent`／`createElement`，审查通常会指出转义函数容易漏（属性上下文、URL 上下文）。只在 `applyUserPreferences` 里加 `hasOwnProperty` 判断但仍允许 `__proto__` 作为自有属性写入，应命中 AC-5。把 API Key 从常量挪到环境变量再打包进前端，**仍然是泄露**——前端产物是公开的，应命中 AC-1。
