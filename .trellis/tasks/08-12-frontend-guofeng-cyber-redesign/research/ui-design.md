# UI design contract：墨境书院

> **版本**：v0.4 可执行草案
> **阶段**：Stage 2 已批准；Stage 3 原型待验证；Stage 4 尚未冻结。
> **权威性**：实现可按本文制作原型，但在原型验收前不得批量扩面。

## 1. Product character

RepoSage 是一间隐于云雾中的“可验证数字书院”：太极表达审查中的平衡与复核，案卷承载任务，朱批表达风险与结论，笔触表达 Agent 进程，落印表达确定动作。整体轻、淡、有呼吸感；赛博感只作为实时状态、动态墨粒和交互反馈存在。

关键词：`克制`、`可信`、`文雅`、`精密`、`可读`。
拒绝：景区古风、仙侠游戏、廉价霓虹、AI 紫粉、全屏毛玻璃、仿木按钮、龙凤灯笼、伪书法正文、无语义故障闪烁。

## 2. Three-plane ownership

| Plane | Owner | 可包含 | 禁止 |
| --- | --- | --- | --- |
| Semantic product | feature/page components | 导航、数据、文字、代码、表格、表单、焦点、风险与动作 | blur、持续位移、墨化、glitch |
| Themed shell | AppShell/shared UI | 纸面、边框、印记、标题字、形状、语义色 | 承担唯一状态信息、截获装饰点击 |
| Ambient decoration | `InkAmbientScene` | 纸纹、远山、淡墨雾、低幅视差、局部晕染 | 业务数据、交互控件、布局占位、pointer events |

关闭 ambient plane 后，产品必须仍是完整、可辨认、可操作的审查工作台。

## 3. Design tokens

### 3.1 Color

| Token | Candidate value | Role |
| --- | --- | --- |
| `--canvas` | `#F1EDE5` | 页面外层云纸灰 |
| `--surface-paper` | `#FBF8F0` | 主工作纸面 |
| `--surface-raised` | `#FFFDFA` | 弹层/悬浮批注 |
| `--surface-muted` | `#F3EEE5` | 次级区域/筛选区 |
| `--ink-strong` | `#1F211D` | 主文字、标题 |
| `--ink-default` | `#3B3E37` | 正文 |
| `--ink-muted` | `#62665C` | 次级文字；候选对比 5.26:1 |
| `--line-soft` | `#DED7CB` | 分隔与默认边框 |
| `--line-strong` | `#C7BEAF` | 选中/结构边界 |
| `--cinnabar` | `#9E382B` | 主动作、Critical、朱印 |
| `--cinnabar-hover` | `#842D24` | 主动作 hover |
| `--cinnabar-soft` | `#F1DCD5` | 风险浅底 |
| `--mineral-cyan` | `#176F70` | focus、运行中、链接 |
| `--mineral-cyan-soft` | `#D8E8E4` | 运行浅底 |
| `--amber-ink` | `#86621E` | warning/highlight |
| `--success-ink` | `#276A53` | success/通过 |

颜色不能作为唯一状态编码。Critical/High/Medium/Low 同时使用文本、图标和印记形状。

v0.3 淡色纸面候选对比度：strong/paper 15.30:1、default/paper 10.25:1、muted/paper 5.54:1、white/cinnabar 6.88:1、cyan/paper 5.58:1、amber/paper 5.24:1；原型与真实组件仍需在 Stage 4 复测。

### 3.2 Code and evidence

| Token | Candidate value |
| --- | --- |
| `--code-surface` | `#F5F0E6` |
| `--code-text` | `#252720` |
| `--code-gutter` | `#E6DED0` |
| `--code-added-bg` | `#DDEBDD` |
| `--code-added-text` | `#245F45` |
| `--code-removed-bg` | `#F2D9D3` |
| `--code-removed-text` | `#8B302A` |
| `--code-focus` | `#176F70` |

Diff 行不做透明虚化；行号、增删符号和背景三重编码。

### 3.3 Typography

- 品牌与一级短标题：`"Noto Serif SC", "Songti SC", STSong, serif`，仅 12 字以内，600 weight。
- 正文与控件：`system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif`。
- 代码：`"JetBrains Mono", "SFMono-Regular", Consolas, monospace`；若未安装则使用系统回退，不从远端阻塞加载。
- 基准字号：12 / 13 / 14 / 16 / 20 / 28；正文不小于 14px，移动端关键正文不小于 15px。
- 正文行高 1.55–1.7；代码行高 1.5；展示标题行高 1.2。
- 不将书法字体用于正文、数字、代码、表格或操作标签。

