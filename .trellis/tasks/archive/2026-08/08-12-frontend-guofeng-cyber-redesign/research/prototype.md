# Prototype：墨境书院 Agent 审查工作台

> **状态：Stage 3 已通过。** 用户在 2026-08-12 确认继续；真实 Chrome 三档响应式、交互、焦点与降级证据已补齐，UI design contract 已冻结为 v1.0。

## Artifacts

- 登录原型：`research/prototype-app/login.html`
- 工作台原型：`research/prototype-app/index.html`
- 样式：`research/prototype-app/styles.css`
- 交互：`research/prototype-app/app.js`
- Stage 2 比较图：`research/assets/visual-directions-comparison.png`

本地预览：

```powershell
python -m http.server 4178 --bind 127.0.0.1 --directory .trellis/tasks/08-12-frontend-guofeng-cyber-redesign/research/prototype-app
```

打开 `http://127.0.0.1:4178/login.html`，使用演示账户进入工作台。

## Demonstrated flow

1. 登录页演示空字段错误、组织账号、演示账户和登录→工作台入口。
2. 案卷索引定位 Agent 审查。
3. 查看风险摘要与六步笔触进度。
4. 在 Finding Ledger 切换 F-001/F-002/F-003。
5. Finding 同步更新 evidence 元数据、说明与稳定 Diff 平面。
6. 朱批栏可定位 F-001 证据。
7. “批准并落印”进入确认对话框，可返回或确认；确认后播放一次性落印并显示成功反馈。
8. “退回修改”提供明确反馈。
9. “演示错误”显示验证失败和“重试失败步骤”；重试后恢复正常。
10. “演示离线”保留已加载内容和“恢复连接”路径。
11. “静态墨境”可人工关闭全部持续环境动效。

## Responsive behavior

- 1440：案卷索引 / PaperWorkspace / 朱批栏三栏。
- 768：案卷索引 + PaperWorkspace；朱批栏变为可访问抽屉。
- 390：单卷任务流；案卷与朱批均为抽屉，风险、Finding、Diff、审批按任务顺序重排，操作区进入移动安全区。

CSS 冻结断点使用 1279 / 767 / 560px：1280px 起三栏；768–1279px 保留案卷索引并将朱批转抽屉；≤767px 案卷与朱批均转抽屉；≤560px 进一步压缩内容与固定移动安全操作区。验收以 1440 / 768 / 390 为主，并补 1279px 临界点。

## Motion modes

- Normal：纸面呼吸、远山 3px、墨雾 6px、近景笔触 10px 指针视差；入场晕染、笔触进度、一次落印。
- Static toggle：停止环境循环和视差，保留信息与操作。
- Reduced/coarse：媒体查询强制静态构图；页面隐藏/窗口失焦时指针变量归零。
- Unsupported blur：使用近实色 topbar、案卷栏、朱批栏和反馈层。

## Automated checks performed

- `node --check research/prototype-app/app.js`：通过。
- HTTP：`index.html` / `styles.css` / `app.js` 均返回 200。
- DOM：39 个 ID，无重复。
- 交互：28 个 JS ID 引用，无悬空目标。
- 合同：响应式断点、`prefers-reduced-motion`、`pointer: coarse`、无 `backdrop-filter` 回退、ambient `pointer-events:none` 与单一 RAF 指针更新均存在。
- Git：任务目录 `git diff --check` 通过。

## Revision — v0.3 淡墨云雾

- canvas/paper 从偏黄宣纸调整为更浅的云纸灰与暖白纸面。
- 远山 opacity 降至 0.045 / 0.028，blur 提升至 5 / 9px，位移收紧为 2 / 3px。
- 增加三层柔白云带，38–54 秒低幅漂移；指针影响只为 2–3px。
- 墨雾由深色烟团改为白雾夹少量青灰，blur 28px。
- 纸纹 opacity 降到 0.11，环境笔触降到 0.045。
- 新纸面下最低候选文本/状态对比度为 5.24:1。

## Revision — v0.4 太极水墨、墨粒与登录门禁

- 新增 `login.html` / `login.js`：组织账号、密码、错误、演示账户、账号恢复和登录→工作台入口。
- 登录页用大幅淡墨太极建立品牌焦点；工作台使用低透明太极水印。
- 登录页与工作台都加入原生 Canvas 墨粒：约 30–64 个，DPR ≤ 1.5，松烟墨为主、极少量朱砂。
- static/reduced/coarse/hidden/unfocused 时停止粒子 RAF，并保留少量静态墨点。
- 禁止太极和颗粒进入表单、Diff、表格、焦点或状态语义层。

## Browser evidence and review result

- 用户于 2026-08-12 回复“继续”，视为对当前视觉方向、信息密度和交互路径的继续授权；不重开 Stage 2。
- Chrome 151.0.7922.109 真实渲染通过 1440×1000、1279×900、768×1024、390×844；所有视口 `documentElement.scrollWidth <= innerWidth`。
- 1440 实测三栏宽度为 236 / 912 / 292px；1279 与 768 均保留案卷索引并将朱批变为抽屉；390 为双抽屉单卷流。
- 390 下 Diff 容器宽 336px、内容宽 720px，采用明确的局部横向滚动，不造成页面级横向溢出。
- 抽屉关闭时使用 `inert` + `aria-hidden` 移出键盘顺序；打开后焦点进入关闭按钮，遮罩点击/Escape 关闭并归还触发器，抽屉内 Tab 循环。
- Dialog 打开后焦点进入“返回复核”，Escape 关闭并归还“批准并落印”；错误→重试、Finding→Evidence、static/reduced、登录校验与重复提交防护均通过回放。
- 控制台/页面异常为 0；内联空 favicon 消除了静态服务器 404。
- 截图与机器可读报告位于 `research/qa/`；结论详见 `research/qa-report.md`。

## Stage 3 exit

- **通过。** 最高风险、证据和主动作在首屏层级明确；水墨未侵入语义平面；三档响应式和 normal/static/reduced 均有证据。
- 原型阶段未发现 Critical/High 残留问题；UI contract 冻结为 v1.0，后续差异按 drift gate 处理。
