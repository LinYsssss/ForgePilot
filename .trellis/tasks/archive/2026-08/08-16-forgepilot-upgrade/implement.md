# ForgePilot 平台升级 — 执行路线图(父任务)

> 本文件是父任务的阶段地图与执行规则。每个 Phase 进场时创建子任务承接实现:
> `python ./.trellis/scripts/task.py create "<Phase 标题>" --slug <pX-slug> --parent .trellis/tasks/08-16-forgepilot-upgrade`
> 子任务自带 prd(从本文件对应 Phase 摘录细化),复杂子任务补 design/implement。
> 工作流偏好:极简过程检查,每 Phase 末全量验证 + 合批提交(用户既定工作流)。

## 0. 全局验证命令

- 后端:`cd backend && mvn -s .mvn/settings.xml verify`
- 前端:`cd frontend && npm test && npm run build`
- 一键:`pwsh scripts/verify-local.ps1 -SkipSmoke`(全仓可重复构建与测试)
- 评测:`pwsh scripts/run-agent-evaluation.ps1`(temperature=0 与镜像摘要断言内置)
- 提交信息含中文:先 Write 落文件再 `git commit -F <file>`(本机 shell 非 UTF-8)

## 1. 阶段总表

| Phase | 内容 | 依赖 | 周当量 | 可裁剪 |
|---|---|---|---|---|
| P0 | 架构清理:model-service 下线 | — | ≈1 | 否 |
| P1a | 成员与 3 角色 RBAC | P0 | 2-2.5 | 否 |
| P1b | Requirement + AC 域 | P1a(指派依赖成员) | 1.5-2 | 否 |
| P2 | 需求质量检查(体检) | P1b | 1.5-2 | 否 |
| P3 | 需求-代码关联 | P1b(与 P2 顺序可互换) | 1.5-2 | 否 |
| P4 | 需求一致性 PR 审查 | P2(Context 场景化)+ P3(PR→需求解析) | 2-3 | 否 |
| P5 | Finding 闭环 + 门禁扩展 | P4 + P1a | 2.5-3 | 否 |
| P6 | 研发助手 | P2 | 1.5-2 | **是(D5)** |
| P7 | 工作台 + 度量 + 前端收尾 | P1-P5 数据模型 | ≈2 | 部分(度量可简化) |
| P8 | 实验与答辩(功能冻结) | L 线 + P4(+P5 供方案 D) | 3-4 | 否 |
| P9 | 品牌收尾:仓库改名 | P8 | ≈0.5 | 可延后 |
| L | 语料线(并行) | P1b 定 schema 后启动 | 1.5-2 摊销 | 底线 ≥28 例 |

关键路径:P0 → P1a → P1b → P2 → P4 → P5 → P8;L 线自 P2 起每周 2-4 例,与 P4 产物汇合于 P8。
裁剪阶梯(时间挤压时依序):砍 P6 → P7 度量页做简版 → P9 仅改 README。
最小可守线:P0–P5 + P8。

## 2. 各 Phase 定义

### P0 架构清理(子任务 1 个)

- 删 `ReviewProcessor` 对 `ModelRiskClient` 的调用与降级分支;删 `model` 包 4 类与配置项。
- 清理 compose / CI job / docs(08 配置清单、README 架构图)/ `.env.example`;
  `model-service/` 目录保留 + `.trellis/spec/model-service/` 标 archived。
- **不做改名**(D2/D7):产品名切换在 P8 前,仓库改名在 P9。
- 退出:A13 全绿(CI 三 job 含 supply-chain);回滚点 = 单独合批提交。

### P1a 成员与 RBAC(子任务 1 个)

- V29 `project_member` + owner 回填 LEADER;`ProjectAuthorization` 按 design §2 演进
  (requireRead→成员;新增 requireRole;两步收紧写路径)。
- 17 个 Controller 逐组过动作-角色矩阵,每组配授权矩阵负面用例。
- 前端:项目设置内成员管理(墨境),角色下拉、移交负责人二次确认。
- 退出:A1 通过;单人项目行为零回归(存量用例全绿)。

### P1b Requirement 域(子任务 1 个)

- V30+ `requirement` / `acceptance_criterion`;状态机 + 守卫 + 新 ErrorCode;REQ 取号。
- 前端:新建 `/requirements` 列表 + 详情骨架(墨境),创建/编辑/指派/状态操作。
- 同步定稿语料 schema 扩展字段(design §13),L 线开工前置。
- 退出:A2 通过;需求 CRUD + 指派 + 状态机全链路可演示。

