# r3 修复批3 执行进度（口径与工程一致性 F-06~F-14）

- 执行日期：2026-08-09
- 分支：integration/track-ab（基点 e818226 = main，r2 已合并）
- 改动文件：`README.md`、`.github/workflows/ci.yml`、`scripts/verify-demo-repos.sh`、`frontend/package-lock.json`（共 4 个；无任何产品 Java/Vue 代码改动）

## 各项 R# 完成情况

### R1 (F-06) 官方源重建前端 lockfile + CI 源门禁

- 文件：`frontend/package-lock.json`（重建）、`.github/workflows/ci.yml`（新增 `Check frontend lockfile registry source` 步骤，位于 `npm ci` 之前）
- 关键过程：首次按 PRD 命令重建时 npm 复用了宿主 `node_modules/.package-lock.json` 的旧元数据（产物仅 36 个包、仍指 npmmirror）；改为**先删 node_modules 再重建**（clean-room）后正确。
- 验证（均在 node:22-alpine 容器，npm 10.9.8）：
  - `grep -o '"resolved": "https://[^/"]*' package-lock.json | sort | uniq -c` → **86 条全部 `registry.npmjs.org`**（0 条 npmmirror；剩余 github/opencollective/tidelift 均为 `funding` 元数据，非 resolved 来源）
  - `npm ci` 成功；`npm audit --audit-level=high` → found 0 vulnerabilities
  - `npm test` → **# tests 21 / # pass 21 / # fail 0**；`npm run build` → ✓ built in 5.92s（dist/assets/index-*.js 203.43 kB）
  - `package.json` 的 `"overrides": {"nanoid": "^3.3.17"}` 保留，lockfile 中 nanoid 解析为 **3.3.18**（CVE-2026-67213 修复不回退）
  - CI 门禁逻辑双向验证：对新 lockfile → PASS；对旧 lockfile 备份 → 检出 80 条外源 resolved、正确判 FAIL
- 版本漂移（semver 范围内，已被上述测试覆盖）：vue 3.5.35→3.5.41、postcss 8.5.24→8.5.26、rollup 4.60.4→4.62.4；其余关键包（vite 6.4.3、vue-router 4.6.4、esbuild 0.25.12、nanoid 3.3.18）不变。

### R2 (F-07) README 测试基线更新为实测数字

- 文件：`README.md`（原 89-94 行基线块）
- 新口径与来源（两个来源相互印证，均为 2026-08-09）：
  - main CI run **31310489195**（绿，3 作业全过）verify 日志：后端 `Tests run: 575, Failures: 0, Errors: 0, Skipped: 0`（Testcontainers 3 项在 CI 真实执行）；sandbox `Tests run: 75`；前端 `# tests 21 / # pass 21` + Vite 构建；model-service `9 passed`
  - 本地容器化 surefire 报告（backend 128 份 / sandbox 17 份，2026-08-09 11:13-11:17）：backend run=575 fail=0 skip=3（跳过者即 3 项 Testcontainers 用例），sandbox run=75 fail=0
- README 写法同时反映两种环境：CI 575 全执行通过；本地容器化运行 3 项跳过、572 通过。

### R3 (F-08) EMBEDDING_PROVIDER 默认值口径

- 文件：`README.md`（配置表行 + 「接入真实大模型」一节新增提醒引用块）
- 代码核对：`backend/src/main/resources/config/app-agent.yml` → `embedding-provider: ${EMBEDDING_PROVIDER:${AI_PROVIDER:mock}}`，即继承 `AI_PROVIDER`、二者皆空才 `mock`，与新描述一致。

### R4 (F-09) Node 基线 20 → 22

- 文件：`README.md` 工程基线表。核对三处实现：`frontend/package.json` engines `>=22 <23`、`frontend/Dockerfile` `node:22-alpine`、ci.yml `node-version: "22"`，表中注明对齐关系。

### R5 (F-10) verify-demo-repos.sh 裸 python → python3

- 文件：`scripts/verify-demo-repos.sh`（第 61 行 + 说明注释）
- 验证：
  - 容器全量运行（maven:3.9-eclipse-temurin-17 + apt 装 nodejs/python3，javac/git 自带）：**15 项检查全 ok，EXIT=0**（含 python syntax、SHA 6/6）
  - 宿主机运行（有 python3、无 `python` 别名——即审计误报环境）：`tenant-user-center: python syntax` 由审计时的 `python: command not found` FAIL 转为 **ok**；宿主机仅剩 2 项 javac FAIL，系宿主机无 JDK 的环境限制（脚本行为正确，全绿证据以容器运行为准）

### R6 (F-11) CI 两模块 Maven 调用对称

- 文件：`.github/workflows/ci.yml`
- 改法：backend `mvn -B -s .mvn/settings.xml verify`；sandbox-runner `mvn -B -s ../backend/.mvn/settings.xml verify`（复用同一份 settings，注释说明动机）。顺带删除注释里硬编码的陈旧数字「37 个测试」。
- Dockerfile 核对：backend/sandbox-runner 两个 Dockerfile 均不引用 settings.xml，无需同步。
- `python3 -c "import yaml; yaml.safe_load(...)"` → ci.yml valid YAML；`backend/.mvn/settings.xml` 相对路径自 sandbox-runner 工作目录可达。CI 实际转绿待提交后由下一次 run 证实。

### R7 (F-14) API 速查表补 6 个接口

- 文件：`README.md` API 速查表（认证 / 知识库 / 审查 三行）
- 逐一先核对实现再补入：`GET /api/auth/csrf`（AuthController @GetMapping("/csrf")，`/api/auth` 基路径）、`POST /api/auth/logout`（@PostMapping("/logout")）、`POST .../knowledge/reindex`（KnowledgeController，语义：跳过 embedding 版本未过期文档、逐文档重建分块与向量索引）、`POST .../reviews/tasks/{taskId}/cancel`（仅非终态可取消，终态 409）、`DELETE .../tasks/{taskId}`、`DELETE .../reports/{reportId}`（均在 ReviewController 实证）。

### R8 Docker 验证声明措辞更新

- 文件：`README.md` 基线块标题与末条
- 旧「依赖 Docker 的项在无 Docker 主机上未验证」→ 新：注明数据来源与日期；末条改为「依赖 Docker 的沙箱链路已实测：2026-08-09 在 Docker 环境把 PR 守门 Agent 全链路端到端跑至 COMPLETED」（依据：merge 3c044a0 全链路 e2e COMPLETED）。评测语料「非真实 Docker 语料跑分」的诚实声明保留不动。

## 遗留 / 备注

- F-12 按 PRD 明确不改；未触碰 deploy 配置。
- 旧 lockfile 备份在 `/tmp/package-lock.json.npmmirror.bak`（仅本机排障用，不入库）。
- 宿主机 `frontend/node_modules` 已被容器内 `npm ci` 按新 lockfile 重装（官方源产物）。
- PRD 验收「CI 全作业绿」需在提交推送后由实际 CI run 最终确认（本地已做 YAML/路径/门禁逻辑三重预验）。
