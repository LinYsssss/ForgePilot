# Implement：生产加固与清理

> 每阶段独立提交=回滚点；提交遵循仓库既有 message 风格、作者本人署名、无 AI 标记。全程硬边界：不触碰受保护资产；墨境步骤 6/7 落库前不触碰 `frontend/`。

0. [ ] **前置核实**：确认 backend schema 演进机制（flyway/liquibase/ddl-auto，跟随既有模式）；精确普查 sandbox-runner/model-service 注释基数（排除 venv/生成物）；核对 RabbitMqConfig 实际队列名供告警规则用。
1. [ ] **P0 TLS overlay**（独立提交）：`deploy/tls/{nginx-tls.conf,docker-compose.tls.yml,gen-self-signed.sh}` + 部署 README；基础 nginx.conf 仅做 CSP 收紧（移除 Google Fonts 域，单独可回滚）。验证：conf 语法审读、compose YAML python 解析通过；**栈级 https 走通转移服务器验收清单**。
2. [ ] **告警与看板**（独立提交，可拆 2 笔）：rabbitmq `enabled_plugins` 挂载 + prometheus 抓取 job + `alerts.yml` 四条规则 + alertmanager 服务（webhook env 可配，默认空接收）+ grafana provisioning（数据源+一张看板 JSON）。验证：YAML 全部可解析、规则表达式与实测指标名对号；**触发验证转移服务器**。
3. [ ] **反馈闭环 backend**（独立提交）：`ReviewFeedback` 实体+迁移（按步骤 0 核实的机制）+ `POST /api/agent-runs/{runId}/feedback` + `GET /api/feedback/export` + `SecurityAuditLogger` 接入 + 校验矩阵单测（404/403/400/upsert 幂等/导出格式/审计断言）。验证：`mvn -s .mvn/settings.xml verify` 全绿。
4. [ ] **错误上报端点**（独立提交）：`POST /api/client-errors`（匿名+4KB 截断+专用限流预算 `app.ratelimit.client-errors-limit`）+ 单测。验证：mvn verify 绿。
5. [ ] **数据生命周期文档**（独立提交）：`docs/` 下成文——ai_call_log 留存口径、postgres 备份/恢复步骤、compose 卷清单与清理边界（WorkspaceArchiveService 既有归档职责一并写清）。
6. [ ] **冗余清理 backend**（每批独立提交）：候选清单（grep 引用分析+Spring 装配排除）→ 逐批删除 → 每批 mvn verify 绿；「已核未删」保留清单落 `research/redundancy-audit.md`（含 LegacyReviewProjectionService/MockAiReviewClient 保留理由）。
7. [ ] **注释中文化 backend**（按包分 8-10 批，每批独立提交）：252 个无中文文件治理——英文注释重写中文、关键裸奔逻辑补注释、不写废话注释、不动既有中文注释；每批 mvn verify 绿。完成判据：`LC_ALL=C.UTF-8 grep -rlP "\p{Han}"` 复查全含中文或豁免清单成文（纯常量/生成类）。
8. [ ] **尾段（时机门：墨境步骤 6/7 已提交后）**：前端错误上报接线（main.js 挂载 sendBeacon 模块）+ frontend 3 个无中文文件注释治理 + sandbox/model-service 按步骤 0 普查结果同标准治理。验证：npm test + build 绿。
9. [ ] **收尾**：`trellis-check`（Agent）→ `trellis-update-spec` 沉淀（部署 overlay 模式、告警规则规范、注释质量标准）→ 提交推送 → 服务器侧验收清单（TLS 走通/告警触发/grafana 看板）并入 r7/r8 转移清单 → `/trellis:finish-work`。

风险文件：`deploy/nginx.conf`（CSP 收紧——唯一动基础栈的点）、backend finding/patch 域（新实体迁移）、SecurityConfig（新端点放行）。
回滚点：每阶段独立提交；overlay 删文件即回原状；迁移单独成笔。
产物：TLS overlay 三件套、告警四规则+看板、反馈 API+导出、错误上报端点、生命周期文档、冗余审计档、注释治理批次、服务器验收清单。
