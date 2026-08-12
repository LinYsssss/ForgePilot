# r6 逐页迁移规约(每个迁移代理必读)

权威链:prd.md(R3/R4)→ design.md(四步法)→ design-tokens.md(色板)→ 本文(操作细则)。

## 环境

- 宿主机无 node。测试/构建:`docker run --rm -v /root/reposage/frontend:/ws -w /ws node:22-alpine sh -lc 'npm test'`(构建把 `npm test` 换成 `npx vite build`)。
- vite dev 服务器已由主会话常驻(容器 r6-dev,http://localhost:5173,HMR 生效),**不要**自行启停。
- 截图由主会话在每页完成后执行,你不用管。

## 每页固定流程(design.md 四步法的落地版;并行模式修订)

> **并行模式(2026-08-10 起)**:多页代理同时开工。为消除共享写点:
> 1. **styles.css 一律冻结不改**——独占类删除从「随页删除」调整为「全局收尾统一普查删除」(PRD 字面调整,已留档;终态零残留 AC 不变)。
> 2. 每个代理只准改**本页文件清单**内的 .vue 文件;发现需要动清单外文件,停下写进报告,不要动手。
> 3. 自检**只跑 `npm test`**,不跑 `npx vite build`(并发构建践踏 dist/;构建验证由主会话集中执行)。
> 4. 迁移页与 styles.css 共存规则:模板保留语义类名(risk-*/sev-*/st-*/diff-* 等)时,在 `<style scoped>` 内**同名重定义**为 tokens 配色(scoped 特异性天然压过全局,视觉即刻脱离 Observatory);或换 rs- 前缀新名。禁止依赖 styles.css 提供任何视觉。
> 5. 全局件(AppShell 的 toast/modal/侧边栏)仍是 Observatory 风,属接受的过渡态,收尾统一处理;页内不要试图修它们。

1. **结构映射**:手写布局 → Element 容器。约定:
   - 面板 `el-card`(shadow="never",描边风)/ 页内分区 `el-space`/`el-row+el-col`;
   - 表单 `el-form`+`el-form-item`+`el-input/el-select`(label 关联=可达性 AC);
   - 列表:数据表格用 `el-table`;卡片网格/时间线等非表格形态保留语义结构,用 tokens 重铸;
   - 抽屉/弹层 `el-drawer`/`el-dialog`;标签徽章 `el-tag`+语义类补色。
2. **状态四态**:loading=`v-loading` 或 `el-skeleton`;empty=`el-empty`(文案沿用原页);error=`el-alert type="error"`;disabled=组件原生。删除该页自造的 skeleton/empty/spinner 结构。
3. **样式**:页内新样式写 `<style scoped>`,只准引用 `--rs-*`/`--el-*`/`--sp-*` token,禁止魔法 hex(AC 抽查会 grep);styles.css 冻结见上。
4. **自检**:容器 `npm test` 21/21 绿 → 报告主会话(改动文件、组件映射要点、测试输出摘要、你在页内同名重定义了哪些语义类)→ 结束。

## 行为红线(违反即返工)

- composables/api/utils/router/directives **一行不改**(模板消费方式不变:同名函数、同参调用、同条件渲染);可改的只有本页文件清单内的 .vue 文件。
- 数据获取调用零变化:不新增分页参数、不改轮询/SSE 逻辑。原页没有分页控件的,**不要**因为换了 el-table 就加 el-pagination。
- 文案零变化(按钮文字、空态提示、表单 label、placeholder 全部原样)。
- 测试锚点保留:`input` 的 `autocomplete` 属性、AgentFindings 的 `data-evidence-path`、路由 `agent-evidence=`、「登录」「退出登录」「加载」等按钮文本(截图脚本与 node 测试都在断言)。
- `v-model` 语义:`el-select` 请显式 `value-key`/保持 `v-model.number` 等修饰符等价;`@keyup.enter` 在 el-input 上原生支持。
- 图标:项目风格显式 import(`@element-plus/icons-vue`),不装全局。
- 禁止 `::v-deep`/`:deep()` 硬改 Element 内部结构;微调走 Element 官方 CSS 变量。
- 单页内禁止新旧混搭(页根到叶全换);其它页保持 Observatory 属正常过渡态。
- **不 commit**(主会话逐页提交)。

## 迁移页清单与独占类速查(删前仍须 grep 复核)

- LoginView:`.auth-wrap .auth-card`(auth 段);toast 结构**保留**(全局收尾统一处理 useToast → ElMessage 的决策)。
- DashboardView(+DashboardStats/DashboardViz):`.stat-grid .stat .viz-row .donut* .legend* .cols .col-*`(viz 段整段独占)。
- ProjectsView:`.proj-grid .proj-card`。
- RepositoryView:`.row-commits`;diff 渲染(labels.diffLines)逻辑不动,配色换 `--rs-diff-*`。
- PullRequestsView:`.pr-meta .pr-report-list .issue-picker .issue-check .action-history .action-row .row-prs`。
- KnowledgeView(+KnowledgeDocPicker):`.doc-grid .doc-card .match* .kb-select .kb-head .kb-tools .kb-chips .kb-chip`(kb-* 若 PR 页也用则留到共用页迁完)。
- AiLogsView:`.log-group* .log-task-* .row-ailog`。
- ReviewsView(+ReviewCompare+ReportSummary):`.split .report-summary .risk-dial .sev-tally .sev-strip .issue* .callout .co-* .conf* .fb-* .compare-* .cs-tile .cc-head .row-tasks .row-reports`;diff 定位/citation 锚定逻辑不动。
- AgentView(+agent/ 五件):`.agent-summary .agent-timeline .timeline-* .agent-live-status .evidence-* .numbered-diff .diff-row .diff-number .finding*`;SSE/轮询逻辑不动,`el-timeline` 做外壳。
