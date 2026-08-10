# styles.css 退役普查表(r6 全局收尾)

> 方法:脚本提取 styles.css 全部 195 个类选择器 + 元素级/变量/keyframes 规则,逐个与 `src/**/*.vue` 的**模板消费**(class 属性 + :class 字面量 + 动态前缀 `'risk-'+x` 等通配)和**scoped 定义**交叉比对;另做反向核对——.vue 中全部 82 个 `var(--*)` 引用逐一确认命中 tokens.css 定义或 `--el-*`。假阳性(CSS 属性值 `content:`/`display:grid`、`rs-panel` 连字符边界、注释、`type="warning"` 属性、JS 字符串 `'rejected'`)已逐条人工排除。

处置类型:**删除**(全站零模板消费)/ **归家 base**(tokens.css §4)/ **归家 §3**(tokens.css @layer 工具类)/ **AppShell 重铸**(本次 scoped/结构替换)/ **页面已承接**(消费页 scoped 早已同名全属性重定义,删除全局无感)。

## A. 变量表(:root + dark/light 双主题块)

| 项 | 处置 | 依据 |
| --- | --- | --- |
| `--font-*` `--fs-*` `--r-*` `--z-*` `--t-*` `--ease-*` `--spring` `--blur` `--bg*` `--surface*` `--border*` `--text*` `--accent*` `--risk-*` `--code-*` `--e-*` `--inset-hi` `--glass-*` `--aurora-*` `--grid-line` `--panel` `--muted` `--mono` | 删除 | 反向核对:.vue 全部 82 个 var() 引用均命中 tokens.css / `--el-*`,旧名零引用 |
| `--sp-1..16` | 已在 tokens.css §1 原名续存 | 页面既有引用直接续用(design-tokens.md 约定) |
| `color-scheme: dark` / `[data-theme]` 双主题机制 | 删除 | 暗色模式明确出范围;tokens.css `color-scheme: light` 生效;useTheme.js 一并删除(唯一消费方 AppShell,测试零引用) |

## B. BASE 段

| 选择器 | 处置 | 依据 |
| --- | --- | --- |
| `* { box-sizing }`、`html { text-size-adjust }`、`body`、`h1-h4`、`button/input/select/textarea { font:inherit }` | 归家 base | 全局底座,亮色 token 化(body 走 `--rs-bg/--rs-text`,系统字体栈;主题切换 transition 随主题功能移除;h 系不再指定 display 字族——单字族体系) |
| `::selection`、`:where(a,[tabindex],summary):focus-visible`、滚动条三条 | 归家 base | 可达性/一致性兜底,teal 与 slate token 重着色 |
| `.mono` | 归家 §3(此前已入) | tokens.css @layer 已有同名 |

## C. BUTTONS 段

| 选择器 | 处置 | 依据 |
| --- | --- | --- |
| `button:where(:not(.el-button))` 元素级皮肤 | 删除 | 残余原生 button 全部页内全属性 scoped:DashboardView `.report-row`、DashboardViz `.legend-item`、AiLogsView `.log-group-head`/`.list-row`、AgentView `.inline-link`、ReviewsView `.vote` |
| `.secondary` `.ghost` `.danger` `.solid` | AppShell 重铸 | 原仅 AppShell 消费;刷新/退出登录/模态按钮已换 el-button(确认=type danger) |
| `.warn` `.sm` `.compare-btn` | 删除 | 全站模板零消费(普查 NONE) |
| `button.vote` `.on-TRUE_POSITIVE/…` | 页面已承接 | ReviewsView scoped |

## D. LAYOUT 段(壳)

