# 修复批3：口径与工程一致性(F-06~F-14)

> 审计依据：audit-report.md F-06~F-11、F-14（F-12 判定无需修复，仅在 docs 属实性上保持现状）。8 项全部为小改动，一次合并。前置：r1 完成（F-07 需要从绿的 CI 取实测数字）。

## Goal

消除全部已知口径失实与工程不一致：文档数字与实测一致、依赖源可信、脚本跨环境可用。

## Requirements

| # | 审计编号 | 改动 | 锚点 |
| --- | --- | --- | --- |
| R1 | F-06 | 官方源重建前端 lockfile：删除 `package-lock.json` 后 `npm install --registry=https://registry.npmjs.org/`（Node 22 环境执行）；CI 增加 lockfile 源一致性检查（grep resolved host 非 `registry.npmjs.org` 即失败） | `frontend/package-lock.json`（现 94% 指向 npmmirror，npm≥12 直接拒装 EALLOWREMOTE） |
| R2 | F-07 | README 测试基线更新为 CI 实测数字并注明来源与日期；后端 528/3 跳过、sandbox 43、前端 21+构建、model-service 9（以 r1 后最新 CI 为准，勿手抄旧数） | README:89-94 |
| R3 | F-08 | `EMBEDDING_PROVIDER` 默认值描述改为"继承 `AI_PROVIDER`，二者皆空时为 `mock`"，并在"接入真实大模型"一节加一句显式设置提醒（防静默产生 embedding 计费调用） | README:201、`app-agent.yml:97` |
| R4 | F-09 | README 工程基线 Node.js 20 LTS → 22（与 engines/Dockerfile/CI 三处实现对齐） | README:367 |
| R5 | F-10 | `scripts/verify-demo-repos.sh:61` 裸 `python` → `python3`，与 `verify-local.sh` 统一 | 该行现于无 `python` 别名系统恒定误报 FAIL |
| R6 | F-11 | CI 两个 Maven 模块统一为 `mvn -B -s <settings> verify`，settings 上提仓库根共用（或 sandbox-runner 显式引用 backend 的 settings，择一，注意 Dockerfile 内构建路径同步） | `ci.yml:33,39` |
| R7 | F-14 | API 速查表补 6 个遗漏接口：`GET /api/auth/csrf`、`POST /api/auth/logout`、`POST .../knowledge/reindex`、`DELETE .../reviews/tasks/{taskId}`、`POST .../tasks/{taskId}/cancel`、`DELETE .../reports/{reportId}` | README:305-321 |
| R8 | 观察 | 审计执行环境 Docker 可用性声明可顺带更新（README:89 "依赖 Docker 的项未验证"一句按当前事实微调措辞，保持诚实口径） | README:89 |

## Out of Scope

- F-12（部署环境 RAG 全量注入配置）——审计判定与 README 推荐一致，不改。
- F-13 已在 r1 处理。
- 任何功能性代码改动（本批只动文档、脚本、CI、lockfile）。

## Acceptance Criteria

- [ ] Node 22 下 `npm ci` 成功且 lockfile resolved host 100% 为 `registry.npmjs.org`；CI 源检查步骤存在且通过。
- [ ] README 四处口径（基线数字、Node 版本、EMBEDDING 默认值、API 表）与代码/CI 实测一致，可逐条指认。
- [ ] `bash scripts/verify-demo-repos.sh` 在无 `python` 别名的系统上全绿。
- [ ] CI 两模块 Maven 调用对称，全作业绿。
- [ ] `git diff` 确认未触碰产品 Java/Vue 代码逻辑。

## Validation

```bash
grep -o 'https://[^/]*' frontend/package-lock.json | sort | uniq -c   # 仅官方源
bash scripts/verify-demo-repos.sh                                      # PASS×7
docker run --rm -v $PWD/frontend:/ws -w /ws node:22-alpine sh -c "npm ci && npm test"
```