### P2 需求质量检查(子任务 1 个)

- Context Builder 统一入口 + REQUIREMENT_CHECK 场景模板(注册表);确定性规则层;
  报告表 + 落库 + 需求详情内嵌报告 UI。
- 退出:A3 通过(mock 与真模型双路径);模板过 golden 测试。
- **L 线启动**:manifest schemaVersion 递增 + 首批 4-6 例标注。

### P3 需求-代码关联(子任务 1 个)

- `requirement_link` + 三类提取器 + 手动关联端点;需求详情关联视图;四问查询。
- 退出:A4 通过;提取器对 demo-repos 演示仓库有界扫描无性能回归。

### P4 需求一致性审查(子任务 1-2 个)

- coverage schema + 模板 + 合并阶段单独判定(design §5/§7);feature flags 预埋(实验共用路径)。
- 报告 coverage 区块 + AgentRun 时间线步骤;`/reviews` + `/agent` 迁墨境合并为智能审查区,
  `/pull-requests` 迁墨境并入仓库区。
- 退出:A5 通过;flags 单测覆盖五臂组合;旧页迁移可逐页回退。

### P5 Finding 闭环 + 门禁(子任务 1-2 个)

- finding 扩展迁移 + 状态机 + 指派 + fix commit 关联;身份匹配(指纹→LLM 辅助→人工,design §8);
  自动复审建议;GateDecision 输入扩展 + PASS/WARN/BLOCK(design §9)。
- 前端:新建 `/quality` 质量中心(列表/详情/流转/指派)。
- 退出:A6 + A7 通过;§31 场景闭环段(Finding→修复→复审→验证→门禁 PASS)可演示。

### P6 研发助手(子任务 1 个,可降级)

- 上下文预取 + SSE 问答 + 需求详情内嵌;无工具、无写通路(design §10)。
- 退出:A8 通过;降级预案:整段不做时需求详情隐藏入口,零残留。

### P7 工作台 + 度量 + 前端收尾(子任务 1-2 个)

- dashboard 内容改造(三列表 + 风险);新建 `/metrics` 四组指标;`/ai-logs` 并入;
  `/knowledge` 迁墨境(如有独立聊天入口则移除);`/ink` 与 `/dashboard` 归一。
- 退出:A9 + A10 通过;全站墨境统一,旧壳组件无引用后删除。

### P8 实验与答辩(子任务 1-2 个;**冻结新增业务功能**)

- 语料验收(38 例或底线 28 例,同集);五臂跑批 + 两率 + AC 命中率 + token/耗时;
  38 例新基线一并复跑;结果表格与分析。
- **UI/文档产品名切换为 ForgePilot(截图前)**;演示脚本双路径固化(在线 webhook / 离线注入);
  系统截图、答辩案例、论文数据打包。
- 退出:A11 + A12(截图部分)通过;演示脚本离线彩排一次成功。

### P9 品牌收尾(子任务 1 个,时点用户定)

- GitHub 仓库改名 + 徽章/链接修复 + 简历同步提醒(旧链接自动重定向,风险低)。
- 退出:A12 全部通过。

## 3. L 语料线(并行工作流)

- 节奏:P2 起每周 2-4 例(约 2-3h/周),每例 ≤1h;进度记入本任务 journal。
- 每例产出:requirement(标题/背景/描述)+ AC 列表 + AC 真值 + 既有缺陷标注核对。
- 优先级:development 集优先(供 prompt 调优),holdout 集后补且不用于调优(防泄漏,现有纪律)。
- 底线(风险对策):≥28 例(dev 20 / holdout 8)时可冻结,五臂仍同集比较,论文如实声明口径。

## 4. 执行纪律

- 每 Phase = 一个可停靠点:全量验证绿(§0 命令)+ 合批提交(中文提交信息走 Write + `-F`)。
- 子任务创建后先补 `implement.jsonl` / `check.jsonl` 场景条目(从父任务清单裁剪 + 追加该 Phase 特有 spec),
  再 `task.py start`。
- 实现偏离 design.md 的决策:先回写 design.md 再动代码;学到的可复用约定走 trellis-update-spec 入 `.trellis/spec/`。
- 门禁红线:冻结契约变更必须带契约测试;prompt 变更必须过模板注册表 + golden 测试 + 评测门禁
  (prompt-management 五规则)。
