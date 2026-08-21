# 批次 2 验证清单

命令为规划目标，实际脚本名可微调；`result.md` 必须记录真实执行的命令、退出码和关键结果。

## 1. 工作树

```bash
git status --short --branch && git diff --check
```

- [ ] 所有 dirty path 归属本批次；无 whitespace error、无冲突标记。

## 2. 后端构建与测试

```bash
cd backend && flock /root/.claude/jobs/e84ffece/tmp/maven.lock docker run --rm --network host \
  -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

- [ ] 全部测试通过，无 skip；**依赖零新增**（`git diff backend/pom.xml` 为空）。
- [ ] 应用能启动——实体映射若不成立会在启动期失败，这是最早的信号。

## 3. 数据库约束（真实 PostgreSQL 15）

- [ ] 空库 Flyway 后恰好 **13 张**业务表 + `flyway_schema_history`，逐名比对。
- [ ] 公共知识（`source_requirement_id IS NULL`）挂到 Requirement 被 `23503` 拒绝。
- [ ] **反证**：把 `requirement_attachment.requirement_id` 改可空后，不存在的 `document_id` 能落库——
      证明 `NOT NULL` 是承重的而非装饰（[D015.2](../../../docs/v2/DECISIONS.md#d015)）。该测试用临时表，不改真实 schema。
- [ ] 维度自洽 CHECK 被 `23514` 拒绝；`(document_id, seq)`、`(provider, instance_identity, external_id)` 唯一。
- [ ] 跨项目写入被数据库拒绝，测试须绕过 Service 直写。
- [ ] `ai_call_log.review_id` 此刻**全为 NULL**（批次 3 补外键前的前置断言）。

## 4. AI Gateway（无凭据）

- [ ] 超时真的触发（stub 睡过配置值）。
- [ ] **恰好重试一次**：可重试错误下 stub 请求计数 = 2。
- [ ] 永久错误（400）不重试：计数 = 1。
- [ ] 两次调用都落 `ai_call_log`，`status` 正确区分 `FAILED` / `TIMEOUT`。
- [ ] `Authorization` 头携带配置值——证明鉴权接线正确，且**不存在任何真实 key**。
- [ ] 全仓库 grep：无硬编码 provider host。

## 5. Knowledge

- [ ] NUL（`22021`）、非法 UTF-8（`22021`）显式失败。
- [ ] **孤立代理项被应用层拒绝**，且有字节级往返断言证明它没有被静默改成 `?`。
- [ ] 超限上传被 `KnowledgeUploadValidator` 拒绝。
- [ ] 错维度 embedding 写入被应用层拒绝（数据库此时**不会**拦，见 [D015.3](../../../docs/v2/DECISIONS.md#d015)）。
- [ ] 检索一律带 `projectId`；A 项目检索不到 B 项目的 chunk。
- [ ] 提升为公共知识产生**新** Document，原附件行未被改写。
- [ ] grep：`::vector` 与 `<=>` 只出现在 `ChunkSearchRepository`。

## 6. SCM

- [ ] `OutboundUrlPolicy` 白名单为空时逐条拒绝：`127.0.0.1`、`10.0.0.1`、`192.168.1.1`、
      `169.254.169.254`、`::1`、非 `http(s)` 协议。
- [ ] 正确签名通过；改一字节被拒；**解析后结构相同但字节不同**（重排键/加空白）被拒。
- [ ] 无效签名返回 `401` 且**零写入**；未知仓库同样 `401`，两者响应体不可区分。
- [ ] fingerprint：同输入同值；任一输入变一字节则变值；`source_revision` 变化**不**改变它。
- [ ] 重放幂等；旧 `source_updated_at` 不回退 head/base/patch。
- [ ] `REQ-<n>` 解析按项目过滤；外项目 id 解析为「未关联」且**不阻断入库**。
- [ ] 有 PR 后修改三元组被拒（`409`）；`api_base` 可改但须仍指向同一实例。
- [ ] 授权判定不读 `scm_username`。

## 7. 架构规则

```bash
find backend/src/main/java/com/forgepilot -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
find backend/src/main/java/com/forgepilot -mindepth 2 -type d
grep -rn "Logger\|slf4j" backend/src/main/java | grep -v "/common/"
```

- [ ] ArchUnit 七条全绿；顶层包仍八个；子包只有 `scm.github`。
- [ ] `scm` 不依赖 `review`；`knowledge` 不反查 `requirement`；`ai` 不依赖业务模块。
- [ ] `scm` 未注入 `RequirementRepository`（只用只读 facade）。
- [ ] 无 `common` 之外的 logger；`ai_call_log` 载荷不进日志。

## 8. Compose 与 CI

```bash
scripts/phase1-compose-smoke.sh forgepilot-phase1-batch2-<unique>
```

- [ ] smoke 的 `expected_tables` 已改为**十三张全名**；空库冷启动三服务健康。
- [ ] CI 四个 job 全绿，且 `.github/workflows/ci.yml` 中**仍无 `secrets.*`**。

## 9. 边界人工检查

- [ ] 无 Review / Finding 相关代码或表；未新增第 17 张表。
- [ ] 无向量索引、无维度绑定。
- [ ] 无新增前端界面、无新增一级菜单、无新增运行时依赖。
- [ ] 迁移中除 `author_user_id` 外无 `ON DELETE`。

## 10. 最终任务验证

```bash
python3 ./.trellis/scripts/task.py validate 08-21-batch-2-ai-knowledge-scm
git diff --check && git status --short
```

- [ ] `result.md` 含全部证据与偏差，**§2.1 补列（`title`/`failure_reason`）单独列出**。
- [ ] 非数据库执行的不变式（三元组冻结）如实记为「非数据库执行」。
- [ ] 密钥轮换缺口如实记录。
- [ ] 按 [D014](../../../docs/v2/DECISIONS.md#d014) 逐条自证退出闸门。
