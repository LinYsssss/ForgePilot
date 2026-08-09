# 修复批2：沙箱归档断链(F-03/F-04/F-05)

> 审计依据：audit-report.md F-03/F-04/F-05。这是 PR 守门 Agent 端到端可用的前提，也是全路线图技术含量最高的一批。F-03 与 F-04 是同一条断链的两截，**必须同批修复**，否则修完一半链路依然是断的。

## Goal

后端产出的沙箱归档引用能被 Runner 接受并解析到真实存在的归档文件；`SANDBOX_TOOL_IMAGE` 配置到位；用契约测试锁死该链路防止再次漂移。

## Requirements

### R1（F-03）统一归档引用契约

现状（必然失败）：

- 后端 `RepositoryArchiveRefResolver` 产出 `workspace://agent-run-{id}-{sha}.tar`
- Runner `WorkspaceArchiveResolver.java:26-28` 对含 `:` 的引用无条件抛 `SecurityException`
- 后端 `InputValidation.requireArchive`（`InputValidation.java:8-13`）不检查 scheme，放行

修法方向见 `design.md`（推荐方案 C：共享引用编解码器）。无论选哪个方案：

- 两侧 `SandboxJob` record 字段序保持一致（`workspaceArchiveRef` 第 2 位，审计已逐字段核对）。
- **必须新增契约测试**：用后端真实产出格式驱动 Runner 校验/解析（audit 指出 Runner 现有 14 个测试全用裸文件名 `"repo.zip"`，从未测过真实格式——这正是缺陷逃逸的原因）。

### R2（F-04）明确归档的生产者与移交路径

- 现状：`deploy/docker-compose.yml:108` 中 `sandbox_archives` 卷仅被 sandbox-runner 以 `:ro` 挂载，backend 未挂载，仓库内无任何写入方——引用即使格式合法也解析不到文件。
- 修法方向见 `design.md`。验收以真实文件落卷、Runner `Files.isRegularFile` 通过为准。

### R3（F-05）补齐沙箱镜像配置并转为快速失败

- `deploy/.env` 补 `SANDBOX_TOOL_IMAGE=<镜像>@sha256:<digest>`（`EvaluationCorpusService.java:26` 强制 digest 格式；`.env.example` 已有该项模板）。
- 将该项纳入启动期校验（并入 `ProdSecretValidator` 或同等机制）：生产 profile 下缺失即启动失败，消除当前"返回 `ENVIRONMENT_INCOMPLETE` 软失败、health 仍 UP、缺口不可见"的状态。dev/mock profile 不受影响。

## Out of Scope

- 沙箱容器安全参数调整（审计确认 8 项全部属实，勿动）；对象存储等更大架构改动（除非 design 评审选中）。

## Acceptance Criteria

- [ ] 契约测试存在且通过：后端产出格式 → Runner 接受；恶意格式（`..`、`\`、绝对路径、scheme 伪造）→ Runner 拒绝。
- [ ] 部署服务器端到端演示：触发一次 Agent Run，沙箱取证步骤成功（无 SecurityException、无 ENVIRONMENT_INCOMPLETE），Findings 正常产出。
- [ ] 归档文件在链路中真实产生与消费（能指出生产者代码路径与卷挂载配置）。
- [ ] 生产 profile 缺 `SANDBOX_TOOL_IMAGE` 时后端启动失败且报错信息明确；dev profile 不受影响。
- [ ] backend 与 sandbox-runner 全部测试绿。

## Validation

```bash
# 服务器
docker compose up -d --build
curl -s http://localhost/actuator/health | jq .status        # UP
# 触发 Agent Run（手动登记 PR 或 webhook），观察时间线到取证步骤
docker compose logs sandbox-runner | grep -E "SecurityException|ENVIRONMENT_INCOMPLETE"   # 应无
```
