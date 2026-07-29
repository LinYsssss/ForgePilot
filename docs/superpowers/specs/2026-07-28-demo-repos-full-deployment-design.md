# 演示素材改造设计：让 demo-repos 支撑完整版部署的测试与演示

> 编写日期：2026-07-28
> 对应分支：`fix/track-a-core`
> 关联文档：`docs/完整功能测试方案.md`、`docs/演示素材与缺陷对照表.md`

## 1. 目标

让 `demo-repos/` 在服务器完整版部署下，能支撑一次完整的功能测试与答辩演示，且推送到 GitHub/GitLab 后素材不丢失。

范围刻意收紧到「演示链路能跑通」，不追求测试方案 100% 覆盖。第 8 节明确列出了主动放弃的部分及理由。

核心演示链路：登录 → 建项目 → 绑仓库 → 导入 PR → 上传知识库 → 创建审查 → Finding → Patch → 报告 → 审批。

## 2. 一个决定范围的事实

**沙箱不可用时，Agent 链路不会卡住。**

`ValidatingPatchStepExecutor.java:163-169`：

```java
private AgentStepResult publish(String reason) {
    return new AgentStepResult(..., Disposition.ADVANCE,
            AgentRunStatus.PUBLISHING_RESULT,
            Map.of("approvable", false, "reason", reason));
}
```

两条降级路径——`commands.isEmpty()` 与 `catch (IllegalStateException unavailable)`——都是 `ADVANCE` 到 `PUBLISHING_RESULT`，带 `approvable: false` 和原因字符串。

因此沙箱工具镜像（`SANDBOX_TOOL_IMAGE`，仓库中无对应 Dockerfile）不是演示的前置条件。Agent Run 照常跑完，只是补丁标记为不可审批。本次不构建该镜像。

## 3. 要解决的问题

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| 1 | **演示仓库 git 历史推送后丢失** | `demo-repos/*/.git` 是嵌套仓库，外层只跟踪文件；`scripts/init-demo-repo.sh` 只处理 1 个仓库、只建 1 个 commit、无 PR 分支 | PR 审查（base..head）没有素材，M4 演示不了 |
| 2 | mall-order-service 不可编译 | `javac -encoding UTF-8` 实测 EXIT=1：`OrderMapper` 缺 `selectByActivity` / `updateStatus` / `selectById` / `updatePaidAmount` / `selectBySql`，`Order` 缺 `getAmount()`；另有 `com.example.mallorder` 与 `com.example.mall` 重复的 `Order` / `OrderService` | 评委 clone 后随手编译即报错 |
| 3 | 两个仓库缺构建描述 | `payment-settlement-service` 无 `pom.xml`；`tenant-user-center` 无 `pyproject.toml` / `package.json` | 构建工具识别无依据 |
| 4 | 知识库规模过小 | `chunk-size=800` / `overlap=100` / `top-k=5`（`app-agent.yml:84-88`）下，每项目 4 份文档约 7,000 字符、仅 10~11 个切片。从 10 个里选 5 个，随机也有 50% 命中率 | 检索质量无从观察，M23 测不出差异 |
| 5 | 知识库缺通用规范与干扰项 | 12 份文档全部是仓库专属业务文档 | 无法证明检索是「按需召回」而非「全塞」 |
| 6 | 评测语料路径描述错误 | 测试方案 M24 称素材在 `demo-repos/evaluation/`；`EvaluationCorpusServiceTest.java:13` 实际读顶层 `evaluation/manifest.json`。`demo-repos/evaluation/` 内容是 maven/gradle/pyproject/requirements/tsconfig 五个空壳，用途是构建工具识别 | 文档与代码不一致 |

已核实**不需要**处理的：`payment-settlement-service` 的 Java 源码 `javac` 通过（0 错误）；`tenant-user-center` 的 Python 通过 `python -m compileall`，JS 通过 `node --check`。

## 4. 目录结构

```
demo-repos/
├── README.md                          素材总览、上手步骤、诚实边界
├── mall-order-service/                Java（补齐至可编译）
├── payment-settlement-service/        Java（补 pom.xml）
├── tenant-user-center/                Python + JS（补工程描述）
├── patches/                           PR 分支 diff，纯文本，GitHub 网页可评审
│   ├── mall-order-service/feature-promotion-batch-ship.patch
│   ├── payment-settlement-service/feature-instant-settlement.patch
│   └── tenant-user-center/feature-ops-console.patch
├── knowledge-shared/                  跨仓库通用规范，三个项目各上传一份
│   ├── engineering-standards.md
│   ├── security-baseline.md
│   ├── api-design-guide.md
│   ├── data-handling-policy.md
│   └── code-review-checklist.md
├── knowledge-noise/                   干扰文档，验证检索不瞎召回
│   ├── frontend-style-guide.md
│   ├── mobile-release-process.md
│   └── oncall-rotation.md
└── build-tool-fixtures/               原 evaluation/，正名（maven/gradle/pyproject/requirements/tsconfig）
```

`demo-repos/evaluation/` 更名为 `build-tool-fixtures/`。原名与顶层 `evaluation/`（真正的评测语料）冲突，已导致测试方案 M24 描述错误。

