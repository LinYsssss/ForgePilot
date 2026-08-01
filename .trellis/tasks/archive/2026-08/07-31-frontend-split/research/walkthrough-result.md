# 走查结果（2026-07-31，Playwright 自动走查 + 人工截图核验）

- 环境：Vite dev server (localhost:5173) → 代理 → 部署中的后端（真实数据）；Playwright v1.49.1 容器（--network host）。
- 账号：种子管理员（凭据来自仓库外备份，未落盘）。

## 结果：通过

| 步骤 | 结果 |
|---|---|
| 未登录访问 /#/dashboard → 登录页 | ✅ |
| 登录 → app-shell + dashboard（真实项目/报告数据渲染） | ✅ |
| 遍历 7 个导航页，hash 路由逐一核对 | ✅ |
| 刷新浏览器停留在 #/ai-logs（路由收益核验） | ✅ |
| 主题切换 亮/暗 | ✅（截图 10-theme-light.png） |
| 退出登录 → 回登录页 | ✅ |
| 页面错误（pageerror） | 0 |
| 控制台错误 | 仅 2 条预期 401（登录前/退出后的 /auth/me 探测） |

截图：shots/01-login … 11-after-logout（暗色为主，10 为亮色）。
人工核验：02-dashboard、07-reviews、10-theme-light 三张与拆分前视觉一致，无布局回归。

## 合并说明

走查前合并了 origin/fix/track-a-core 的演示仓库重建工作（cd8839e）：
- init-demo-repos.sh 在本机重建 3 个演示仓库，6 个 pinned SHA 全部命中（跨平台确定性成立）。
- 补偿校验：javac(容器) / python3 compile / node --check 全部通过。
- 根 .gitattributes 采用双方并集；2 个新 superpowers 文档按本分支布局落入 docs/archive/。
