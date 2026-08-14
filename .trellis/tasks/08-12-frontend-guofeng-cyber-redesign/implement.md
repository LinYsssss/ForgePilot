# Implement：墨境书院动态审查台

> **当前状态：规划门已通过，待激活生产实施。** A「墨境书院」、交互原型与 UI design contract v1.0 已冻结；下一步完成隔离/Node 22 基线后进入生产 Vue 重写。

0. [ ] **隔离与基线**：安全处理 R7 未提交工作；从最新 `main` 建立 `codex/frontend-guofeng-cyber-redesign` 独立分支/工作树；Node 22 下运行 `npm ci && npm test && npm run build`，记录旧 UI 截图、体积、console/network 与关键路径。
1. [ ] **补齐 Stage 1 证据**：使用真实数据检查 Agent → finding → evidence/Diff → review action 路径；补长内容、权限、离线、错误恢复和 390/768/1440 当前态截图，更新 `research/ui-inventory.md`。
2. [x] **Stage 2 视觉方向**：用户选择 A「墨境书院」，追加水墨动态要求；选择、拒绝项、架构授权和可逆边界已写入 `research/visual-directions.md` / `research/ui-decisions.md`。
3. [x] **Stage 3 交互原型**：按 `research/prototype.md` 制作登录门禁 + 1440/768/390 Agent 工作台原型；演示太极水墨、低密度墨粒、normal/reduced/static、success/error/recovery；用户评审后记录证据。
4. [x] **Stage 4 冻结设计合同**：基于原型校正并批准 `research/ui-design.md` v1.0；测量候选颜色对比度，冻结令牌、布局、组件 anatomy、状态、响应式、水墨动效预算和禁止项。
5. [ ] **新架构骨架**：建立 feature-first 目录、typed API/entity adapters、`shared/ui`、`shared/theme`、`shared/motion`、新登录门禁与 AppShell 隔离入口；旧认证/API 可通过兼容层工作。
6. [ ] **纵向切片**：实现 CaseIndex + PaperWorkspace + Agent/Reviews 主路径，覆盖真实数据、finding、EvidenceDiff、AnnotationRail、ReviewActionBar、success/error/retry；组件和路由测试通过后独立提交。
7. [ ] **水墨动效与降级**：实现 TaijiAmbientMark、InkParticleField、InkAmbientScene、单一 pointer observer、远山/墨雾/笔触/落印反馈；验证粒子/DPR预算、reduced motion、coarse pointer、page hidden、无 blur、纹理失败和性能降级。
8. [ ] **逐页扩面**：Dashboard → Projects → Repository → PullRequests → Knowledge → AI Logs；每页迁移后删除对应旧表现层，保持 API/权限和路由语义，禁止长期双份业务逻辑。
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
