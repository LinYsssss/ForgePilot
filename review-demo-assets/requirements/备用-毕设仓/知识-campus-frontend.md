# Campus Platform Frontend — 项目规范

> 适用范围：`LinYsssss/campus-platform-frontend`
> 用途：ForgePilot 项目知识。审查 Finding 会引用本文条款作为判定依据。

## 1. 技术栈与工程结构

Vue 3.5（`<script setup>` 组合式 API）+ Vite 7，Node `^20.19 || >=22.12`。

| 依赖 | 版本 | 用途 |
|---|---|---|
| `vue-router` | ^5.0 | 路由，`meta.roles` 承载权限 |
| `pinia` | ^3.0 | 状态，`stores/{auth,app}` |
| `element-plus` | ^2.13 | UI 组件与 `ElMessage` 提示 |
| `axios` | ^1.14 | HTTP，统一走 `api/request.js` |
| `echarts` | ^6.0 | 看板与统计图表 |

目录约定：

```
src/api/        每个业务域一个文件，只导出请求函数，不含组件逻辑
src/stores/     Pinia store，auth 管登录态，app 管布局偏好
src/router/     路由表（meta.roles 声明可访问角色）
src/utils/      纯函数工具，permission.js 是角色判定唯一来源
src/layout/     外壳：Header / Sidebar / Breadcrumb / TagsView
src/views/      页面，按业务域分目录：dashboard / education / campus / system / statistics / message / login / register / error
```

**所有 HTTP 请求必须经 `src/api/` 下的函数发出，页面组件里不得直接 `import axios`。**

## 2. 请求层契约

`src/api/request.js` 是唯一的 axios 实例。

- `baseURL` 取 `import.meta.env.VITE_API_BASE`，缺省 `/api`；开发走 vite proxy，生产走 nginx 同域反代。**不得在代码里硬编码后端域名。**
- 超时 15000ms。

后端恒返回 HTTP 200，业务语义在响应体的 `code` 字段（契约见后端项目规范）。响应拦截器据此分两层处理：

1. **业务层**（`response.data.code`）：`200` → 直接返回 `data`；`401/403/400/500` → `ElMessage` 提示后 `Promise.reject`。
2. **传输层**（`error.response.status`）：`401/403/404/500` → 同样提示后 reject。

**两层都要覆盖。** 只处理其中一层的新错误码属于缺陷。

`normalizeResponseData` 会递归把 ISO 时间串规整成 `YYYY-MM-DD HH:mm:ss` 再交给页面。**页面不要重复做时间格式化**，也不要依赖原始 ISO 串。

### 已知缺口

401 分支当前是「提示 → `logoutAction()` → `router.push('/login')`」，**没有保留用户当前所在路由**。重新登录后一律落到默认首页，用户丢失上下文。修复必须同时覆盖上述两层 401 分支，且要防止并发请求同时触发时重复跳转、重复弹提示。

## 3. 权限模型

`src/utils/permission.js` 是角色判定的**唯一来源**，页面和路由都不得自己写 `userType === 2` 之类的判断。

- `userType`：`0=学生`、`1=教师`、`2=管理员`，与后端一致。
- `userTypeToRole(userType)` → `'student' | 'teacher' | 'admin'`。
- `ROLE_DEFAULT_PATH`：admin → `/system/user`，teacher → `/education/course`，student → `/dashboard`。
- 路由通过 `meta.roles: ['admin', ...]` 声明可访问角色；**未设置 `meta.roles` 即视为放行**。
- `hasPermission(userRole, route)` 判单条，`filterRoutes(routes, userRole)` 递归过滤路由树。

**前端过滤只负责不展示，不构成安全边界。** 任何权限相关的最终判定都在后端；前端隐藏入口不能替代后端角色校验。

### 已知缺陷

`filterRoutes` 的函数注释声明「父路由的 children 全部被过滤后，父路由也移除」，但链尾那条

```js
.filter(route => !route.children?.length || route.children.length > 0)
```

的条件**恒为 true**（`children` 为空数组时第一子句成立，非空时第二子句成立），该过滤实际是死代码。结果是：一个自身没有 `meta.roles`、但子路由全部被过滤掉的父节点，仍会留在菜单里，点进去是空目录。

## 4. 登录态

`stores/auth.js`：

- `token` 存 `localStorage`，key 为 `token`，store 初始化时读回。
- `userInfo` / `permissions` / `roles` 登录后由 `fetchUserInfo()` 填充。
- `isLoggedIn` = `!!token`。

**硬规则：**

1. Token 只放 `localStorage` 的 `token` 一处，不得再往 cookie、sessionStorage 或 URL 里复制。
2. **禁止 `console.log` 输出 token、验证码 key、手机号、身份证等敏感值。** 现存的验证码 key 打印属于待清理项，不要照抄这个写法。
3. 登出必须清 token、清 userInfo、清 permissions/roles 三者，只清 token 会留下脏状态。

## 5. 交互基线

- 任何异步操作都必须有**加载态**与**失败态**，失败态要给可恢复动作（重试 / 返回），不得静默失败。
- 列表必须有**空状态**文案，不得只渲染一个空白区域。
- 有副作用的按钮（提交、删除、上传）在请求进行中必须禁用，防重复提交。**但前端禁用不构成幂等保证**，后端仍需自己防重。
- 提示统一用 `ElMessage`，不要混用 `alert` 或自造 toast。
- 破坏性操作（删除、批量操作）必须二次确认。

## 6. 测试与验证

**本仓 `package.json` 中没有测试框架**，`scripts` 只有 `dev` / `build` / `preview`。因此：

- **不要在验收条件里要求「补前端单元测试」** —— 没有 runner，这类要求无法完成。
- 变更的验证方式是：`npm run build` 通过，加上按验收条件逐条人工走查（关键视口 1440 / 768 / 390）。
- 若确实需要引入测试框架，那是一次独立的工程决策，必须单独立需求，不能夹带在业务 PR 里。

## 7. 兼容性

- 后端接口契约由 `campus-platform-backend` 定义，前端不得单方面假设新字段存在。
- 路由 `path` 与 `name` 是外部可见契约（用户书签、`ROLE_DEFAULT_PATH`、`TagsView` 缓存都依赖它），改名属破坏性变更。
- 新增页面必须同时补：路由条目、`meta.title`、`meta.roles`、以及 `src/api/` 下对应的请求函数。