| 选择器 | 处置 | 依据 |
| --- | --- | --- |
| `.app-shell`(mesh 渐变 + `::before` aurora 层 + `aurora-drift` keyframes) | AppShell 重铸 | el-container 根,平底 `--rs-bg`;aurora/玻璃拟态按 PRD 整体移除 |
| `.sidebar`(glass 半透明 + backdrop-filter + @supports 回退) | AppShell 重铸 | el-aside 亮色面 `--rs-surface` + 右描边,sticky 通高保留 |
| `.brand` `.brand-logo` `.tagline` | AppShell 重铸 | teal R 标参考 LoginView 手铸(flat `--rs-primary`) |
| `nav` `nav button(.active/:disabled)` `.nav-ico` | AppShell 重铸 | 保留原生 button 结构 tokens 全属性重铸;八项无障碍名逐字未动,`:disabled` 六项原样,active teal 高亮 + 左指示条保留 |
| `.sidebar-foot` `.user-chip` `.uname` `.avatar` | AppShell 重铸 | 亮色 chip;头像改 teal tint(`--el-color-primary-light-8`) |
| `.content` `.topbar` `.crumb` `.muted` `.topbar-actions` | AppShell 重铸 | el-main 根覆写 padding/overflow(overflow 回 visible 保 AI 日志 sticky 组头);面包屑/项目名 mono 结构不动 |
| `.theme-toggle` | 删除 | 主题切换按钮按任务要求移除(连同 useTheme 消费) |

## E–I. 面板/表单/徽标/列表/业务段

| 选择器(组) | 处置 | 依据 |
| --- | --- | --- |
| `.panel` `.panel-head`(hover 辉光、panel-in keyframes) | 删除 | 模板零消费(命中均为注释或 `rs-panel` 边界假阳性) |
| `.section-title` | 页面已承接 | KnowledgeDocPicker、PullRequestsView scoped |
| `.grid(.two/.three/.four)` `label.field` | 删除 | 模板零消费(已迁 el-row/el-col、el-form-item) |
| `input/select/textarea:where(:not([class^=el-]))` 皮肤、`::placeholder`、file 控件 | 删除 | 原生控件仅存三处且自足:复选框×2(KnowledgeDocPicker/PullRequestsView scoped accent-color+focus 环)、file×1(KnowledgeView `.rs-file` 全属性 scoped) |
| `.actions` | AppShell 重铸 + 页面已承接 | AppShell 处改 el-dialog footer;PatchApprovalPanel scoped |
| `.badge(.plain)` `.status-pill` 形状 | 页面已承接 | 消费 7 文件全部 scoped(AgentFindings/KnowledgeDocPicker/ReviewCompare/PullRequests/Reviews/Knowledge/AiLogs) |
| `.risk-*` `.sev-*` `.st-*` 色板、`pulse` keyframes | 归家 §3(此前已入) | tokens.css @layer 全站唯一映射 + `rs-pulse`;各消费页另有 scoped 同名 |
| `.list` `.list-row` `.row-tasks/.row-reports/.row-ailog` `.row-actions` `.selected` `.grow` | 页面已承接 | PullRequests/Reviews/AiLogs scoped(含 display:grid 等全属性,已逐条抽验) |
| `.row-commits` `.row-prs` | 删除 | 模板零消费(两页已重铸/换 el-table) |
| `.proj-grid` `.proj-card` | 页面已承接 | ProjectsView scoped |
| `.doc-grid` `.doc-card` `.match*` `.kb-select/.kb-head/.kb-tools/.kb-chips/.kb-chip` | 页面已承接 | KnowledgeView / KnowledgeDocPicker scoped |
| `.split` `.pr-meta` `.pr-report-list` `.issue-picker` `.issue-check` `.action-history` `.action-row` | 页面已承接 | PullRequestsView / ReviewsView scoped |
| `.report-summary` `.risk-dial(.r-*)` `.rs-body/.rs-meta` `.sev-tally` `.sev-strip/.sev-seg` `.issue*` `.kv` `.conf*` `.callout/.co-*` | 页面已承接 | ReportSummary / ReviewsView / ReviewCompare scoped(`.risk-dial`/`.sev-strip` 在其余页的"命中"是 `'risk-'+x`/`'sev-'+x` 动态前缀通配假阳性,已人工排除) |
| `.fb-list/.fb-item/.mine/.who/.you-tag/.when/.fb-row/.fb-empty` | 页面已承接 | ReviewsView scoped |
| `.diff-wrap/.diff-file-head/.diff-body(.numbered)/.diff-line/.ln-no/.ln-text` | 页面已承接 | RepositoryView scoped(diff 配色走 `--rs-diff-*`) |
| `.log-group*` `.log-task-*` | 页面已承接 | AiLogsView scoped |
| `.agent-summary` `.agent-timeline/.timeline-*` `.finding*` `.confidence/.evidence-*` `.numbered-diff/.diff-row/.diff-number` `.agent-live-status` `.field-hint/.inline-link` | 页面已承接 | AgentView + agent/ 五件 scoped |
| `.compare-summary/.cs-tile/.compare-grid/.cc-head/.compare-issue/.kb-signal/.kb-badge` | 页面已承接 | ReviewCompare scoped |
| `.warning` `.finding.rejected` | 页面已承接 | AgentFindings/PatchApprovalPanel scoped(ReviewsView 的 "warning" 命中是 el-button `type="warning"` 属性;AgentReviewWorkspace 的 "rejected" 是 JS 字符串) |

