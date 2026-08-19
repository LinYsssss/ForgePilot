# Impl notes — 步骤 5「新架构骨架」(2026-08-13)

> 范围:implement.md 步骤 5,仅骨架;不含步骤 6 的业务页面迁移。
> 验证:Node v24.13.0(步骤 0 已声明的开发口径;Node 22 正式验收留给步骤 9)
> `npm test` **39 pass / 0 fail**(21 项既有 + 18 项新增),`npm run build` 成功 5.3–6.6s / 1764 modules。

## 1. 交付物与文件清单

### 新增

| 路径 | 职责 |
| --- | --- |
| `src/shared/theme/ink-tokens.css` | 合同 §3 全部冻结值 + §3.3/§3.4 字体/字号/间距/圆角/阴影 + 动效时长 + 洗色/ambient 专用段 |
| `src/shared/theme/ink-base.css` | `.ink-stage` 底座、focus-visible 环、skip-link、ink-reveal、跨 feature 按钮原语、`.ink-toast`、静态/减动效降级 |
| `src/shared/theme/index.js` | 主题入口(main.js 引一次) |
| `src/shared/motion/motionPolicy.js` | 动效偏好状态机(纯逻辑):staticMode/reduced/coarse/hidden/unfocused → ambientAllowed/mode |
| `src/shared/motion/useMotionPolicy.js` | 单例 Vue 绑定:matchMedia、visibilitychange、blur/focus,重入保护 |
| `src/shared/motion/pointerField.js` | 归一化 + rAF 合并写入(纯逻辑,可注入调度器) |
| `src/shared/motion/usePointerField.js` | **唯一** pointermove 观察器;写根节点 `--ink-pointer-x/y`;抑制归零;`getPointerState()` 供 ambient 读 |
| `src/shared/motion/inkParticles.js` | 墨粒模型(纯逻辑):数量 30–64、DPR≤1.5、半径 0.7–3.2、朱砂 1/15 |
| `src/shared/motion/InkParticleField.vue` | 原生 Canvas 渲染;策略变化即启停 RAF,停时留静态一帧 |
| `src/shared/motion/TaijiAmbientMark.vue` | 太极符号,watermark(32s)/anchor(22s)两种呼吸 |
| `src/shared/motion/InkAmbientScene.vue` | 纸纹+远山+三云带+双墨雾+太极+墨粒;`aria-hidden`、`pointer-events:none`、`z-index:-1`,可整体移除不改布局 |
| `src/shared/ui/sealTone.js` / `SealBadge.vue` | 印记徽章:形状+印记字+文本三重编码;`stamp` 一次性落印 |
| `src/shared/ui/progressModel.js` / `BrushProgress.vue` | 笔触进度:SVG 描线 320ms 仅在阶段变化时过渡;步骤=印记字+文本+meta |
| `src/features/shell/drawerController.js` | 抽屉状态机(纯逻辑):断点常量、互斥、inert 决策、焦点目标、Escape 优先级、Tab 循环 |
| `src/features/shell/CaseIndex.vue` | 案卷索引(展示组件):搜索过滤 + 主导航 + slots;冷青细线表达当前项 |
| `src/features/shell/InkShell.vue` | 新壳层:三栏 grid(236 / minmax(640,1fr) / 292,朱批可折叠)、1279/767 断点抽屉、焦点/键盘合同、绑定 motion+pointer、`.ink-toast` |
| `src/features/auth/LoginGate.vue` | 墨境门禁:稳定纸面表单、朱砂错误框、双提交防护;认证语义=旧 LoginView |
| `src/pages/InkAtelierPage.vue` | 隔离入口页:门禁分流 + 骨架预览(印记/笔触样例均标注"示例") |
| `tests/ink.test.mjs` | 18 项:令牌 drift gate、无硬编码色扫描、状态机/指针/墨粒/抽屉/进度纯逻辑、路由与认证锚点 |

### 修改

- `src/router.js`:新增 `{ path: '/ink', name: 'inkAtelier', meta: { shell: 'ink' } }`;旧 8 路由、`#agent-evidence=` 重定向、catch-all 原样。
- `src/App.vue`:顶部新增 `router-view v-if="isInkShell"` 分支(`route.meta.shell === 'ink'`);旧 LoginView/AppShell 分流逐字保留。
- `src/main.js`:追加 `import './shared/theme/index.js'`。

## 2. 路由整合方案(选并存,理由)

