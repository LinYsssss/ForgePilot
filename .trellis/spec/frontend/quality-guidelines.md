# Quality Guidelines

> Code quality standards for frontend development.

---

## Forbidden Patterns

### 依赖源纪律:lockfile 只允许官方 registry(r3/F-06 实证)

**What**:`package-lock.json` 的全部 `"resolved"` URL 必须指向 `https://registry.npmjs.org`;禁止镜像源(npmmirror 等)进入 lockfile。CI 有源门禁(`ci.yml` 的 lockfile 源检查步骤,只匹配 `"resolved"` 行,不误伤 `funding` 元数据 URL)。

**Why**:镜像源 lockfile 在 npm≥12 直接拒装(EALLOWREMOTE),且供应链不可审计。审计 F-06 时 94% 条目指向 npmmirror,即为此病。

**Gotcha — clean-room 重建**:仅删 `package-lock.json` 后 `npm install --registry=https://registry.npmjs.org/` 是**不够**的:npm 会复用 `node_modules/.package-lock.json` 里的旧镜像元数据,并丢失平台变体包。必须连 `node_modules` 一起删再装(容器内执行天然干净)。重建后三项验证:
1. `grep '"resolved"' package-lock.json | grep -vc 'registry.npmjs.org'` → 0
2. `package.json` 的 `overrides`(如 nanoid ≥3.3.17 安全钉版)仍生效
3. 容器内 `npm ci && npm test && npm run build` 全绿

**新增可疑包核对法**:对 lockfile 新出现的包,取官方 registry 元数据的 `dist.integrity` 与 lockfile integrity 哈希逐字节比对(r3 曾以此排除 `@napi-rs/lzma-linux-x64-gnu` 的投毒嫌疑——rollup 4.62.4 官方声明的 optionalDependency,哈希一致)。

### 其余禁止项(细则见所引规范)

- 会话数据入 Web Storage(HttpOnly cookie 之外只有 useTheme 允许,smoke 测试强制)、composable import `router.js`、每调用点各自 toast 401 —— 均见 [state-management.md](./state-management.md) 硬规则。
- 相对导入省略 `.js`/`.vue` 扩展名 —— node ESM(测试)下加载失败,见 [directory-structure.md](./directory-structure.md)。

---

## Required Patterns

### API 边界容错与结构化错误

- 列表数据一律过 `api/page.js` 的 `unwrapPage`:同时接受裸数组与冻结分页信封 `{items, page, size, totalElements, totalPages}`,异常形状返回 `[]` 不炸渲染。新列表端点接入时不得绕过它自造解构。
- 错误分支按 `api/apiError.js` 的 `ApiError` 结构化字段(`status`/`code`/`traceId`)判断,**不解析中文提示串**;`traceId` 透传自后端 `X-Trace-Id`,报障时直接引用。
- 401 在 `api/client.js` 里于 JSON 解析**之前**拦截(401 响应体为空),经 `setUnauthorizedHandler` 单点漏斗登出——新请求路径必须走 `api()` 包装,不裸用 `fetch`。

---

## Testing Requirements

- 入口:`npm test` = `node --test tests/*.test.mjs`(package.json,node 内置 runner,零测试框架依赖);Node 版本钉 `>=22 <23` 且由 smoke 测试断言 engines 字段。
- 两层测试,各司其职:
  - `tests/smoke.test.mjs` — 配置与源码断言(vite 配置、index.html 入口、对源码文件做正则断言锁关键行为),外加对抽离出的纯 JS 决策逻辑直接单测(`canApprovePatch`、`unwrapPage`、`ApiError`)。
  - `tests/composables.test.mjs` — 行为测试:直接驱动单例 composable,不渲染组件。**桩(fetch 按 URL 路由、document.cookie、EventSource)必须在 import 业务模块之前就位**——composable 是模块级单例,import 即执行(文件头注释即此规则)。Agent SSE 生命周期由它钉死,改生命周期必须连测试一起改。
- 不引组件渲染测试栈(jsdom/vue-test-utils);需要测的逻辑按 [component-guidelines.md](./component-guidelines.md) 抽成纯 JS。

---

## Code Review Checklist

- lockfile diff 里出现非 `registry.npmjs.org` 的 `"resolved"`?→ 拒。
- 新增依赖过了 `npm audit`(high 以上零容忍)与 CI supply-chain 门禁(trivy fs 扫 npm manifest,见 `.trellis/spec/backend/security-guidelines.md`)?
- 后端字段/路径变更是否两侧同批(`.trellis/spec/backend/frozen-contracts.md` 第 6 条)?