## 5. 演示仓库：补齐到可编译

### 5.1 不变量

**43 条缺陷一条不增、一条不减。** 构成为 M1~M10（10 条）、P1~P15（15 条）、T1~T18（18 条）。本节只补缺失的符号与构建描述，不改缺陷语义、不改所在方法、不加框架。

### 5.2 具体改动

| 仓库 | 改动 |
|---|---|
| mall-order-service | `OrderMapper` 补 5 个方法签名；`Order` 补 `getAmount()`；删除 `com.example.mallorder` 包下的重复类；`pom.xml` 保持无外部依赖 |
| payment-settlement-service | 新增 `pom.xml`（结构与 mall 一致，无外部依赖） |
| tenant-user-center | 新增 `pyproject.toml`（声明 fastapi / pyjwt，仅作元数据）与 `package.json`（type: module） |

刻意不引入 Spring Boot、MyBatis、pytest、jest。理由：这些依赖唯一的用途是喂沙箱，而第 2 节已确认沙箱本轮不做。保持零依赖能让「clone 下来就能验证」成立，无需联网拉包。

### 5.3 验收方式

```bash
javac -encoding UTF-8 -d /tmp/out $(find src -name "*.java")   # 两个 Java 仓库，EXIT=0
python -m compileall -q src/                                    # tenant，EXIT=0
node --check web/*.js                                           # tenant，全部 OK
```

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

commit SHA 由 tree、parent、author（名/邮箱/时间）、committer（名/邮箱/时间）、message 共同决定。以上全部钉死后，**任意机器重建得到的 SHA 完全一致**，因此对照表可直接写死真实 SHA。

脚本提供 `--verify` 模式：重建后比对实际 SHA 与预期清单，不一致则以非 0 退出。对已初始化的目录保持幂等（沿用现有 `init-demo-repo.sh` 的行为）。

### 6.2 patches 的来源

从现有三个仓库的本地 `.git` 中导出 `main..feature/*` 的 diff，落为纯文本 patch 文件。导出后原 `.git` 目录不再是唯一真相来源，重建脚本可从零复现。

## 7. 知识库分层

### 7.1 平台的检索边界

`RagService.chunksFor()`（`RagService.java:73-79`）：

```java
if (documentIds == null || documentIds.isEmpty()) {
    return chunks.findByProjectId(projectId);          // 不指定文档 = 全项目检索
}
return chunks.findByProjectIdAndDocumentIdIn(projectId, documentIds);
```

- **`documentIds` 是可选的收窄过滤器。** 不传即在该项目全部文档里普遍检索——「不针对特定文件的查找」平台已支持。
- **`projectId` 是硬过滤，没有跨项目全局知识库。** pgvector 路径（`RagService.java:150`）同样带 `where kc.project_id = ?`。通用规范需在每个项目各上传一份。

### 7.2 三层结构

在现有仓库专属文档之外新增两类，均为 Markdown，无技术风险。

**通用规范（`knowledge-shared/`，5 份，三个项目各传一份）**

| 文档 | 内容 | docType |
|---|---|---|
| `engineering-standards.md` | 命名、异常处理、日志、注释、方法长度 | `STYLE_GUIDE` |
| `security-baseline.md` | 注入、认证、密码学、敏感数据、依赖管理 | `STYLE_GUIDE` |
| `api-design-guide.md` | 分页、错误码、幂等、版本策略 | `API_DOC` |
| `data-handling-policy.md` | 数据分级、脱敏、留存、导出管控 | `STYLE_GUIDE` |
| `code-review-checklist.md` | 评审清单 | `STYLE_GUIDE` |

**干扰文档（`knowledge-noise/`，3 份，与被审代码无关）**

`frontend-style-guide.md`、`mobile-release-process.md`、`oncall-rotation.md`。上传进 Java 后端项目，审查时**不应**出现在引用列表中。没有干扰项时，「检索排序合理」这个结论无法被证伪。

### 7.3 分层不变量

**通用规范只写「是什么」，不写具体数值与业务规则。** 数值与业务规则留给仓库专属文档，否则会抢走 B/C 类缺陷的判定作用。

```
security-baseline.md（通用）   「参数化查询是唯一允许的方式」
security-policy.md（专属）     「ORDER BY 字段必须走白名单」
bug-history.md（专属）         「2024-11 因 keyword 拼接被拖库 12 万条」
```

| 类别 | 应命中的文档层 |
|---|---|
| A 类（SQL 拼接、硬编码凭据） | 通用规范即可 |
| B 类（ORDER BY 白名单、脱敏要求） | **必须**命中仓库专属文档 |
| C 类（0.6% 费率、INC-2024-07） | **必须**命中 `bug-history.md` |

判定标准随之收紧：A 类命中通用规范属正常，**B/C 类必须命中仓库专属文档**才能认定 RAG 真正起效。这强化对照表既有的 A/B/C 分层，不改变 43 条缺陷的编号与归类。

### 7.4 加层后的规模