### 3.4 Space, shape, depth

- 间距：4 / 8 / 12 / 16 / 24 / 32 / 48。
- 圆角：纸面 2px，普通控件 4px，弹层 6px；禁止大面积 16px+ SaaS 卡片圆角。
- 纸面边界：1px `line-soft`，可增加极弱内阴影模拟纸层；不得使用厚重拟物卷轴边框。
- 阴影：只表达层级，最大 `0 12px 36px rgb(46 39 28 / 0.14)`。
- 模糊：内容面始终为 0；shell 局部 ≤ 12px；纯环境云雾可到 32px，但同时可见的大面积模糊层不超过 3 个。

## 4. Layout contract

### Desktop ≥ 1280px

- 案卷索引：224–248px。
- 主审查纸面：`minmax(640px, 1fr)`，最高视觉优先级。
- 朱批栏：272–304px，可折叠。
- 顶栏只保留全局上下文、搜索、通知、主题/动效控制与用户入口。
- 主动作靠近审查结论，不在页面两端重复。

### Tablet 768–1279px

- 案卷索引 200–224px；主纸面占剩余空间。
- 朱批栏转侧边抽屉；风险摘要常驻主纸面顶部。
- Diff 与 finding 保持单一阅读列，避免压成三栏。

### Mobile ≤ 767px

- 单卷任务流：摘要 → Agent 进度 → findings → evidence/Diff → action。
- 案卷索引进入导航抽屉；朱批/证据使用分段页或底部抽屉。
- 主操作固定在安全区，但不能遮挡代码和最后一行内容。
- 环境山水改为静态低对比页脚/页头构图，不做鼠标视差。

## 5. Component contracts

### `TaijiAmbientMark`

太极只作为品牌/环境符号：登录页可作为主要视觉锚点，工作台只能是低透明水印。不得承载状态、遮挡表单或取代品牌文字；normal 模式仅做 22–32 秒呼吸，不持续快速旋转。

### `InkParticleField`

原生 Canvas 环境层，`aria-hidden`、`pointer-events:none`。粒子约 30–64 个，DPR ≤ 1.5，半径约 0.7–3.2px；以松烟墨为主，最多约 1/13–1/15 使用低透明朱砂。不得读取业务数据或响应控件 hover。

### `LoginGate`

稳定近实色纸面表单，包含组织账号、密码、保持登录、账号恢复、错误提示和演示入口。太极/云雾/颗粒在表单背后，输入、标签、错误和主操作均不透明、可键盘操作。

### `CaseIndex`

左侧案卷入口，包含搜索、当前案卷、主导航和保存案卷。当前项使用冷青细线 + 文本加粗，不以整块霓虹填充。

### `PaperWorkspace`

主内容纸面，负责页面标题、风险摘要、Agent 进度、finding 与 evidence。自身不模糊、不响应指针视差。

### `SealBadge`

圆/方印记 + 文本双编码。朱砂只用于 Critical、主动作或最终确定状态；普通标签用墨线/冷青，避免“满页红章”。

### `BrushProgress`

使用 SVG 笔触路径表示阶段推进；当前步骤同时显示图标、文本和数值/时间。动画只在阶段改变时播放一次。

### `FindingLedger`

桌面可采用紧凑表格，移动端改为分组卡片。选中行保持位置不变，通过边线、浅底和标题权重表达。

### `EvidenceDiff`

固定宽度代码字体、稳定行高、可键盘导航；支持 unified/split。禁止纸纹穿透影响字符边缘。

### `AnnotationRail`

右侧朱批时间线，包含来源、时间、状态、注释和跳转锚点；窄屏转抽屉。批注与正文用细线关联，不画复杂飞线。

### `ReviewActionBar`

次要动作墨线按钮，主要动作朱砂实底；危险动作需确认。success 使用一次落印反馈后恢复静态。

## 6. Interaction and data states

