# Implement：古风赛博动态审查台

> 当前状态：仅规划，尚未批准实现。前置 R7 工作区必须先安全落盘；视觉方向选择是阻塞门。

0. [ ] **隔离与基线**：处理 R7 未提交工作；从最新 `main` 建立 `codex/frontend-guofeng-cyber-redesign` 独立分支/工作树；使用 Node 22 完成 `npm ci && npm test && npm run build`，记录截图、体积、控制台与网络基线。
1. [ ] **Stage 0–1 产品与信息架构**：复核 `prd.md` 和 `research/ui-inventory.md`；用真实数据补齐主路径、错误恢复、长内容、权限、离线与三档视口证据。
2. [ ] **Stage 2 视觉方向**：呈现 A 墨境书院 / B 星宿夜巡 / C 玉衡机关；记录唯一选择、拒绝项和可逆边界到 `research/visual-directions.md` 与 `research/ui-decisions.md`。
3. [ ] **Stage 3 原型**：制作 Agent 工作台 1440px 与 390px 原型，覆盖 entry → task → evidence/Diff → primary action → success → failure recovery；记录到 `research/prototype.md` 并完成评审。
4. [ ] **Stage 4 设计契约**：冻结 `research/ui-design.md`：令牌、字体、布局、组件 anatomy、全部状态、响应式、动效 purpose/budget、pointer/reduced-motion/touch/blur fallback、示例与禁止项。
5. [ ] **Stage 5 纵向切片**：实现 AppShell + AmbientScene + Agent 工作台 + Reviews 关联面；真实数据打通，补测试并完成切片级浏览器 QA 后独立提交。
6. [ ] **扩面迁移**：按 Dashboard → Projects → Repository → PullRequests → Knowledge → AI Logs 迁移；每页复用令牌与组件，补齐状态并独立提交，禁止顺手改后端合同。
7. [ ] **Stage 6 动效与降级**：实现受限三层视差、观测光晕与状态反馈；验证 reduced motion、coarse pointer、页面隐藏、无 backdrop-filter 与低性能降级；无证据不引入重动画依赖。
8. [ ] **Stage 7 审计与质量门**：执行设计漂移、无障碍、390/768/1440 浏览器、console/network、刷新/返回/取消/重复提交/失败恢复、体积与动画性能检查；运行 `npm test && npm run build`，证据写入 `research/qa-report.md`。
9. [ ] **Trellis 收尾**：`trellis-check` → 修复 Critical/High 或记录责任人 → 将稳定前端规则沉淀到 `.trellis/spec/frontend/` → 提交、推送、Finish 与归档。

## 提交与回滚粒度

- 规划文档、纵向切片、共享壳层、每个页面扩面、动效/降级与 QA 修复分别提交。
- 任一页面可回退到旧表现层而不改变路由、API 与数据契约。
- 纵向切片未通过 Gate D 时停止扩面并修订设计契约。

## 产物

- `research/ui-inventory.md`
- `research/visual-directions.md`
- `research/prototype.md`
- `research/ui-design.md`
- `research/ui-decisions.md`
- `research/qa-report.md`
