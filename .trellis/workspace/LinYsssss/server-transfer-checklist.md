# 服务器侧转移清单（跨任务合并版）

> **一个入口**：需要真实运行环境（Docker / 真实模型 / GPU 或长跑）的事项全在这里，
> 分散在三个任务里的验收合并成一张表，按顺序执行即可。
>
> 更新于 2026-08-14。本机（Windows，无 Docker、无真实模型额度）已把所有**能在本机完成的**
> 部分做完并落库；下列项目**未执行、也不假装执行**。

---

## 为什么这些只能在服务器做

| 障碍 | 影响的事项 |
|---|---|
| 本机无 Docker | TLS 栈、告警触发、Grafana 看板、V28 真实 Postgres 迁移、镜像扫描复验 |
| 本机无真实模型额度 | r7 基线复跑、r8 各阶段评测对比 |
| Windows 无符号链接特权 | sandbox-runner `symlinkEscapingArchiveRootIsRejected`（Linux CI 已覆盖，非阻塞） |

---

## A. 生产加固运行时验收（08-13）

明细见 `.trellis/tasks/08-13-production-hardening/research/server-acceptance-checklist.md`，
那里有逐条命令。摘要：

- [ ] **A1 TLS overlay 走通**：`./tls/gen-self-signed.sh <host>` →
      `docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml up -d` → https 可达、HSTS 生效
- [ ] **A2 告警触发**：制造一次 DLQ 堆积与一次服务 down，确认四条规则真的 fire 到 alertmanager
      （**重点核对 `sandbox.dead.queue` 也在告警覆盖内**——它由 sandbox-runner 声明，
      规划期只查 backend 时曾三处漏配）
- [ ] **A3 Grafana 看板**：数据源自动 provision，总览看板有数据
- [ ] **A4 V28 迁移**：真实 Postgres 上跑通 `V28__review_feedback.sql`，
      含两条部分唯一索引（`uq_review_feedback_finding_reporter_type` /
      `uq_review_feedback_miss_location`）的并发行为抽验
- [ ] **A5 反馈闭环端到端**：提交三型反馈 → `GET /api/feedback/export` 导出 NDJSON，
      **确认中文 note 未被损坏**（该编码缺陷已修，此处是回归确认）
- [ ] **A6 错误上报**：前端制造一次未捕获异常，确认 `POST /api/client-errors` 收到并落日志，
      且限流预算生效（默认 10/分/来源）

## B. r7 评测基线（08-03-r7）

- [ ] **B1** 38 例基线在服务器复跑，数字落 `baseline-*.json/md`
- [ ] **B2** 复跑后走 r7 步骤 8 收尾（check → spec → 提交）

## C. r8 提示词调优（08-03-r8）

r8 的每一步都以「评测对比」为准入，因此全部依赖 B1 的基线数字：

- [ ] **C1** 步骤 0 前置确认（基线在档、prompt 规范可注入、真实模型配置可用）
- [ ] **C2** R1 分层模板 → 评测对比
- [ ] **C3** R2 类型化清单**门禁复评**（代码已提交于 `a9e7c3b`，
      **门禁未过前不得对外做效果声明**）
- [ ] **C4** R3 两段式复核 → 评测对比（重点：误报率下降、漏报率不动）
- [ ] **C5** R4 动态 few-shot → 评测对比
- [ ] **C6** 终版全量评测 vs r7 基线，数字落 `eval-runs/final.md`；
      README / 简历素材**只引用该文件**
- [ ] **C7** 漏报回灌流程演练一次（可直接用 A5 导出的真实反馈作为输入）

## D. 供应链复验（随时可做）

- [ ] **D1** 上游发布带 Go ≥ 1.26.6 的 `docker:*-cli` 后，升级
      `sandbox-runner/Dockerfile` 的 docker-cli 阶段，并删除 `.trivyignore` 里那两行。
      **该抑制 2026-11-01 到期后会自动恢复拦截**，届时 CI 变红即是提醒。

---

## 执行约定

1. 每完成一项，把**命令输出 / 截图路径 / 日期**回填到对应任务的档案里，而不是只勾选。
   能力表述必须能追溯到实测产物。
2. A 与 B 互不依赖，可并行；C 全线依赖 B1。
3. 任何一项没做，就不要在 README / 简历里出现对应的效果数字——
   见 `.trellis/spec/guides/demo-assets-and-claims.md`。
