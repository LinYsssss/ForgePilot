# Design: Project reality audit

## Approach: 三层证据体系

审计结论必须可复核，按证据强度分三层，逐层收集后汇总：

### Layer 1 — 实测层（最强证据）

在本机直接运行可重复的验证命令，产出与 README 声称基线的对照：

| 目标 | 命令 | 对照声称 |
| --- | --- | --- |
| 后端测试 | `cd backend && mvn -s .mvn/settings.xml test` | 190 通过 / 3 跳过 |
| sandbox-runner 测试 | 其模块内测试命令（进目录后确认构建工具） | 37 通过 |
| 前端测试+构建 | `cd frontend && npm test / npm run build` | 4 测试 + 构建通过 |
| model-service | 依 requirements 安装后 `python scripts/train_model.py` 冒烟 | 可训练可启动 |
| demo-repos | `pwsh -File scripts/init-demo-repos.ps1 -Verify` | 6 ref SHA 一致 |
| Docker 探测 | `docker compose version` | 决定容器类声称能否本机验证 |

失败/数字不符 → 直接构成发现；环境缺失 → 标"本机无法验证"。

### Layer 2 — 声称-实现比对层

以 README 为主索引，把每条能力声称映射到实现代码并核对：

- 核心特性清单（README「核心特性」~14 条）→ 定位实现类/配置。
- PR 守门 Agent 安全边界声明（验签、沙箱参数、无 scm.publish、密钥不下发 Runner、补丁必审批）→ 逐条对代码。
- API 速查表 → 与 Controller 实际路由/方法/权限注解比对。
- 配置项表（默认值列）→ 与 `application.yml` 及 `deploy/.env.example` 比对。
- docs/ 主要文档（01/02/03/04/05/08/11/12）抽查关键断言与代码一致性。

已在 README「诚实声明」中明示的限制不计为失实，但要核对声明本身是否仍准确。

### Layer 3 — 代码缺陷扫描层

按模块并行扫描（可用 Explore/general-purpose 子代理分工），重点：

- backend：鉴权/越权边界、事务与 Outbox 一致性、并发（状态机竞态）、限流实现、加密存储、webhook 验签实现细节、异常路径。
- frontend：与后端契约漂移（分页信封、字段名）、错误处理。
- model-service：接口契约、模型文件加载失败路径。
- sandbox-runner：签名校验、命令白名单、路径围栏的实现与声称一致性。
- deploy：Compose 服务定义 vs 文档、.env.example 完整性、init.sql/Flyway 分工。
- scripts：verify-local / smoke-backend / init-demo-repos 在当前仓库状态下是否真能跑。

## 汇总与去重

- 与 `docs/演示素材与缺陷对照表.md` 中**设计内故意缺陷**（演示素材）区分开，不计入项目缺陷。
- 与已归档任务/已知问题去重（.trellis/tasks/archive、docs/archive）。
- 每条发现按 P0–P3 定级：
  - P0：失实声称（简历风险）或阻断本地演示的缺陷
  - P1：安全/一致性实际缺陷、明显口径偏差
  - P2：边界条件、契约漂移、文档陈旧
  - P3：改进机会
- 报告落盘任务目录 `audit-report.md`，路线图按优先级+工作量档位排列。

## Risks / Operational notes

- 后端全量测试耗时可能较长（分钟级）→ 后台运行，期间并行做 Layer 2/3。
- 本机大概率无 Docker（README 亦如此暗示）→ Testcontainers 跳过属预期，容器安全类声称记"未验证"，不判失实。
- 审计只写任务目录，不改产品代码；实测会产生构建产物（target/、node_modules 等），均为 gitignore 内容。
- model-service 训练脚本会写本地模型文件（gitignore 内），可接受。

## Rollback

无产品代码改动，无需回滚；实测产物可随时清理。
