# r6 阶段 1+2 证据档案(design tokens 落地 + 依赖引入)

> 执行环境:宿主机无 node,全部命令走 `docker run --rm -v /root/reposage/frontend:/ws -w /ws node:22-alpine sh -lc '<cmd>'`(node v22.23.1,满足 engines `>=22 <23`)。

## 改动文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/tokens.css` | 新建,唯一 tokens 文件(三段结构,见下) |
| `frontend/src/main.js` | 在 `./styles.css` 之前 import `./tokens.css` |
| `frontend/vite.config.js` | 新增 AutoImport/Components 两插件(`ElementPlusResolver`,`dts:false`);`build.outDir='dist'`/`emptyOutDir=true` 原样保留(smoke 测试断言) |
| `frontend/package.json` | dependencies + `element-plus@^2.14.4`、`@element-plus/icons-vue@^2.3.2`;devDependencies + `unplugin-vue-components@^32.1.0`、`unplugin-auto-import@^21.1.0`;engines/scripts/overrides(nanoid)未动 |
| `frontend/package-lock.json` | 随安装更新,全部官方源 |
| `.trellis/tasks/08-03-r6-frontend-element-plus/bundle-size.md` | 体积对比档案(口径说明含 README 历史基线 202.52 kB 的口径澄清) |

`frontend/src/styles.css`、`views/`、`components/`、后端、CI、README 零改动(`git diff --stat frontend/src/views frontend/src/components` 为空)。

## tokens.css 结构(两句说明)

第一段 `:root` 挂 `--rs-*` 原生 tokens 全表(teal 品牌双值、slate 灰阶、风险四级×4 值、CRITICAL/INFO 两端扩展、diff 六色变量、圆角/字号阶/间距 `--sp-1..16` 同名沿用/阴影三级/z-index 五层/motion 三时长二缓动/双字体栈),第二段在 `html:root` 上把 Element 变量(primary 全 ramp、四语义色 ramp、圆角/字体/文本/描边/填充/背景/阴影)映射到 rs 值。第三段为产品语义工具类(`.risk-*`/`.sev-*`/`.st-*` 全家只写颜色+底+描边色、RUNNING/PENDING 自带 `rs-pulse` keyframes 与 reduced-motion 分支、`.mono`),整段包在 `@layer rs-tokens` 里降优先级——迁移期由未分层的 styles.css 同名类继续接管形状与颜色,styles.css 删除后本段自然生效。

## 对派单的三处技术偏离/补充(色值零自创,全部可由定稿文档推出)

1. **Element 覆盖块用 `html:root` 而非 `:root`**。原因:Element theme-chalk 的默认值同样声明在 `:root`,而按需注入的组件样式在 bundle 中的位置随迁移进度浮动(实测本轮探针构建中 Element 默认块在产物**字节 0** 处、tokens.css 覆盖块在 35694 处;views 一旦改为懒加载,Element CSS 还会进 async chunk 更晚加载)。`html:root` 特异性 (0,1,1) > `:root` (0,1,0),使覆盖**与 CSS 打包/加载顺序无关**;变量仍落在 html 元素,继承语义不变。
2. **四语义色补了 dark-2**(派单只列 light-3/5/7/8/9)。定稿文档写"四语义同法(全 ramp)",且 dark-2=向黑 20% 公式已被 primary 字面值反向验证(`#0F766E`×0.8=`#0C5E58` 与文档逐字节一致);不补则 success/warning/danger 按钮 hover 会漏出 Element 默认绿/橙/红。值:success `#12823B`、warning `#AE5F05`、danger `#B01E1E`、info `#505D6F`。
3. **补了 `--el-color-error` 族(值=danger 族)**。Element 官方默认主题 error≡danger,表单校验/ElMessage 走 error 变量,不补会漏默认红 `#f56c6c`。另:第一段末尾带 `color-scheme: light`(迁移期被后加载的 styles.css `dark` 覆盖,零现值影响;styles.css 删除后自动生效)。

派生阶舍入约定:四舍五入(round half up)。文档 primary 亮阶字面值与公式计算差 ±1(文档手算舍入不一致,如 light-3 B 通道 153.5 取 153),**primary 按文档字面值照抄**,四语义亮阶按公式统一四舍五入——已互证无内部矛盾(success light-9 算得 `#E8F6ED` 恰等于文档风险 LOW bg)。

## 验证记录

### 阶段 1(tokens.css,element-plus 引入前)

```
npm ci && npm test        → # tests 21 / # pass 21 / # fail 0
npx vite build            → ✓ 72 modules;css 53.04 kB(gzip 11.08)/ js 203.43 kB(gzip 72.55)
```

### 阶段 2 安装与门禁

```
npm install --registry=https://registry.npmjs.org/ element-plus @element-plus/icons-vue
npm install --registry=https://registry.npmjs.org/ -D unplugin-vue-components unplugin-auto-import
grep -c "npmmirror\|taobao" package-lock.json                          → 0
grep '"resolved"' package-lock.json | grep -vc 'registry.npmjs.org'    → 0
npm audit --audit-level=high                                           → found 0 vulnerabilities(exit 0)
npm test(vite.config 改动后;smoke 测试在纯 node 下 import 该配置)     → 21/21 全绿
```

### 空壳验证(已完全还原)

方法:临时在 `LoginView.vue` 模板加 `<el-button type="primary">` + `<el-tag type="success">` → `npx vite build` → 检查产物 → `git checkout -- frontend/src/views/LoginView.vue` 还原。

结果:

- 构建绿;模块数 72 → **1656**;产物 `index-CJ0_uBsx.css` 86.77 kB(gzip 15.49)、`index-DvDjVGdL.js` 251.54 kB(gzip 90.76)。
- 产物 CSS 内实测:`.el-tag{` @7790、`.el-button{` @13811(按需样式确实注入,且只进用到的组件);Element 默认 `:root{--el-color-white:#fff;…--el-color-primary:#409eff…}` @0;tokens 覆盖块 `html:root{--el-color-primary: #0F766E;…}` @35694;`@layer rs-tokens` @38278。
- 还原后复建:产物与阶段 1 基线**逐字节一致**(内容哈希同为 `index-CziIvLeT.css` / `index-BtBkfZaY.js`)——探针还原彻底 + 零使用零体积开销双重证明。
- 还原后 `npm test` → 21/21 全绿;`git status` 中 views/components 无残留改动。

构建过程仅有 rollup 对 `@vueuse/core` 两处 `/* #__PURE__ */` 注释位置的提示(注释被忽略,非错误,element-plus 传递依赖的已知噪音)。

## 遗留注意事项(交给后续阶段)

- 迁移页首次用 Element 组件时会摊入基础层(探针实测 gzip +22.6 kB),bundle-size.md 已建基准行与逐页追加区。
- `index.html` 的 Google Fonts 外链(Sora/Plus Jakarta Sans/JetBrains Mono)按定稿在**收尾阶段**移除,本阶段未动。
- `@element-plus/icons-vue` 已装未用:图标按项目风格在迁移页显式 import(未配 IconsResolver,派单如此)。
- 工具类段在 styles.css 删除前不生效(@layer 设计使然);删除 styles.css 的收尾阶段无须改 tokens.css。
