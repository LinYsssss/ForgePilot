# Phase 1 验证清单

以下命令是规划目标；具体脚本名可随实现生成器微调，但 `result.md` 必须记录实际执行命令、退出码和关键结果。默认不跑与 Phase 1 无关的全量业务测试。

## 1. 工作树与格式

```bash
git status --short --branch
git diff --check
git diff --stat
```

- [ ] 所有 dirty paths 已归属本任务或明确列为用户已有改动。
- [ ] 无 whitespace error、冲突标记或意外二进制大文件。

## 2. 后端构建与定向测试

```bash
cd backend
./mvnw -B verify
```

必须从 Maven 输出或定向报告确认：

- [ ] Java release 为 21，Spring Boot/依赖均为固定稳定版，无 snapshot/RC。
- [ ] Spring context/Actuator health 测试通过。
- [ ] Testcontainers 使用 PostgreSQL 15 + pgvector 镜像，`server_version_num >= 150000`。
- [ ] Flyway 从空库成功执行，`vector` extension 存在。
- [ ] ArchUnit 五条规则通过且有负向 fixture 证明规则非恒真。

## 3. 数据库与业务范围边界

```bash
rg -n -i 'create\s+table|alter\s+table' backend/src/main/resources/db/migration
rg -n 'class .*Controller|class .*Service|@Entity|@Table|enum .*Status' backend/src/main/java
find backend/src/main/java/com/forgepilot -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
```

- [ ] migration 只启用 pgvector，不创建业务表。
- [ ] 除应用入口/框架配置/package contract 外无业务 Controller/Service/Entity/Status。
- [ ] 顶层 package 集合严格为 `ai auth common knowledge project requirement review scm`。
- [ ] 禁止 package：`agent patch mq rag repo pullrequest context assistant finding` 无命中。