任务允许两种方案;选择 **「保持旧壳层并存」**:

- design.md §8.1 明文要求骨架"先挂在隔离入口/feature flag 下";`meta.shell = 'ink'` 即该隔离开关。
- 旧 8 个 view 的行为零改动(旧 AppShell 里还挂着 confirm 对话框、刷新、导航装载逻辑,若把旧页塞进新纸面需整套复刻,风险远大于并存)。
- 门禁不走路由守卫而复用 App.vue 的"登录态分流"模式(`LoginGate v-if="!authenticated"`),天然避开启动时 `loadMe` 未返回的竞态,且 401 漏斗、CSRF 引导、会话单例全部同源,无第二套认证。
- 墨境案卷索引里旧目的地(总览/项目/仓库/PR/审查/知识库/AI 日志)全部可点,导航映射与 App.vue `onNavigate` 相同(agent→openAgentWorkspace、aiLogs→openProjectAiLogs、dashboard/projects→goto、其余 goTab),跳转后由旧壳层接管——"全部旧 views 可达可用"成立。
- 步骤 6 在 `/ink` 内迁入 Agent/Reviews 主路径;步骤 8 之后再讨论把 ink 壳层设为默认。

## 3. 实现要点

- **三层渲染面**:语义层(页面/表单/文字)不透明、无模糊;壳层(topbar/index/rail)洗色 + `backdrop-filter`(12/8px ≤ 合同 12px),`@supports not (backdrop-filter)` 时以 token 覆盖为近实色;ambient 层 fixed、`z-index:-1`、`aria-hidden`、`pointer-events:none`,移除不改布局。
- **指针视差**:唯一 observer 在 `usePointerField`(壳层/门禁绑定,重入计数保证单例);rAF 每帧至多一次写 `--ink-pointer-x/y`;只有 `InkAmbientScene` 的 CSS 消费;位移预算 远山 2/-3px、云 2–3px、雾 ±6px、笔触 10px,全部落在 `.ink-parallax` 包装层。
- **降级矩阵**:用户静态(`.ink-static` 类,杀包装层 transform 与环境动画)/ reduced+coarse(媒体查询全冻结)/ hidden+unfocused(JS:指针归零 + 粒子 RAF 停止)。测试覆盖状态机全部抑制项。
- **冻结抽屉合同**:断点 767/1279 与 CSS 一致(常量入测试);关闭态 `inert`+`aria-hidden`;打开焦点进关闭钮、关闭归还触发器;遮罩点击、Escape(朱批优先)、抽屉内 Tab 循环、body 滚动锁;离开断点自动收抽屉。桌面朱批折叠时焦点交还顶栏开关。
- **体积**:ambient/motion 八文件源码 24.6KB / gzip 8.5KB(≤ 12KB 预算;打包 minify 后更小);环境静态资源 0KB(全 CSS 渐变 + 内联 SVG,≤300KB 预算)。整包对照基线:CSS gzip 32.45→39.04KB(+6.6),JS gzip 186.46→196.69KB(+10.2,含壳层/门禁/页面/原语),模块 1735→1764。>500KB 单 chunk 警告为基线既有(Element Plus 全量),步骤 8/9 处理。
- **drift gate 自动化**:`tests/ink.test.mjs` 逐字断言 §3.1/§3.2 全部冻结色值与间距/圆角/阴影;并扫描全部新 SFC 禁止十六进制色(颜色只能经 ink-tokens.css 进入);印记朱砂独占 Critical、指针变量仅 ambient 消费也有断言。

## 4. 与合同/原型的偏离清单(逐条,含理由)

对「合同 v1.0 文字」零偏离;以下 6 条是**原型代码与合同文字冲突时按合同执行**或**修复原型 quirk**,均不改变合同语义:

1. **SealBadge High 不用朱砂**(原型风险条"高"用了朱砂):合同 §5 明文"朱砂只用于 Critical、主动作或最终确定状态",High 改用 `--amber-ink` 描边;Critical 以方印+填底与其区分(§3.1 要求形状参与编码)。任务声明 ui-design.md 为最高权威。
2. **纸纹呼吸 0.08–0.13**(原型 keyframes 写死 0.16–0.23,覆盖了其声明的 0.11 基准):按 v0.3 冻结陈述"纸纹 opacity 降到 0.11"取 0.11±0.03,36s 不变。
3. **太极呼吸改相对透明度**(原型 keyframes 用绝对 opacity,导致登录页标题锚点被动画压到 ≈0.05 几乎不可见):组件内呼吸 0.63–1.0,绝对强度由放置层决定(工作台 0.072/登录 0.105),观感等于原型水印的 0.045–0.072。
4. **登录太极用等价静态 top/left 定位**(原型把居中 translate 和指针位移放同一元素,静态降级 `transform:none` 会把它甩出构图):1440×900 下换算为 top 12%/left 7%,构图不变,降级正确。
5. **≤767 朱批抽屉 z-index 50→54**(原型手机端遮罩 52 盖住打开的朱批栏 50,栏内不可点):修正层级(index 55 > rail 54 > scrim 52),抽屉互斥所以无新冲突。
6. **平板朱批入口并入顶栏**(原型平板用右下悬浮 FAB):D-007 冻结的是"移动入口位于 sticky topbar,44×44,不得在正文上悬浮游走";统一用顶栏 44×44 icon-button 覆盖平板+手机,消除悬浮件遮挡正文的风险,aria-expanded/controls 齐全。

另有 4 条**合同区间内的取值/命名选择**(非偏离,备查):指针变量名用合同 §7 的 `--ink-pointer-x/y`(原型为 `--pointer-x`);工作台墨粒下限收紧到合同的 30(原型工作台公式允许 26);朱砂粒间隔取 1/15(合同"约 1/13–1/15");focus 环 offset 取合同 §6 的 2px(原型 3px)。云带为仅有的三个持续动画大面积模糊层(38/46/54s),墨雾无自身动画、只随指针 ±6px——与原型结构一致,满足 §7"同时运行的大面积模糊层 ≤3"。

洗色(`--ink-wash-*`)与 ambient 专用色(远山/太极/笔触/渐变底)均逐值移植自 Stage 3 已批准并随 §11 冻结的原型 styles.css,不是新颜色;在 ink-tokens.css 内单独分段标注来源。

### Check 阶段追加(trellis-check 自修,2026-08-13)

7. **环境呼吸/漂移从 margin 换成 transform**:原型 taiji-breathe 用 `margin-top`、cloud-drift 用 `margin-left/right`(布局属性),与合同 §7 实现规则「只改 transform/opacity」冲突;按 §11「等价技术实现」改为 `translateY/translateX`,幅度、节奏(-5/8px,±1.5–2vw,38/46/54s)逐值不变;右锚定云带(margin-right)换算时取反号。`tests/ink.test.mjs` 新增断言禁止两个 ambient 组件出现 margin 声明。
8. **遮罩洗色回正**:`--ink-wash-scrim` 实现时写成 0.28(原型无此值),已改回原型 `.drawer-scrim` 逐字值 `rgb(31 33 29 / 0.22)`;dialog 背景(原型 0.34)属步骤 6 的审批对话框,届时另立 token。
9. **LoginGate 解剖与合同 §5 的差异补记**(此前漏报):合同 §5 LoginGate 列有「保持登录、账号恢复、演示入口」;生产实现只保留组织账号/密码/错误提示,恢复指引以「联系管理员」文案承担。理由:D-002 冻结认证语义(HttpOnly 会话、无 remember-me 后端接口);「演示账户」是原型 Stage 3 的演示器物(login.js 填 reviewer@reposage.local),生产无此账户,保留即假入口;账号恢复无后端流程。`ysainlin` 反向断言沿用 smoke.test.mjs 对 LoginView 的既有守卫。此差异待步骤 9 质量门确认或按 drift gate 入 `ui-decisions.md`。
10. **SealBadge 补 `animationcancel` 归位**:静态墨境在落印进行中开启时 `animation:none` 不触发 `animationend`,`stamping` 会滞留并在退出静态后补播一次;补 `@animationcancel` 兜底。

## 5. 已知边界(步骤 6+ 处理)

- `/ink` 主纸面当前为骨架预览(印记/笔触样例 + 迁移说明,全部标注"示例"),不承载业务结论;Agent/Reviews 真实数据、EvidenceDiff、AnnotationRail 内容、审批对话框与落印全流程在步骤 6。
- 静态墨境开关为会话级(不落存储;Web Storage 纪律下如需持久化,步骤 6+ 单独决策)。
- 浏览器三档实测截图与帧率证据归步骤 9 质量门(本机无浏览器工具,与步骤 0 基线声明一致)。