| | 现在 | 加层后 |
|---|---|---|
| 每项目文档数 | 4 | 12（4 专属 + 5 通用 + 3 干扰） |
| 每项目切片数 | ~10 | ~34 |
| top-k=5 选中率 | 50% | 15% |
| full-context 全量字符 | 7,366 | ~23,500 |
| 对 `max-context-chars: 6000` | 勉强超出 | 明显截断，可观察 |

### 7.5 三档对照实验

测试方案 M6-20 现为两档，扩展为三档：

| 档位 | 关联范围 | 预期能发现 |
|---|---|---|
| 1 | 不关联任何文档 | 只有 A 类 |
| 2 | 只关联 5 份通用规范 | A 类 + 部分规范符合性问题 |
| 3 | 关联全部 12 份 | A + B + C 类 |

第 2 档为新增。它能证明「通用规范有用，但业务上下文不可替代」，比二元对比更能说明 RAG 的价值层次。

## 8. 主动放弃的部分

以下不做，理由与答辩口径一并写入 `demo-repos/README.md`。

| 放弃项 | 理由 | 影响的用例 | 答辩口径 |
|---|---|---|---|
| 沙箱工具镜像（约 2GB）与三份规则集 | 第 2 节已证实 Agent 链路有干净降级；构建成本与演示价值不成比例 | M10 全节、M9-7 | 隔离策略已实现且有单测覆盖（`ContainerPolicy` / `SandboxCommandCatalog`），工具镜像未构建 |
| 仓库改造为 Spring Boot + 单元测试 | 唯一用途是喂沙箱 | 补丁 exitCode 对比 | 同上 |
| 独立仓库发布与真实 webhook PR | 需要外网回调与多仓库维护 | M11-5/6/7/18 | 验签、幂等、并发、正文不落库仍可用手造签名请求验证（M11-9/11/12/16） |
| 语言边界与补丁边界 fixture | 与核心链路无关 | M22-4/5/6、M9-14 | 未覆盖 |

## 9. 配套改动

- `docs/完整功能测试方案.md`：修正 M24 的语料路径；M6-20 由两档改三档；M23-1 补充干扰文档判定；M10 与 M9-7 标注为本轮不覆盖并说明原因。
- `docs/演示素材与缺陷对照表.md`：更新位置列、写死真实 commit SHA；新增「知识库分层与三档对照实验」一节含记录模板；判定标准中写明 B/C 类必须命中仓库专属文档。
- `docs/12_服务器部署与演示手册.md`：更新初始化脚本名称（`init-demo-repo.sh` → `init-demo-repos.sh`）与三仓库上手步骤。

## 10. 诚实边界

写入 `demo-repos/README.md`：

1. **缺陷是刻意植入的**，不是真实项目中自然产生的。不宣称「在真实项目里发现了 43 个问题」。
2. **AI 不保证全部命中。** 对照表是满分答案，实际命中率取决于模型与检索质量。
3. **可能出现误报。** 这正是系统保留人工审批环节的理由。
4. **`knowledge-noise/` 是刻意放的干扰项**，目的是让「检索排序合理」可被证伪。答辩时应主动说明。
5. **通用规范是为演示编写的**，不是从真实企业规范摘录，不宣称对标某套行业标准。
6. **仓库代码可编译但不可运行**，没有数据库与容器依赖。它们的用途是被审查，不是被部署。
7. **沙箱工具镜像未构建**，补丁会标记为不可审批并附原因，这是设计内的降级而非故障。

## 11. 实施顺序

| 阶段 | 内容 | 结束条件 |
|---|---|---|
| 1 | 补齐三个仓库至可编译 | `javac` / `compileall` / `node --check` 全部 EXIT=0 |
| 2 | 导出 `patches/`，重写重建脚本 | `init-demo-repos.sh --verify` 通过，SHA 与清单一致 |
| 3 | 八份知识文档 | 上传后全部 `INDEXED`，三档对照实验分层清晰 |
| 4 | 目录更名与文档同步 | 对照表、测试方案、部署手册与代码一致 |

阶段 3 只依赖 Markdown，与阶段 1、2 无耦合，可并行。

## 12. 验收标准

- [ ] 干净机器上 `git clone` 主仓库后执行 `scripts/init-demo-repos.sh --verify` 通过，三个仓库的 SHA 与清单一致。
- [ ] 三个仓库分别通过 `javac` / `python -m compileall` / `node --check`，EXIT=0。
- [ ] 每个项目上传 12 份知识文档后状态均为 `INDEXED`，切片总数约 34。
- [ ] 三档对照实验结果分层清晰：第 1 档只出 A 类，第 3 档能出 B/C 类。
- [ ] 审查 Java 仓库时，`knowledge-noise/` 的三份文档不出现在引用列表中。
- [ ] `RAG_FULL_CONTEXT=true` 时可观察到 `max-context-chars` 截断提示。
- [ ] 43 条缺陷在对照表中位置准确，逐条可在代码中定位。
- [ ] 核心演示链路端到端跑通：登录 → 项目 → 绑仓库 → 导入 PR → 知识库 → 审查 → Finding → Patch → 报告 → 审批。
