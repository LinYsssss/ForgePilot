# 演示素材改造设计：让 demo-repos 支撑服务器完整版功能测试

> 编写日期：2026-07-28
> 对应分支：`fix/track-a-core`
> 关联文档：`docs/完整功能测试方案.md`、`docs/演示素材与缺陷对照表.md`

## 1. 目标

现有 `demo-repos/` 是「供 AI 阅读的代码样本」。本次改造把它变成「可在服务器完整版部署下驱动全链路功能测试的工程素材」，并保证素材推送到 GitHub/GitLab 后仍然完整可用。

具体要达成：

- 三个演示仓库是**真工程**：可编译、可跑测试、有构建描述文件。
- 演示仓库的 **git 历史与 PR 分支可在任意机器确定性重建**，commit SHA 完全一致。
- 沙箱白名单里的 19 条命令**有可执行的工具镜像**，且在只读根 + nobody + 无网络下真能跑通。
- 测试方案里所有需要素材的用例**都有对应物**，包括边界与异常素材。
- 素材的用途命名与文档描述一致，不再出现路径写错、占位摘要冒充真镜像的情况。

不在本次范围：修改后端业务逻辑、调整 Agent 编排、变更沙箱安全策略。第 9 节列出的配套改动仅限配置与演示入口，不触碰审查链路本身。

## 2. 现状问题清单

按影响排序，全部有实据。

| # | 问题 | 证据 | 影响的测试模块 |
|---|---|---|---|
| 1 | 演示仓库 git 历史推送后丢失 | `demo-repos/*/.git` 是嵌套仓库，外层只跟踪 56 个文件；`scripts/init-demo-repo.sh` 只处理 1 个仓库、只建 1 个 commit、无 PR 分支 | M4 全节、4-5、9-11 |
| 2 | 代码不可编译 | `javac -encoding UTF-8` 实测 EXIT=1：`OrderMapper` 缺 5 个方法、`Order` 缺 `getAmount()`；`payment-settlement-service` 无 `pom.xml`；`tenant-user-center` 无 `pyproject.toml`/`package.json` | M9-7、M10 全节 |
| 3 | 无测试用例 | 三个仓库均无 `src/test`、`tests/`、`__tests__/` | M9-7 补丁验证无 exitCode 可比对 |
| 4 | 沙箱工具镜像不存在 | 仓库仅 4 个 Dockerfile（backend/frontend/model-service/sandbox-runner）；`RabbitSandboxToolGateway.java:39` 默认值 `reposage-tools@sha256:abc` 过不了 `LanguageCommandRequest.java:21` 的 64 位十六进制校验；`app-boundary.yml:43` 的 `SANDBOX_TOOL_IMAGE` 默认为空 | M9-7、M10 全节、语言插件 ToolCommand |
| 5 | 静态分析规则集不存在 | 白名单引用 `/opt/reposage/rulesets/{pmd.xml,checkstyle.xml,semgrep.yml}`，仓库中无对应文件 | `java.pmd`、`java.checkstyle`、`javascript.semgrep` |
| 6 | 评测语料路径描述错误 | 测试方案 M24 称素材在 `demo-repos/evaluation/`；`EvaluationCorpusServiceTest.java:13` 实际读顶层 `evaluation/manifest.json`。`demo-repos/evaluation/` 内容是 maven/gradle/pyproject/requirements/tsconfig 五个空壳，用途是构建工具识别 | M24 |
| 7 | 评测 manifest 使用假镜像摘要 | `evaluation/manifest.json` 中 `"toolImage": "reposage-tools@sha256:abcdef"` | M24-1、答辩可复现性 |
| 8 | 边界素材未落盘 | 上传边界文件靠 `docs/功能测试准备清单.md` 里的 PowerShell 现场生成，Linux 服务器无对应物 | M5.3、M22-4/5/6、M9-14 |
| 9 | 前端演示入口单一 | `VITE_DEMO_REPO_PATH` 单值指向 `mall-order-service` | M26 界面走查 |

## 3. 沙箱运行环境的硬约束

设计必须服从 `ContainerPolicy.java:37-49` 生成的容器参数：

```
--network none                        无网络
--read-only                           根文件系统只读
--cap-drop ALL / --security-opt no-new-privileges
--user 65534:65534                    nobody
--tmpfs /tmp:rw,noexec,nosuid,size=64m
--mount type=bind,src=<workspace>,dst=/workspace    唯一可写位置
```

由此推出四条设计约束：