- `hover`：边线/底色轻变，120–180ms，不缩放大卡片。
- `focus-visible`：2px mineral-cyan 外环 + 2px paper offset，任何纸纹下可见。
- `active`：1px 下压或明度变化，不制造位移跳动。
- `disabled`：降低饱和度并保留标签；不得只用 opacity < 0.45。
- `loading`：稳定骨架或阶段文本；不使用持续旋转的大型水墨动画。
- `empty`：简短说明 + 下一步动作，可用静态留白山形。
- `error`：朱砂细框、错误摘要、重试/恢复路径；不闪烁。
- `permission`：明确缺少的权限与申请/返回动作。
- `offline`：顶部持久状态条 + 保留已加载内容；恢复后说明同步状态。
- `long-content`：标题/动作可 sticky，正文与 Diff 自然滚动；截断必须可展开。

## 7. Ink motion contract

| Motion | Purpose | Trigger | Normal | Reduced/coarse fallback |
| --- | --- | --- | --- | --- |
| Paper breathing | 品牌氛围 | idle | opacity 0.03–0.06，24–40s | static texture |
| Taiji breathing | 平衡/复核品牌符号 | idle | 22–32s opacity/position breathing | static mark |
| Ink particles | 技术生命感 | idle/pointer | 30–64 particles, DPR ≤ 1.5, slow drift | static sparse dots |
| Far mountain parallax | 空间层次 | pointer | max 2–3px | static |
| Cloud veil | 云中雾里氛围 | idle/pointer | 38–54s 漂移，max 2–3px, blur ≤ 32px | static pale `n`n| Ink mist parallax | 环境响应 | pointer | max 6px, blur ≤ 28px | static low-opacity wash |
| Near brush accent | 局部反馈 | pointer | max 10px | hidden/static |
| Ink wash reveal | 空间连续 | route/panel enter | 320–480ms, once | 160ms fade |
| Brush progress | 状态解释 | Agent stage change | 240–420ms SVG stroke | instant line + text |
| Seal confirmation | 成功反馈 | approved action | 120ms press + 220ms halo | instant icon/text |
| Error blot | 注意引导 | new error | 180ms one-shot edge bloom | static error border |

Implementation rules:

- AppShell 仅注册一个 pointer observer；使用 `requestAnimationFrame` 写 `--ink-pointer-x/y`。
- 只有 `InkAmbientScene` 消费全局指针变量，且 `pointer-events: none`、`aria-hidden: true`。
- 不在每帧触发 Vue 响应式更新；不读写布局交错；只改 transform/opacity。
- 同时运行的大面积模糊 ambient 层不超过 3 个；纸纹/远山属于静态或低成本层；同时发生的一次性效果不超过 2 个。
- `visibilitychange`、blur、reduced motion、coarse pointer 时停止观察器。
- 允许原生 Canvas 低密度墨粒；禁止实时流体模拟、高密度粒子、scroll-jacking 或 WebGL。Canvas 必须独立于业务 DOM，并在 static/reduced/coarse/hidden/unfocused 时停止 RAF。

## 8. Accessibility and performance budgets

- 正文 ≥ 4.5:1，大文本 ≥ 3:1；风险、focus 与 disabled 状态逐一测量。
- 全流程键盘可达；抽屉/对话框正确锁定和归还焦点。
- 触控目标 ≥ 44×44px；hover 信息必须也能通过 focus/tap 获得。
- ambient 关闭时零功能损失；不支持 `backdrop-filter` 时使用近实色表面。
- 单一 pointer RAF；无持续 layout shift；目标交互帧率 ≥ 55fps，出现明显掉帧即降级。
- 初版环境效果运行时代码目标 ≤ 12KB gzip（含太极/颗粒策略），静态纹理单项 ≤ 120KB，总环境资源 ≤ 300KB。
- 不为装饰阻塞首屏；纹理失败时使用纯 CSS 纸面与静态渐变。

## 9. Good / forbidden examples

Good：登录页以淡墨太极建立品牌记忆；工作台太极退为水印；墨粒稀疏慢漂；风险结论像朱批但仍是清晰标签；Agent 进度像笔触但有文本；鼠标移动仅让环境层产生轻微深度。

Forbidden：太极盖住登录字段；高密度粒子像雪花；颗粒追逐按钮；把 Diff 做成毛玻璃；用墨滴遮住加载内容；按钮写成竖排书法；所有状态都盖红章；触屏仍运行动画；暗色星空或青铜 HUD 混入 A 主方向。

## 10. Drift gate

任何新颜色、字体、圆角、阴影、模糊值、位移、动效时长、印记形状或主题隐喻，必须先进入本合同或 `ui-decisions.md`。页面局部不得创建近似令牌、复制 AmbientScene 或直接覆盖 Element Plus 内部结构。
