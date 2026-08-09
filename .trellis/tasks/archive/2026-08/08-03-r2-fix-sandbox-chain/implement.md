# Implement：沙箱归档断链修复

## 执行清单（服务器）

1. [ ] `trellis-before-dev` 读取规范与本任务上下文。
2. [ ] 契约层（方案 C / C'，见 design.md）：
   - [ ] 定义 `WorkspaceArchiveReference` encode/parse（parse 含全部安全校验：拒绝 `..`、`\`、绝对路径、非白名单字符；长度上限沿用现有 `InputValidation`）。
   - [ ] 后端 `RepositoryArchiveRefResolver` 改用 encode；`InputValidation.requireArchive` 收敛到同一规则源。
   - [ ] Runner `WorkspaceArchiveResolver` 改用 parse，保留 resolve 后的 `Files.isRegularFile` 与路径围栏检查。
3. [ ] 契约测试（新增，双向）：
   - [ ] 后端真实产出格式 → Runner parse 通过并解析到预置临时文件。
   - [ ] 拒绝集：`workspace://…`（若 C' 选择去 scheme）、`../x.tar`、`a\b.tar`、`/etc/passwd`、空串、超长、前导 `-`。
   - [ ] 两侧 `SandboxJob` 字段序快照测试（防 record 字段重排）。
4. [ ] 归档生产者：backend 派发作业前 `git archive` 写 `/app/archives/<ref>.tar`；compose 给 backend 挂读写卷，Runner 保持 `:ro`；作业终态清理归档。
5. [ ] `deploy/.env` 补 `SANDBOX_TOOL_IMAGE=<镜像>@sha256:<digest>`（用 `docker inspect --format='{{index .RepoDigests 0}}' <image>` 取真实 digest）。
6. [ ] `ProdSecretValidator` 并入 `SANDBOX_TOOL_IMAGE` 校验（生产 profile 非空 + digest 格式），补对应测试（缺失→启动失败；dev profile 不校验）。
7. [ ] 全量验证（见下），`trellis-check` Agent 复查，ADR 落 `docs/adr/`，提交推送。

## Validation commands

```bash
# 单元/契约
cd backend && mvn -s .mvn/settings.xml test
cd ../sandbox-runner && mvn -B verify

# 端到端（Docker）
cd deploy && docker compose up -d --build
curl -s localhost/actuator/health | jq .status
# 触发 Agent Run 后：
docker compose logs sandbox-runner | grep -E "SecurityException|ENVIRONMENT_INCOMPLETE"  # 期望无
docker compose exec backend ls /app/archives                                             # 归档在作业期间可见
```

## 风险文件

- `RepositoryArchiveRefResolver` / `InputValidation`（后端）、`WorkspaceArchiveResolver` / `RepositoryCommandExecutor`（Runner）、`deploy/docker-compose.yml`、`ProdSecretValidator`。
- 回滚点：整批单独提交，revert 即恢复现状。

## Before task.py start

- [ ] implement.jsonl / check.jsonl 已有真实条目（audit 报告 + 上述风险文件）。
