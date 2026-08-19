# 步骤 0 实施基线（2026-08-13）

## 环境与分支

- 分支：`codex/frontend-ink-prototype`（**用户 2026-08-13 明确豁免独立分支要求**，指示直接在当前分支开工；R7 未提交工作已于 cfb8b4d/3ff90bc 安全落盘并推送，原隔离动机已消除）
- Node `v24.13.0` / npm `11.6.2`。package.json engines 要求 `>=22 <23`，无 engine-strict，npm 仅警告。PRD 已声明本机 Node 24 为已知约束：**日常开发在 Node 24 进行，正式构建与最终验收（步骤 9 质量门）须在 Node 22 复跑**。

## 旧 UI 基线数字（Element Plus 版，重构对照基准）

- `npm ci`：成功（exit 0）。
- `npm test`（node 内置 runner）：**21 pass / 0 fail**，duration ≈ 4.7s。
- `npm run build`（Vite 6）：成功，9.70s，1735 modules。
  - `dist/index.html` 0.65 kB（gzip 0.48 kB）
  - `dist/assets/index-*.css` **234.45 kB**（gzip **32.45 kB**）
  - `dist/assets/index-*.js` **540.88 kB**（gzip **186.46 kB**）
  - 警告：单 chunk >500kB（Element Plus 全量进主包，无代码分割）——新架构需以路由级拆分/按需消化，步骤 9 用本节数字对照。
  - 另有 `@vueuse/core`（element-plus 传递依赖）的 `#__PURE__` 注释位置警告 ×2，无功能影响。

## 已声明缺口（不虚报）

- **旧 UI 截图 / console·network 记录 / 关键路径实录**：本机无可运行后端栈（无 Docker）且本会话无浏览器工具，未采集；r6 归档亦无现成截图。转入步骤 1（Stage 1 证据补齐）在具备可运行栈的会话完成；步骤 9 质量门会对新 UI 全量重测，不影响本步骤的数字基线有效性。
