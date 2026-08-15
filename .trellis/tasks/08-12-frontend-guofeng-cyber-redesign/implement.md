# Implement：墨境书院动态审查台

> **当前状态：规划门已通过，待激活生产实施。** A「墨境书院」、交互原型与 UI design contract v1.0 已冻结；下一步完成隔离/Node 22 基线后进入生产 Vue 重写。

0. [x] **隔离与基线**：安全处理 R7 未提交工作；从最新 `main` 建立 `codex/frontend-guofeng-cyber-redesign` 独立分支/工作树；Node 22 下运行 `npm ci && npm test && npm run build`，记录旧 UI 截图、体积、console/network 与关键路径。（实况 2026-08-13：R7 已全部落盘推送；**用户豁免独立分支**，直接在 `codex/frontend-ink-prototype` 开工；Node 24 开发/Node 22 留待步骤 9 正式验收（PRD 已声明约束）；`npm ci` + 测试 21/0 + 构建 9.7s 全绿，体积基线入 `research/impl-baseline.md`；旧 UI 截图与 console/network 因本机无可运行栈转入步骤 1 补采）
1. [ ] **补齐 Stage 1 证据**：使用真实数据检查 Agent → finding → evidence/Diff → review action 路径；补长内容、权限、离线、错误恢复和 390/768/1440 当前态截图，更新 `research/ui-inventory.md`。
2. [x] **Stage 2 视觉方向**：用户选择 A「墨境书院」，追加水墨动态要求；选择、拒绝项、架构授权和可逆边界已写入 `research/visual-directions.md` / `research/ui-decisions.md`。
3. [x] **Stage 3 交互原型**：按 `research/prototype.md` 制作登录门禁 + 1440/768/390 Agent 工作台原型；演示太极水墨、低密度墨粒、normal/reduced/static、success/error/recovery；用户评审后记录证据。
4. [x] **Stage 4 冻结设计合同**：基于原型校正并批准 `research/ui-design.md` v1.0；测量候选颜色对比度，冻结令牌、布局、组件 anatomy、状态、响应式、水墨动效预算和禁止项。
5. [x] **新架构骨架**：建立 feature-first 目录、typed API/entity adapters、`shared/ui`、`shared/theme`、`shared/motion`、新登录门禁与 AppShell 隔离入口；旧认证/API 可通过兼容层工作。（实况 2026-08-13：`/ink` 隔离入口 + `meta.shell` 分流落地，旧 8 路由零改动并存；合同 §3 令牌逐字落地并有 drift-gate 测试钉死；动效五态降级 + 单指针观察器 + ambient 运行时 8.7KB gzip ≤ 12KB 预算；LoginGate 复用既有会话/401 漏斗；trellis-check 扫出 6 处已全部修复，残留 4 项及理由见 `research/impl-notes-step5.md` §4；npm test 39/0、build 绿）
6. [x] **纵向切片**：实现 CaseIndex + PaperWorkspace + Agent/Reviews 主路径，覆盖真实数据、finding、EvidenceDiff、AnnotationRail、ReviewActionBar、success/error/retry；组件和路由测试通过后独立提交。（实况 2026-08-13：workspace 六组件 + 两个纯逻辑模型落地，数据全走既有 composables/api 零复制；`useAgentWorkspace` 仅扩 `onAgentPage` 谓词纳入 /ink，check 补行为测试钉死双路由 SSE/轮询门；`#agent-evidence=` 锚点整链兼容；合同 §6 状态逐项真分支（按 ApiError.status）；trellis-check 修 2 处、7 条申报偏离全验真；npm test 58/0、build 绿；残留归步骤 9，见 `research/impl-notes-step6.md`）
7. [x] **水墨动效与降级**：实现 TaijiAmbientMark、InkParticleField、InkAmbientScene、单一 pointer observer、远山/墨雾/笔触/落印反馈；验证粒子/DPR预算、reduced motion、coarse pointer、page hidden、无 blur、纹理失败和性能降级。（实况 2026-08-14：实现在步骤 5 骨架中已落地并逐项核对；本轮补三条回归测试把此前只写在注释里的合同约束变成守卫——环境层零外部资源（故「纹理失败」按构造不存在，属强于合同 §198 的满足，见 `research/impl-notes-step7.md` §3）、持续动画大面积模糊层 ≤3（实测且仅识别出三层云带，带 heavy>0 自检防空跑）、模糊分级 §18/§81/§126（内容面恒 0、shell ≤12px）。另澄清「无 blur」是设计漂移约束而非浏览器降级，§187 的 blur 指 window 失焦事件、已有实现与测试。npm test 61/0、build 6.30s，gzip 42.19KB CSS / 206.80KB JS 与步骤 6 基线持平。运行时帧率/截图证据需浏览器，归步骤 9）
8. [ ] **逐页扩面**：Dashboard → Projects → Repository → PullRequests → Knowledge → AI Logs；每页迁移后删除对应旧表现层，保持 API/权限和路由语义，禁止长期双份业务逻辑。（进度 2026-08-14：**1/6 完成（Dashboard）**，并建好后续 5 页的迁移地基。①`features/shell/inkNav.js` 把「哪些页已迁入墨境」收成单点声明，迁一页只需打开 `ink` 标记，不再往 onNavigate 里堆 if；②`features/shell/InkPageFrame.vue` 抽出「登录门禁+壳层+侧栏+用户/登出」这套每页都一样的外壳，避免迁 6 页复制 6 份外壳；③关键决策：**迁移不改路径也不改路由名**，只换 component 并打 `meta.shell`——因此既有 `goto('dashboard')`、兜底重定向、nav 默认值、以及墨境工作台侧栏跳转全部照常工作，路由语义零变更；④旧表现层三件（DashboardView/DashboardStats/DashboardViz）已删除，CSS 由 290.7KB 降至 285.8KB；⑤补 3 条测试（导航模型分流、项目门控禁用态、迁移后路由语义与数据零复制），新组件已纳入硬编码色 drift gate。npm test 69/0、build 5.78s。**剩余 5 页未迁**）
9. [ ] **Stage 7 质量门**：设计漂移、键盘/焦点/对比/触控、390/768/1440、normal/reduced/static、console/network、刷新/返回/取消/重复提交/失败恢复、体积与动画帧率；Node 22 下 `npm test && npm run build`，证据写入 `research/qa-report.md`。
10. [ ] **Trellis 收尾**：`trellis-check` → 修复 Critical/High 或记录责任人 → 将稳定设计与前端架构规则沉淀到 `.trellis/spec/frontend/` → 按 Trellis 提交确认流程执行 → Finish/归档。

## Commit and rollback units

1. planning/prototype/design-contract；
2. feature-first scaffold + compatibility boundary；
3. Agent/Reviews vertical slice；
4. InkAmbientScene and motion policy；
5. one commit per migrated route；
6. QA fixes and stable spec promotion。

任一新页面可回退到旧路由实现；ambient plane 可单独关闭或移除；API、认证和权限合同不随视觉提交变化。

## Required artifacts

- `research/assets/visual-directions-comparison.png`
- `research/ui-inventory.md`
- `research/visual-directions.md`
- `research/prototype.md`
- `research/ui-design.md`
- `research/ui-decisions.md`
- `research/qa-report.md`