1. **镜像必须 `WORKDIR /workspace`。** 白名单命令不带 `-w`，容器工作目录取自镜像。
2. **Maven 本地仓库不能用 `~/.m2`。** nobody 用户在只读根下无家目录写权限。依赖预热进镜像 `/opt/m2`，由**项目侧** `.mvn/maven.config` 指定 `-o -Dmaven.repo.local=/opt/m2`（白名单命令固定，无法追加参数）。
3. **Gradle 的 `GRADLE_USER_HOME` 必须落在 `/workspace` 下。** 镜像设 `ENV GRADLE_USER_HOME=/workspace/.gradle`；依赖解析由项目侧 `settings.gradle.kts` 声明 `maven { url = uri("file:///opt/m2") }`。
4. **不能依赖 `/tmp` 执行代码。** `/tmp` 挂载了 `noexec`。白名单已含 `mvn -B`（batch 模式不加载 jansi native 库），无需额外处理。

## 4. 目录结构

```
demo-repos/
├── README.md                          素材总览、一键上手、诚实边界
├── mall-order-service/                Java · Spring Boot 3 · 订单域
│   ├── pom.xml
│   ├── .mvn/maven.config
│   ├── src/main/java/…
│   ├── src/test/java/…
│   ├── docs/                          4 份知识文档（内容不变）
│   └── README.md
├── payment-settlement-service/        Java · Spring Boot 3 · 结算域（同构）
├── tenant-user-center/                Python FastAPI + 原生 JS
│   ├── pyproject.toml
│   ├── package.json
│   ├── eslint.config.js
│   ├── tsconfig.json                  allowJs + checkJs，供 javascript.typescript 使用
│   ├── src/app/…
│   ├── tests/…                        pytest
│   ├── web/…
│   ├── web/__tests__/…                jest
│   └── docs/
├── patches/                           PR 分支 diff，纯文本，可在 GitHub 网页评审
│   ├── mall-order-service/feature-promotion-batch-ship.patch
│   ├── payment-settlement-service/feature-instant-settlement.patch
│   └── tenant-user-center/feature-ops-console.patch
└── fixtures/
    ├── knowledge/                     M5.3 上传边界
    ├── languages/                     M22-4/5/6 语言识别边界
    ├── patch-boundary/                M9-6 / M9-14 恶意补丁，验证平台拒绝
    ├── patch-repair/                  补丁验证演示：红灯测试 + 修复补丁
    ├── build-tools/                   原 demo-repos/evaluation/，正名
    └── vitest-minimal/                javascript.vitest 的最小覆盖工程
```

`demo-repos/patches/` 与 `demo-repos/fixtures/patch-*/` 用途不同，不要混淆：

- `patches/` —— **PR 分支的 diff**，被重建脚本 `git apply` 用来生成 feature 分支，是审查对象。
- `fixtures/patch-boundary/` —— **恶意补丁**，用于验证平台会拒绝（路径穿越、HTML 注入）。
- `fixtures/patch-repair/` —— **修复补丁 + 配套红灯测试**，用于演示沙箱验证中 exitCode 由非 0 变为 0。

`demo-repos/evaluation/` 更名为 `fixtures/build-tools/`。原名与顶层 `evaluation/`（真正的评测语料）冲突，已导致测试方案 M24 描述错误。更名后需同步修改 `docs/完整功能测试方案.md` 的 M24 小节。

## 5. 三个演示仓库的改造

### 5.1 不变量

**43 条缺陷一条不增、一条不减，逐条对得上位置。** 构成为 M1~M10（10 条）、P1~P15（15 条）、T1~T18（18 条）。改造只做「让它成为真工程」：补构建描述、补依赖、补缺失的方法与字段、加框架注解、加测试。缺陷的语义与所在方法不变。

对照表（`docs/演示素材与缺陷对照表.md`）第四节的「位置」列随类名/方法名变化同步更新，编号保持不变。

### 5.2 技术栈

| 仓库 | 改造前 | 改造后 |
|---|---|---|
| mall-order-service | 裸 POJO，编译失败 | Spring Boot 3 Web + MyBatis，`@RestController` / `@Service` / `@Mapper` |
| payment-settlement-service | 无 `pom.xml` | 同上 |
| tenant-user-center（Python） | FastAPI 代码但无工程描述 | `pyproject.toml`：fastapi + pyjwt + pytest + httpx |
| tenant-user-center（JS） | 裸 ESM 模块 | `package.json`：jest + eslint；`tsconfig.json` 开 `allowJs`/`checkJs` |

依赖集刻意保持最小：不引入 Spring Data JPA、Spring Security、SQLAlchemy ORM。理由是镜像预热体积可控，且缺陷（SQL 拼接、越权、无审计）在最小依赖下依然完整可见。

清理项：删除 `com.example.mallorder` 包下与 `com.example.mall` 重复的 `Order` / `OrderService`。

### 5.3 测试用例的设计目标

`PatchValidationExecutor` 的判定逻辑是「基线执行一次 → 应用补丁再执行一次 → 比对 exitCode」。据此：

