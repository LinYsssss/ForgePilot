# Impl notes — 步骤 6「纵向切片:CaseIndex + PaperWorkspace + Agent/Reviews 主路径」(2026-08-13)

> 范围:implement.md 步骤 6,在骨架(提交 4e89d0d)之上迁入真实 Agent 审查主路径。
> 验证:Node v24.13.0(步骤 0 声明的开发口径;Node 22 正式验收留给步骤 9)。
> `npm test` **57 pass / 0 fail**(39 项既有 + 18 项新增),`npm run build` 成功 5.7s / 1780 modules。
> 体积对照步骤 5(1764 modules,CSS gzip 39.04KB,JS gzip 196.69KB):CSS gzip 42.18KB(+3.1),JS gzip 206.79KB(+10.1,含全部工作台组件与模型)。>500KB 单 chunk 警告仍为基线既有(Element Plus 全量),步骤 8/9 处理。

## 1. 文件清单

### 新增

| 路径 | 职责 |
| --- | --- |
| `src/features/workspace/workspaceModel.js` | 展示模型纯逻辑:Timeline→笔触步骤、Run 状态标签/语气、Finding 计数/排序/筛选签/选中维持、证据锚点、朱批栏批注派生、审批门模型(复用 `canApprovePatch`)、装载/审批错误分类(按 ApiError 结构化字段) |
| `src/features/workspace/diffModel.js` | Diff 行模型:unified 行(复用 `labels.diffLines` + 与旧 PatchDiffViewer 同构的 `+++ b/` 文件跟踪)、split 配对(删/增段逐行对齐,锚点挂新码侧) |
| `src/features/workspace/PaperWorkspace.vue` | 主审查纸面(合同 §5):案卷头 + 运行状态行 + 取消/重试/刷新、页面级状态条、风险摘要(真实计数)、BrushProgress 接真实阶段、朱批清单 + 严重度筛选签、证据/Diff、人工落款;空态/骨架分支 |
| `src/features/workspace/FindingLedger.vue` | 朱批清单:桌面紧凑行表 / ≤560 CSS 重排分组卡片(同一 DOM);SealBadge 三重编码;选中行=冷青边线+浅底+加粗、位置不动;role=listbox/option + v-list-nav |
| `src/features/workspace/EvidenceDiff.vue` | 证据与 Diff:元信息条、长说明 3 行截断可展开、否决/缺证据告警(旧文案逐字)、details 证据抽屉(Esc 折叠)、unified/split 双视图、`data-diff-file/line`+`data-evidence-path`+`.evidence-focus` 锚点逐字保留、520px 局部滚动、split 720px 最小宽横滚、验证日志、下载链接;expose `focusDiff()` |
| `src/features/workspace/ReviewActionBar.vue` | 人工落款:四道校验门(文本+边线双编码)、不可批准理由(旧文案逐字)、行内错误(error/permission/offline 三分支)、审批意见、退回(墨线 danger)/批准并落印(朱砂)+ InkDialog 确认、双击防护;≤560 吸底安全区 |
| `src/features/workspace/AnnotationRail.vue` | 朱批栏内容:批注时间线(来源+时间+状态+「定位证据」)、案卷简目、守门规范引言;容器/抽屉仍由 InkShell 承担 |
| `src/features/workspace/RunCaseList.vue` | 案卷清单(CaseIndex #case 槽):Run 筛选签(ALL/ACTIVE/WAITING/FAILED/DONE,计数来自 `agentRunCounts`)、Run 行(状态标签+短 SHA+时间)、筛选无匹配→「显示全部」(旧 AgentView 语义) |
| `src/features/shell/InkDialog.vue` | 墨境模态(冻结对话框合同):捕获阶段 Escape(优先级:对话框>抽屉)、Tab 循环(复用 `drawerController.nextTrapIndex`)、焦点入取消钮/关闭归还触发器、body 滚动锁、遮罩点击关闭、busy 期间不可关 |
| `tests/ink-workspace.test.mjs` | 18 项:笔触模型(推进/失败/跳过/未知阶段)、完成步骤旧口径、Run 呈现(朱砂不参与)、Finding 计数/排序/筛选/选中/锚点、朱批派生、审批模型(理由逐字)、错误分类、unified/split 行模型、`/ink` 纳入 SSE 活跃判定与 citation 锚点源码钉 |

### 修改

- `src/pages/InkAtelierPage.vue`:骨架演示页 → 纵向切片编排页。门禁/导航映射保持;新增案卷装载(watch authenticated+activeProject)、Run 选择/筛选、Finding 选中维持(`defaultSelectedId`)、证据定位(nav query + `focusEvidenceAnchor` 直调补偿同锚点重复点击)、useConfirm 模态的墨境呈现、批准后一次落印覆盖层。
- `src/composables/useAgentWorkspace.js`:`onAgentPage()` → `AGENT_WORKSPACE_PAGES = ['agent', 'inkAtelier']`。仅扩展「页面活跃」谓词,SSE/轮询/退避/取消/重试语义零改动(composables.test 钉死的生命周期全部原样)。
- `src/features/shell/InkShell.vue`:追加 `railBadge` prop(顶栏朱批入口角标,承接原型 FAB 的「批 3」计数;D-007 已冻结入口在 sticky topbar)与 `defineExpose({ closeDrawer })`(承接原型 `closeRail(false)`:定位证据后收抽屉不归还焦点,焦点交给码面)。
- `src/shared/theme/ink-tokens.css`:新增 `--ink-wash-dialog: rgb(31 33 29 / 0.34)` 及无 backdrop-filter 回退 `rgb(247 242 231 / 0.97)`——两值均为已批准原型 `.dialog-backdrop` / `@supports not` 块逐字值,**步骤 5 check 记录 §4-8 明文预留本步另立**,非新颜色。
- `src/shared/theme/ink-base.css`:`.ink-toast` 追加 ≤560px 分支(bottom `calc(82px + safe-area)`)——实现 D-007 冻结条款「Toast 必须避开 sticky approval safe area」,82px 为原型 `.toast` 逐字偏移。
- `tests/ink.test.mjs`:硬编码色扫描与指针变量禁令名单纳入全部新 SFC;追加 `--ink-wash-dialog` 令牌断言。

## 2. 复用 vs 新建边界(数据逻辑零复制)

| 能力 | 来源 | 方式 |
| --- | --- | --- |
| Run 列表/筛选/计数 | `useAgentWorkspace.filteredAgentRuns / agentRunCounts / agentRunFilter` | 直接 import 单例;RunCaseList 纯 props 呈现,不重算筛选 |
| Timeline/Finding/Patch 装载 | `loadAgentRuns / loadAgentWorkspace / selectAgentRun` | 直接调用;页面仅补 try/catch 做状态条分类 |
| SSE + 轮询 + 退避 + 终态停机 | `startAgentPolling`(由 loadAgentWorkspace 内部驱动) | 零触碰;仅 `onAgentPage` 纳入 inkAtelier |
| 取消/重试(含确认文案) | `askCancelAgentRun / askRetryAgentRun` + `useConfirm` | 直接调用;确认模态由 InkDialog 呈现同一单例,`run(confirmAction)` 与旧 AppShell 同构(失败保留模态) |
| 审批提交 | `api/patchApproval.submitPatchApproval` | 直接 import(与旧 PatchApprovalPanel 同一入口) |
| 审批可用性 | `components/agent/patchApprovalPolicy.canApprovePatch` | `approvalModel` 内 import 复用,不建第二套策略 |
| 审批后续(toast+重载) | `onPatchDecided / onPatchError` | 直接调用 |
| 证据定位 | `focusEvidenceAnchor` + route query `evidence=path:line` | 复用定位本体与 query 语义;墨境组件保留 `data-diff-file/line`、`data-evidence-path`、`.evidence-focus` 锚点(有测试钉) |
| diff 行号推导 | `utils/labels.diffLines` | `diffModel.unifiedRows` 包装复用;split 为新纯逻辑(对既有输出的投影) |
| 认证/401/CSRF | LoginGate + `useSession` + client 漏斗 | 骨架既有,未动 |
| 会话/项目 | `useSession / useWorkspace(goto/goTab/refreshAll/logout)` | 直接调用;导航映射与 App.vue onNavigate 一致 |
| 新建(纯呈现) | workspaceModel/diffModel 派生、7 个 SFC 的版式与状态呈现 | 全部新写;旧 AgentReviewWorkspace/AgentFindings/AgentTimeline/PatchDiffViewer/PatchApprovalPanel 与旧 AgentView **零改动**继续服务旧 `/agent` |

## 3. 状态覆盖矩阵(合同 §6 逐项)

| 状态 | 实现 |
| --- | --- |
| hover / active / focus-visible / disabled | 主题原语按钮(墨线/朱砂/文本钮)+ ink-base focus 环;disabled 降饱和保留标签;行 hover 仅底色轻变 |
| loading | 静态骨架条 + 阶段文本(不转圈);刷新钮「正在装载…」;RunCaseList「装载中…」;轮询态沿用旧文案「正在自动刷新持久化状态 / 运行已结束 / 自动刷新已暂停」 |
| empty | 未选项目→前往项目页;无 Run→解释 Webhook 触发 + 刷新/前往 PR 工作流;无 Finding→「当前 head 没有 Finding。」;筛选无匹配→提示切换筛选签;无 Patch→dashed 空面 +「Finding 与证据仍可正常复核」;朱批栏空说明 |
| error | 装载失败→朱砂细框 banner(role=alert)+「重试装载」;审批失败→行内朱砂框(标题+摘要)+ toast(旧漏斗);校验门 FAILED→朱砂边线+「未通过」 |
| permission | 403 装载→banner +「切换项目」;403 审批→「缺少审批权限」+ 联系管理员指引(按 `ApiError.status` 分支,不嗅探文案) |
| offline | status 0→顶部持久状态条(role=status)+ 保留已加载内容 +「恢复连接」;审批 offline→「意见会保留」+ 重试路径;离线期批准钮禁用(原型语义) |
| success | 批准→InkDialog 确认→一次落印覆盖层(normal 1.2s / 静态与降级 0.5s 即时呈现)+ toast + 工作台重载;拒绝/取消/重试→toast |
| long-content | 说明 3 行截断可展开(换 Finding 复位);证据 >3 条收 details;diff 520px 局部滚动、split 720px 最小宽横滚(冻结移动 Diff 合同)、split 表头 sticky;验证日志 details + 300px 滚动;批注 3 行钳制;标题溢出 anywhere 断行 |
| 响应式 | 桌面三栏;≤1279 风险摘要重排 + 朱批转抽屉(壳层既有);≤880 头部/清单纵排;≤560 卡片化清单、双列校验门、吸底审批区 + toast 避让、双列筛选签横滚 |
| 动效降级 | 对话框/纸面入场复用 `.ink-reveal`(ink-base 统一接管 static/reduced);落印覆盖层 `motionMode !== 'normal'` 时去动画缩短驻留;无新增持续动画、无第二指针观察器 |

## 4. 关键行为决定(备查)

1. **`/ink` 内证据定位闭环**:朱批栏/证据条目 → `nav.push({ name:'inkAtelier', query:{ evidence } })` → 复用 `focusEvidenceAnchor`;同锚点重复点击时 query 不变、watch 静默,页面补一次直调;抽屉形态下定位后 `closeDrawer(false)` 并把焦点交进码面(`EvidenceDiff.focusDiff`,与原型 `$('.diff').focus` 同义)。旧 `#/agent?evidence=` 外链与旧页面路径不受影响。
2. **Escape 优先级**:InkDialog 在 document 捕获阶段处理 Escape/Tab 并 stopPropagation,InkShell 的抽屉键序(冒泡)不会先行;busy 期间对话框不可关,避免动作在飞行中丢失宿主。
3. **审批语义**:批准=危险/最终动作 → 确认后提交(原型冻结流程);拒绝沿用旧面板直接提交;双击由组件级 busy 防护;`blocked`(页面 error/offline)只禁批准不禁拒绝(原型语义)。patch 为空时两钮皆禁(旧面板此处点拒绝会因 `patch.id` 崩溃,本实现以禁用护住,提交语义不变)。
4. **失败步骤呈现**:BrushProgress 的 current 停在第一个未了结步骤,FAILED/INTERRUPTED/CANCELED 以 meta 文本声明(图标+文本+时间三编码);「完成步骤 x/y」沿用旧 `['SUCCEEDED','COMPLETED','SUCCESS']` 口径。

## 5. 偏离清单

对**合同 v1.0 文字零偏离**。以下 7 条为申报项(边界跨越或原型→生产的必要替换),均不改变合同语义:

1. **新增洗色令牌 `--ink-wash-dialog`(0.34)+ 回退(0.97)**:步骤 5 check 记录 §4-8 明文预留;两值均为已批准原型逐字值,非新颜色。已入 drift-gate 测试。
2. **ink-base `.ink-toast` ≤560 避让规则**:实现 §11 冻结条款(本步才引入吸底审批区,条款首次生效);82px 为原型逐字值。
3. **InkShell 追加 `railBadge` prop 与 `closeDrawer` expose**:均为增量,不改抽屉状态机与既有键序;角标只承载 Critical 计数(朱砂=Critical 语义内)。
4. **`useAgentWorkspace.onAgentPage` 纳入 `inkAtelier`**:否则 SSE/轮询在 /ink 上会拒绝刷新;仅扩展页面活跃谓词,生命周期钉死测试全绿。
5. **EvidenceDiff 默认 unified(原型演示默认并排)**:合同 §5 只冻结「支持 unified/split」未冻结默认;unified 是补丁原文的忠实投影且与旧查看器一致,并排一键可达。
6. **风险摘要不设「风险评分」数字**(原型演示的 76 分):后端无此字段,不虚构指标;磁贴全部为真实计数(活跃/可阻断/四档严重度/状态/Patch/步骤)。
7. **对话框与空态文案生产化**:原型「此原型不会提交真实数据」等演示语替换为真实语义(「该决定会写入审计」等);无 Run 空态如实说明 Webhook/PR 触发路径。

组织性说明:`features/workspace` import `features/shell/InkDialog.vue`(跨 feature 引用壳层原语)——InkDialog 与抽屉/遮罩同属壳层覆盖面家族,且需复用 `drawerController.nextTrapIndex`(shared 不得反向依赖 features,故不落 shared/ui)。

## 6. 已知边界(后续步骤处理)

- 步骤 5 残留 4 项未在本步强修(深链首帧闪烁等无零风险顺手方案,未动)。
- Reviews 报告页(useReviews 域)的墨境迁移属步骤 8 扩面;本切片按任务定义覆盖 Agent → Finding → 证据/Diff → 审批主路径,Reviews 记录入口经案卷索引走旧壳层可达。
- 首登且从未选过项目时,选择项目仍需借道旧项目页(空态已给「前往项目页」动作);把项目选择迁入墨境属步骤 8。
- 浏览器三档实测截图、帧率与对比度逐状态复测归步骤 9 质量门(与步骤 0 基线声明一致)。