## J–N. 状态四态 / 浮层 / 过渡

| 选择器(组) | 处置 | 依据 |
| --- | --- | --- |
| `.empty` `.ico` `.empty.compact` | 删除 | 模板零消费(全站已 el-empty,附言用各页 `.empty-extra` scoped) |
| `.spinner(.dark)`、`spin` keyframes | AppShell 重铸 | 原仅 AppShell 刷新/确认按钮消费,已换 el-button `:loading` |
| `.skeleton` `.sk-row`、`shimmer` keyframes | 删除 | 模板零消费(全站已 el-skeleton) |
| `.toast(.error/.success)`、`toast-life` keyframes | 归家 §3 | tokens.css 新增亮色全套(三型左条+图标、`rs-toast-life` 3.2s 进度条、`--rs-z-toast`);AppShell/LoginView 模板类名未动,useToast 零改动 |
| `.modal-backdrop` `.modal`、`modal-in` keyframes | AppShell 重铸 | 改 el-dialog(esc/遮罩点击关闭语义等价,`@close` 统一走 dismiss) |
| `.hint` | 页面已承接 | KnowledgeDocPicker scoped(其余命中为 `login-hint`/`field-hint`/`rs-hint` 新名) |
| `.t-enter/leave-*` | 归家 base | AppShell/LoginView toast 过渡仍消费 |
| `.page-enter/leave-*` | 归家 base | App.vue 路由过渡 `<transition name="page">` 消费(任务允许 App.vue 非 scoped 块或 tokens.css,取后者与 `.t-*` 同家) |
| `.hide-sm { display:initial }` | 删除 | span 默认即 inline;唯一消费页 AiLogsView 已在页内媒体查询控制隐藏 |

## O–P. 响应式 / 无障碍

| 项 | 处置 | 依据 |
| --- | --- | --- |
| `@media 960px` 壳规则(app-shell/sidebar/nav/content/crumb) | AppShell 重铸 | 同断点同行为迁入 AppShell scoped |
| `@media 960px/520px` 页面规则(.grid/.split/.row-*/.report-summary/.panel/.agent-summary…) | 页面已承接 / 删除 | 消费页 scoped 自带窄屏规则(AiLogs/ReviewCompare/AgentReviewWorkspace 等),其余引用已消亡;移动端非目标 |
| `@media (prefers-reduced-motion)` 全局块 | 归家 base | 原样保留(tokens.css §4) |

## 汇总

- **删除(零引用直接消亡)**:约 40 类 + 全部 Observatory 变量/双主题机制/8 组 keyframes 中的 6 组(aurora-drift、panel-in、shimmer、spin、grow-x、modal-in;pulse→rs-pulse、toast-life→rs-toast-life 已易名归家)。
- **归家 tokens.css**:base 段(盒模型/正文/选区/滚动条/焦点兜底/`.t-*`/`.page-*`/reduced-motion)+ §3 新增 `.toast` 全套。
- **AppShell 重铸**:壳层 20 余类(容器改 el-container/el-aside/el-main,模态改 el-dialog,按钮改 el-button,导航 tokens 手铸)。
- **页面已承接**:其余约 120 类,九个业务页迁移期均已 scoped 同名全属性重定义,删除全局零视觉影响。