## 4. 前端检查

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm run test -- --run
npm run build
```

- [ ] lockfile 安装可重复；依赖无 prerelease。
- [ ] 路由只表达项目、研发需求、代码审查三项信息架构与通用空状态。
- [ ] 无 Pinia/Axios/Tailwind/UI 大库/图表库，除非规划经重新批准。
- [ ] 键盘焦点、语义结构、颜色非唯一状态载体和 reduced-motion 定向测试通过。
- [ ] 用户选定视觉方向后，生产 tokens 与 `.trellis/spec/frontend/` 一致。

## 5. 视觉 artifact 与规范

- [ ] 三个 HTML artifact 均可本地打开并交互。
- [ ] DOM/布局结构、密度、字体层级和记忆点有实质差异，不是换色。
- [ ] 每个方向记录商业契合度、语义色角色与禁用模式。
- [ ] 主会话保存用户明确选择证据。
- [ ] `design-contract.md`、`motion.md` 与已有六份前端 spec 无占位符/冲突，且只描述实际实现。

## 6. 评测契约

```bash
python3 evaluation/tools/score.py --validate-corpus --manifest evaluation/manifest.quick.json --case-set evaluation/case-sets/phase1-quick.json
python3 evaluation/tools/score.py --selftest
python3 evaluation/tools/score.py --manifest evaluation/manifest.quick.json --runs evaluation/reference-runs --out-dir /tmp/forgepilot-phase1-scores
python3 evaluation/tools/score.py --compare-report /tmp/forgepilot-phase1-scores/scores-reference.json evaluation/fixtures/phase1-reference-score.json
```

- [ ] quick manifest 恰有 12 个唯一 case，全部 `split=development`，source commit 正确。
- [ ] 评分器内置 selftest 全绿；reference 重算与版本化 snapshot 一致。
- [ ] 分母为 0、null line、类别 alias、贪心 1:1、AC verdict、notRun/结构失败均有自测。
- [ ] reference runs 明确为 synthetic/reference，不记录为真实模型质量。

Holdout 防泄漏检查：

```bash
rg -n '"split"\s*:\s*"holdout"|python-safe-parameterization|prompt-injection-comment|typescript-known-patch|biz-fee-rounding-mode|biz-ship-missing-shipped-at|eng-second-path-fence-bypass|eng-map-get-deref-chain|fp-java-guarded-admin-endpoint|miss-clearing-currency-skip|miss-pump-cap-dropped|sec-java-project-member-idor|sec-java-audit-filter-sqli' evaluation .trellis/tasks/08-20-phase-1-foundation --glob '!research/**' --glob '!prd.md' --glob '!design.md' --glob '!implement.md' --glob '!validation.md'
```

- [ ] 无 holdout fixture、run 或 score 产物；若 README/contract 为解释禁令而出现单词 `holdout`，需人工区分，不得包含具体运行结果。

## 7. Compose smoke

```bash
docker compose -f compose.yaml -p forgepilot-phase1-a config
docker compose -f compose.yaml -p forgepilot-phase1-a build
docker compose -f compose.yaml -p forgepilot-phase1-a up -d --wait
docker compose -f compose.yaml -p forgepilot-phase1-a ps
curl -fsS http://127.0.0.1:<frontend-port>/
curl -fsS http://127.0.0.1:<backend-port>/actuator/health
docker compose -f compose.yaml -p forgepilot-phase1-a logs --no-color
docker compose -f compose.yaml -p forgepilot-phase1-a down --volumes
# 使用 forgepilot-phase1-b 与第二组全新卷完整重复一次
```

- [ ] 两次 fresh-volume cold-start 均三服务健康，空库 Flyway 成功，pgvector 可用。
- [ ] 失败和成功路径均只清理 `forgepilot-phase1` project。
- [ ] 现有 `cpa-manager-plus`、`cli-proxy-api` 与 `cloudflared` 未被停止、重启或改配。
- [ ] 需要空库复验时仅删除明确命名的 ForgePilot Phase 1 volume，并记录可恢复性。

## 8. CI

- [ ] CI backend job 执行等价于 `./mvnw -B verify`。
- [ ] CI frontend job 执行 npm clean install、lint/typecheck/test/build。
- [ ] CI evaluation job 执行 manifest validation、selftest、reference recomputation 与 holdout guard。
- [ ] CI smoke job 构建/启动/健康检查/清理独立 Compose project。
- [ ] 所有 job 不依赖 AI、SCM、生产数据库或仓库秘密。

## 9. 4 GB 容量验证

原始数据必须落入 `.trellis/tasks/08-20-phase-1-foundation/evidence/capacity/<run-id>/`。建议至少包含：

```text
host.txt
baseline-memory.tsv
samples.tsv
docker-stats.tsv
process-smaps.tsv
jvm-memory.tsv
postgres-settings.txt
oom-before.txt
oom-after.txt
compose-ps.txt
summary.md
```

- [ ] 先完成 5 分钟既有服务基线和 2 分钟预热；使用 UTC 与单调 elapsed time，稳定采样间隔 15 秒、窗口不少于 240 秒且不少于 17 组。
- [ ] 采样包含目标机现有服务与 ForgePilot 三服务。
- [ ] JVM heap/non-heap/direct/metaspace 和 PostgreSQL内存参数有实值。
- [ ] 稳定窗口所有有效样本 `MemAvailable >= 1 GiB`。
- [ ] 无 kernel/container OOM、无意外重启；稳定窗口 `SwapUsed` 相对基线增长不超过 64 MiB，且不出现连续 3 个采样间隔的 swap-in/out；孤立的页级抖动不单独判失败。
- [ ] 结论明确为 PASS/FAIL/INVALID；失败或无效运行未删除，任何参数调整均重新跑完整 5+2+4 分钟协议；报告不得把短窗口外推为长期稳定性证据。

## 10. 架构与安全边界人工检查

- [ ] 后端仍为一个模块化单体；没有第二 runtime/pipeline。
- [ ] `scm` 编译依赖无 `review`。
- [ ] 无 Agent、Patch、MQ/Outbox、Redis、代码索引/向量库、本地 Git/clone。
- [ ] 前端无额外一级菜单，未混淆业务状态（本阶段不应出现业务状态）。
- [ ] `.env.example` 无真实凭据；日志和 CI 输出无 secret。
- [ ] Legacy 仅使用迁移矩阵允许的评测数据/工具，未继承旧 Flyway 或周边架构。

## 11. 最终任务验证

```bash
python3 ./.trellis/scripts/task.py validate 08-20-phase-1-foundation
python3 ./.trellis/scripts/task.py current --source
git diff --check
git status --short
```

- [ ] `result.md` 含全部证据与偏差。
- [ ] 任务在提交与用户验收前仍为 `in_progress`，不提前 archive。
- [ ] 未创建/启动 Phase 2 任务。
- [ ] 提交分组与 commit message 已展示并等待用户确认；未自动推送。