- **main 分支：测试全绿**（基线 exitCode = 0）。
- **PR 分支：测试同样全绿**，但覆盖不到植入的缺陷。

第二条是刻意的。缺陷代码本身能正常运行、能通过既有测试，否则开发者自己就发现了，PR 也不会进入评审。让 PR 分支测试变红会破坏演示逻辑的可信度。

- **补丁修红灯的演示**由 `fixtures/patch-repair/` 单独承载：提供一对「暴露缺陷的红灯测试 + 修复补丁」，用于展示 exitCode 从非 0 变为 0。

## 6. 确定性重建

### 6.1 脚本

`scripts/init-demo-repos.sh` 重写（并提供等价的 `init-demo-repos.ps1`），覆盖全部三个仓库：

```bash
export GIT_AUTHOR_NAME="RepoSage Demo"
export GIT_AUTHOR_EMAIL="demo@reposage.local"
export GIT_AUTHOR_DATE="2026-01-15T10:00:00+08:00"    # 每个 commit 使用固定且递增的时间
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
export GIT_COMMITTER_DATE="$GIT_AUTHOR_DATE"
```

流程：`git init -b main` → 提交 baseline → `git switch -c feature/<name>` → `git apply patches/<name>.patch` → 提交。

commit SHA 由 tree、parent、author（名/邮箱/时间）、committer（名/邮箱/时间）、message 共同决定，以上全部钉死后，**任意机器重建得到的 SHA 完全一致**。因此对照表可直接写死真实 SHA。

脚本提供 `--verify` 模式：重建后比对实际 SHA 与预期 SHA 清单，不一致则以非 0 退出。脚本对已初始化的目录保持幂等（沿用现有 `init-demo-repo.sh` 的行为）。

### 6.2 独立仓库发布

`scripts/publish-demo-repos.sh`：把重建好的三个仓库推送为 GitHub/GitLab 上的独立仓库，供 M11 开真实 PR、发真实 webhook。

主仓库与独立仓库共用同一份源码和同一份 patch，不产生分叉。不使用 git submodule —— `git clone` 未加 `--recurse-submodules` 时 `demo-repos/` 会是空目录，答辩现场重新 clone 的翻车风险不可接受。

## 7. 沙箱工具镜像

新增 `sandbox-tools/Dockerfile`，提供 `SandboxCommandCatalog.java:13-40` 全部 19 条命令所需的绝对路径：

| 命令组 | 需提供 |
|---|---|
| `sandbox.health` | `/bin/true` |
| `patch.apply*` | `/usr/bin/git` |
| `java.maven.*` | `/usr/bin/mvn` |
| `java.gradle.*` | `/opt/gradle/bin/gradle` |
| `java.pmd` | `/opt/pmd/bin/pmd` + `/opt/reposage/rulesets/pmd.xml` |
| `java.spotbugs` | `/opt/spotbugs/bin/spotbugs` |
| `java.checkstyle` | `/usr/bin/java` + `/opt/checkstyle/checkstyle.jar` + `/opt/reposage/rulesets/checkstyle.xml` |
| `python.*` | `/usr/local/bin/{ruff,bandit,pytest}` |
| `javascript.*` | `/usr/local/bin/{eslint,semgrep,tsc,jest,vitest}` + `/opt/reposage/rulesets/semgrep.yml` |

镜像基线：`eclipse-temurin:17-jdk-jammy`。附加 `WORKDIR /workspace`、`ENV GRADLE_USER_HOME=/workspace/.gradle`、`/opt/m2` 预热三个演示仓库的 Maven 依赖（构建期有网）。

三份规则集为本次新写。取舍原则是「能命中演示仓库里的 A 类通用缺陷，且不产生淹没性噪音」：

- `pmd.xml` —— 基于 PMD 内置 `category/java/errorprone.xml` 与 `security.xml`，保留 SQL 拼接、资源未关闭、异常吞没相关规则。
- `checkstyle.xml` —— 基于 Sun Checks 精简，保留命名、魔法数、方法长度，关闭格式类噪音规则。
- `semgrep.yml` —— 采用 `p/javascript` 与 `p/owasp-top-ten` 中与演示缺陷对应的规则：`innerHTML` 注入、硬编码凭据、原型污染。

规则集只用于补充 A 类通用缺陷的静态证据，B/C 类（依赖知识文档、历史事故）仍由 RAG 与模型承担，规则集不试图覆盖。

**构建期自检**：Dockerfile 末尾用与运行期等价的约束（只读根、`--user 65534`、无网络）实跑一次 `mvn -B test`、`pytest`、`eslint`，任一失败则镜像构建失败。避免出现「镜像建成但沙箱内跑不动」。

