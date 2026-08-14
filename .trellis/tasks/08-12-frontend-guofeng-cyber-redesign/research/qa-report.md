# QA report

> 本报告记录 Stage 3 交互原型的真实浏览器验证。它通过原型/设计合同阶段门，但不替代生产 Vue 实现的 Stage 7 终验。

## 2026-08-12 — Stage 3 prototype final

### Environment

- Artifact: `research/prototype-app/`
- Server: Python 3.13.3 `http.server`，`127.0.0.1:4178`
- Browser: Google Chrome 151.0.7922.109，CDP headless 真实渲染
- Node syntax runner: v24.13.0（仅原型 JS 语法与 QA 脚本；生产构建仍必须使用 Node 22）
- Production frontend: 未修改

### Evidence

机器可读报告：

- `research/qa/browser-report.json` — 登录、Finding、错误恢复、static、Dialog、三档布局与 reduced motion。
- `research/qa/browser-regression.json` — 抽屉 inert/aria-hidden、重复提交、控制台回归。
- `research/qa/browser-breakpoint-report.json` — 1440/1279/768/390 最终断点度量。
- `research/qa/browser-final-mobile.json` — 移动滚动后的 sticky topbar、朱批入口和审批区遮挡回归。

关键截图：

- `login-1440.png` / `login-390.png`
- `workspace-1440.png` / `workspace-1279.png` / `workspace-768.png` / `workspace-390.png`
- `workspace-768-rail.png` / `workspace-390-nav.png` / `workspace-390-rail.png`
- `workspace-390-static.png` / `workspace-390-reduced.png` / `workspace-390-approval.png`

### Responsive results

| Viewport | Expected | Result |
| --- | --- | --- |
| 1440×1000 | 案卷 / 主纸面 / 朱批三栏 | Pass；实测 236 / 912 / 292px，无页面级横向溢出 |
| 1279×900 | 平板合同临界点；案卷常驻，朱批抽屉 | Pass；案卷 218px、主纸面 1061px、朱批 inert 关闭 |
| 768×1024 | 案卷常驻，朱批抽屉 | Pass；案卷 218px、主纸面 550px、上下文标题可见 |
| 390×844 | 单卷流；案卷与朱批双抽屉 | Pass；无页面级横向溢出，Diff 在 336px 容器内局部滚动 720px 内容 |

### Interaction and accessibility results

- Login empty submit: error alert visible, focus lands on first missing field, both fields receive `aria-invalid`.
- Demo login: fills deterministic credentials; submit becomes disabled + `aria-busy=true`, preventing duplicate submission.
- Finding selection: F-002 updates title, file, description and selected state.
- Error/retry: approval disabled during error and restored after retry.
- Offline/recovery: loaded content remains readable and approval is disabled until recovery.
- Static mode: `aria-pressed=true`; ambient animations and particle RAF stop while content remains complete.
- Reduced motion: media query matches; animation duration collapses and ambient transforms reset.
- Drawer: mutually exclusive, scrim-backed, Escape dismissible, focus enters close button, hidden drawers use `inert` + `aria-hidden`, Tab is trapped while open.
- Dialog: focus enters dialog, Tab is trapped, Escape closes, body lock clears, focus returns to approval trigger.
- Touch targets: interactive controls used on touch layouts are at least 44×44px.
- Console/network: final browser passes report zero warnings/errors and no missing resource request.

### Issues found and fixed during QA

1. **Tablet breakpoint drift** — 768px previously collapsed the left index despite the contract. Fixed by separating 768–1279 tablet behavior from ≤767 mobile behavior.
2. **Hidden drawer focus leakage** — off-canvas controls could remain tabbable. Fixed with responsive `inert`/`aria-hidden` and drawer focus loop.
3. **Missing drawer modality** — added shared scrim, body scroll lock, mutual exclusion and focus return.
4. **Mobile annotation trigger collision** — floating button overlapped progress/content. Moved into sticky mobile topbar.
5. **Mobile toast/action collision** — moved toast above the sticky approval safe area.
6. **Touch target undersizing** — normalized key controls to 44px minimum.
7. **Login duplicate submission** — added disabled/`aria-busy` state and missing-field focus/validation semantics.
8. **Static server favicon 404** — added an inline empty favicon.
9. **Contract breakpoint mismatch** — aligned CSS/JS to desktop ≥1280, tablet 768–1279, mobile ≤767.

### Readiness

- Stage 3 prototype gate: **Passed**.
- Stage 4 UI design contract: **Frozen at v1.0**.
- Residual Critical/High: **None for the prototype scope**.
- Production Stage 7: **Not started**; must rerun under Node 22 against the Vue implementation, real API states, route guards, permissions, long content, performance and bundle budgets.
