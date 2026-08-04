# Implement: Project reality audit

## Ordered checklist

1. [ ] 环境探测：`docker compose version`、`java -version`、`node -v`、`python --version`，记录基线。
2. [ ] 启动 Layer 1 实测（后台并行）：
   - [ ] `cd backend && mvn -s .mvn/settings.xml test`（后台，耗时最长，最先启动）
   - [ ] sandbox-runner 测试（确认其构建工具后运行）
   - [ ] `cd frontend && npm install --cache .npm-cache && npm test && npm run build`
   - [ ] `pwsh -File scripts/init-demo-repos.ps1 -Verify`
   - [ ] model-service：安装依赖 + `python scripts/train_model.py --version audit-check` 冒烟
3. [ ] Layer 2 口径比对（与实测并行）：
   - [ ] README 核心特性 ~14 条 → 实现定位表
   - [ ] PR 守门 Agent 安全边界声明逐条对代码（webhook 验签、沙箱参数、工具白名单、补丁审批、密钥不下发）
   - [ ] API 速查表 vs Controller 路由与权限注解
   - [ ] 配置项表默认值 vs application.yml / .env.example
   - [ ] docs 01–12 主要断言抽查
4. [ ] Layer 3 缺陷扫描（并行子代理分工）：backend / frontend+契约 / model-service+sandbox-runner / deploy+scripts 四路。
5. [ ] 汇总：收集实测结果，合并三层发现，与演示素材故意缺陷及已归档已知问题去重。
6. [ ] 定级 P0–P3，写 `audit-report.md`（发现清单 + 逐条声称核对表 + 实测数字对照 + 路线图）。
7. [ ] 交付审计报告摘要给用户，等待用户挑选路线图条目再开修复任务。

## Validation commands

- 报告完整性自查：四张声称清单每条有结论；每条失实/缺陷有证据锚点。
- `git status --short` 确认未触碰产品代码（只有任务目录产物与 gitignore 构建产物）。

## Risky files / rollback points

- 无产品代码改动。唯一写入点：`.trellis/tasks/08-03-project-reality-audit/`。
- 实测产生的 target/、node_modules/、模型文件均在 gitignore 内，可清理。

## Before task.py start

- [x] prd.md 收敛完成（无阻塞开放问题）
- [x] design.md / implement.md 就绪
- [ ] 用户对最终规划摘要的明确批准（待）
