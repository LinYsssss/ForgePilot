# Prototype：墨境书院 Agent 审查工作台

> **状态：Stage 3 已实现，待用户视觉/交互评审与浏览器截图补证。** Stage 2 方向选择已完成；UI design contract 仍为 v0.2 草案，尚未冻结。

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
3. 案卷索引定位 Agent 审查。
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

CSS 断点使用 1180 / 880 / 560px，分别控制三栏收束、移动壳层和窄屏内容重排；验收仍以 1440 / 768 / 390 三个视口为准。

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

## Pending review / evidence

- 自动浏览器连接在初始化模块时连续超时，因此本轮未生成 1440/768/390 实际截图，也未取得 console、focus order 与点击回放证据。
- 用户需先评审原型的层级、密度、墨迹强度、交互路径和移动重排。
- 浏览器恢复后必须补三档截图、normal/static/reduced 对照、键盘焦点、Dialog 焦点归还、console/network 与操作回放。
- 以上补证与用户评审完成前，不将 Stage 3 标记为通过，也不将 UI design contract 冻结为 v1.0。

## User review checklist

- 最高风险、证据位置和下一步动作能否在 10 秒内辨认？
- 宣纸工作面是否足够清爽，水墨是否太淡或太重？
- 左侧案卷、中央审查、右侧朱批的比重是否合理？
- Finding → Diff → 审批是否顺手？
- 朱砂红与冷青是否既有古风感又保留了技术状态感？
- 移动端是否应进一步减少风险卡片或批注信息？