`scripts/build-sandbox-tools.sh`：构建镜像 → 读取 RepoDigest → 写入 `deploy/.env` 的 `SANDBOX_TOOL_IMAGE` → 同时回填 `evaluation/manifest.json` 的 `fixedRun.toolImage`，替换现有的 `sha256:abcdef` 占位摘要。

## 8. 边界素材

`fixtures/` 下入库以下素材；超大文件不入库，由 `scripts/gen-demo-fixtures.sh` 生成。

| 用例 | 素材 |
|---|---|
| M5.3 上传边界 | `knowledge/binary-disguised.md`（MZ 头）、`invalid-utf8.md`、`unsupported.pdf`、`blank.md`、`oversized.md`（脚本生成 3MB） |
| M22-4 未知语言 | `languages/unknown/main.rs`、`languages/unknown/main.go` |
| M22-5 二进制文件 | `languages/binary/logo.png` |
| M22-6 超大单文件 | 脚本生成 |
| M9-14 路径穿越补丁 | `patch-boundary/path-traversal.patch`（含 `../`） |
| M9-6 HTML 不执行 | `patch-boundary/html-injection.patch`（diff 正文含 `<script>`） |
| 补丁验证 exitCode 变化 | `patch-repair/`：红灯测试 + 对应修复补丁 |

## 9. 配套改动

- `deploy/docker-compose.yml`：`VITE_DEMO_REPO_PATH` 保持默认值，新增 `VITE_DEMO_REPO_PATHS`（逗号分隔三个路径），前端演示入口支持切换。
- `docs/完整功能测试方案.md`：修正 M24 的语料路径描述；补充 M10 的镜像前置条件。
- `docs/演示素材与缺陷对照表.md`：更新位置列、写死真实 commit SHA、补充新增素材说明。
- `docs/12_服务器部署与演示手册.md`：更新初始化脚本名称与工具镜像构建步骤。

## 10. 诚实边界

写入 `demo-repos/README.md`，答辩时可主动说明：

1. **缺陷是刻意植入的**，不是真实项目中自然产生的。不宣称「在真实项目里发现了 43 个问题」。
2. **AI 不保证全部命中。** 对照表是满分答案，实际命中率取决于模型与检索质量。
3. **可能出现误报。** 这正是系统保留人工审批环节的理由。
4. **`javascript.vitest`** 由 `fixtures/vitest-minimal/` 最小工程覆盖，主演示仓库使用 jest，不假装两套都在用。
5. **`java.spotbugs` 分析的是 `.class` 而非源码**，必须在 `java.maven.compile` 之后执行，README 中标注顺序。
6. **工具镜像约 2GB**，首次构建 15–25 分钟，需在答辩前预先构建。

## 11. 实施顺序

各部分之间有依赖，按此顺序推进，每阶段结束都有可验证产出：

| 阶段 | 内容 | 结束条件 |
|---|---|---|
| 1 | 三个仓库改造为真工程 + 测试 | 宿主机上 `mvn -B test` / `pytest` / `npx jest` 全绿 |
| 2 | 抽出 `patches/`，重写重建脚本 | `init-demo-repos.sh --verify` 通过，SHA 与清单一致 |
| 3 | 工具镜像 + 三份规则集 | 镜像构建期自检通过，digest 写入 `deploy/.env` |
| 4 | `fixtures/` 全套边界素材 | 逐条投递均得到预期的拒绝或降级 |
| 5 | 文档与配套改动同步 | 对照表、测试方案、部署手册与代码一致 |
| 6 | `publish-demo-repos.sh` 与独立仓库发布 | GitHub/GitLab 上可开真实 PR |

阶段 1 与阶段 3 相互印证：镜像预热的依赖来自阶段 1 的 `pom.xml`，而阶段 1 的测试要在阶段 3 的镜像里复跑一次才算数。

## 12. 验收标准

- [ ] 干净机器上 `git clone` 主仓库后，执行 `scripts/init-demo-repos.sh --verify` 通过，三个仓库的 SHA 与清单一致。
- [ ] 三个仓库在宿主机上分别执行 `mvn -B test` / `pytest` / `npx jest`，main 与 PR 分支均全绿。
- [ ] `scripts/build-sandbox-tools.sh` 构建成功，构建期自检通过，`deploy/.env` 与 `evaluation/manifest.json` 中的摘要一致且非占位值。
- [ ] 完整版部署后，19 条白名单命令逐条投递均返回非异常结果（`sandbox.health` 起验）。
- [ ] `fixtures/patch-repair/` 的修复补丁在沙箱中使 exitCode 由非 0 变为 0；`fixtures/patch-boundary/` 的两个恶意补丁均被拒绝。
- [ ] 测试方案中所有标注「需要素材」的用例均能找到对应物，无「现场生成」依赖。
- [ ] 43 条缺陷在对照表中位置准确，逐条可在新代码中定位。
