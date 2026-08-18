# 全仓代码、生成物与生产就绪审计

> Repository: `LinYsssss/ForgePilot`
> Planning date: 2026-08-18
> User intent: 推送完成后审查全部代码，删除写代码过程中产生且无用途的文件，并确认生产代码是否符合要求、是否存在问题。

## Goal

对 ForgePilot 当前 main 做证据驱动的全仓审计：清除确定可再生且无价值的生成物/缓存/临时残留，修复真实的代码、脚本、配置和部署缺陷，建立防回归门禁，并给出可复核的生产就绪结论。不得以“大文件”“零引用”或命名猜测为由删除演示、评测、迁移或 Trellis 证据资产。

## Confirmed facts

- Fresh ForgePilot clone main=`47a0ed4`，工作树除本任务外干净。
- 已确认唯一 tracked cache artifact：`model-service/tests/__pycache__/test_main.cpython-312-pytest-8.3.4.pyc`。
- `demo-repos/` 的 43 个缺陷、patch/固定 SHA、knowledge-noise 以及 `evaluation/cases` base/head 重复文件均是故意验证资产，必须保留。
- `.trellis/tasks/**` 中的截图、QA、评测和迁移映射是审计证据；除非证明重复且无任何引用，不做体积驱动清理。
- 已确认 smoke auth cookie 契约漂移、verify-local 无 seed admin、Grafana 默认 admin 密码、scan-package-deps 绝对路径四类实际问题。
- 当前主机无 Docker；Docker-dependent 结论必须标记未执行，不得冒充生产验证通过。

## Requirements

### R1 Repository hygiene

- 删除 tracked `.pyc`/缓存/临时/编译产物，并建立全局 Python cache/pytest ignore 规则。
- 审计 tracked ignored files、备份/临时命名、二进制构建物、重复文件和大文件；每一项进入 delete/keep/investigate 清单并附证据。
- 清理只针对确定可再生且不承载产品、测试、研究、迁移或答辩证据的文件。
- 清理当前任务产生的本地 cache/target/dist/node_modules 等 ignored 产物，但不提交本地 Trellis `.developer/.runtime`。

### R2 Executable script correctness

- 修复 `smoke-backend.ps1` 以使用 HttpOnly auth cookie + CSRF，不再读取不存在的 JSON token。
- 修复 `verify-local.ps1` smoke 启动流程，为临时 H2 后端显式 seed 测试管理员并保持凭据只在子进程环境中。
- 让 `scan-package-deps.py` 默认从仓库位置解析路径，并支持显式 override；Windows/Linux 均可运行。
- 更新用户可见 ForgePilot 验证输出，内部冻结标识不做无差别改名。

### R3 Production configuration and security

- 生产 compose 不允许 Grafana 已知默认密码；缺失时 fail-fast。
- 检查 backend prod secret validator、CSRF/cookie、SCM webhook、local-path/insecure HTTP、数据库/RabbitMQ、sandbox signing、Nginx/TLS、observability 和端口暴露边界。
- 检查 Dockerfiles、Compose、CI、dependency manifests、lockfile registry、npm audit/Maven/Python/Trivy coverage；发现有修复版本的 HIGH/CRITICAL 风险必须处理。
- 不恢复已移除的 model-service 生产部署；历史目录保留与否按现有架构决策审计。

### R4 Full code review

- 覆盖 backend、frontend、sandbox-runner、scripts、deploy、evaluation、CI 和活跃文档。
- 重点检查授权、输入/路径校验、事务、异步/outbox/MQ、重试/幂等、错误处理、资源生命周期、日志脱敏、API shape、SSE/poller 清理和前端无障碍。
- 死代码删除必须同时具备引用分析、运行路径/反射/框架入口审计和测试证据；零文本引用不是删除依据。
- 每个 CRITICAL/HIGH/WARNING finding 必须有实际 file:line、失败/攻击路径和验证方式；未证实项降为 investigate。

### R5 Verification and reporting

- 运行 backend、sandbox-runner、frontend、Python/model-service（若历史模块仍有独立测试）、evaluation selftest、脚本语法、Nginx headers、dependency/security checks。
- Docker 不可用时明确列出未执行项和部署环境复验命令。
- 形成删除清单、保留清单、修复清单、未解决风险和生产就绪结论。
- 所有改动在 ForgePilot 新仓库提交并推送；P8 真实模型矩阵状态不得误报。

## Out of scope

- 删除或修复 `demo-repos/` 故意缺陷、patch、knowledge-noise。
- 删除 `evaluation/cases` 为表达 diff 所需的相同 base/head 文件。
- 仅为减小仓库体积删除 Trellis QA/迁移/评测证据。
- 全局重命名 Java package、数据库/MQ/metrics 内部标识。
- 在没有 Docker/真实模型凭据时宣称对应链路已通过。

## Acceptance Criteria

- [ ] tracked cache/build/temp artifact 扫描为零；已确认 `.pyc` 删除且全局 ignore 防回归。
- [ ] smoke-backend 使用 cookie/CSRF，verify-local 可在 fresh H2 启动下完成 smoke。
- [ ] package dependency scan 在仓库任意安装路径和 Windows/Linux 上可运行。
- [ ] production Grafana 密码无默认弱值，配置缺失会明确失败。
- [ ] 全仓 findings 均完成修复、证伪或以证据记录为剩余风险。
- [ ] backend/sandbox/frontend/evaluation/script/CI/安全相关可运行门禁通过；不可运行项有准确原因与复验命令。
- [ ] demo/evaluation/Trellis/migration 证据资产完整，无误删。
- [ ] 最终报告可证明生产就绪范围与未验证边界，改动提交并推送 ForgePilot。

## Blocking open questions

无。删除策略采用保守证据门槛：不确定即保留并进入 investigate，不在清理阶段猜测删除。
