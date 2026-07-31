# demo-repos —— 代码审查演示素材

本目录是 RepoSage 代码审查能力的验证素材。三个仓库里的缺陷是**刻意植入**的，
配套的知识文档决定了哪些缺陷「只有读过文档才可能发现」。每一条缺陷都能在
`docs/演示素材与缺陷对照表.md` 里逐条核对。

```
demo-repos/
├── mall-order-service/          演示仓库 · Java · 电商订单
├── payment-settlement-service/  演示仓库 · Java · 支付结算
├── tenant-user-center/          演示仓库 · Python + JavaScript · 多租户用户中心
├── knowledge-shared/            通用规范 5 份（三个项目共用）
├── knowledge-noise/             干扰文档 3 份（三个项目共用）
├── patches/                     PR 分支的唯一载体，由重建脚本消费
└── build-tool-fixtures/         构建工具识别夹具，与演示流程无关
```

`build-tool-fixtures/` 里是 maven、gradle、tsconfig、pyproject、requirements
五套空壳工程，供构建工具识别逻辑取样。它原名 `evaluation/`，与仓库顶层的
`evaluation/`（真正的评测语料）重名并已导致过文档描述错误，故更名区分。它不参与
下面任何一个步骤。

以下命令均假设当前目录是仓库根目录 `F:\202605New`。

---

## 一、三个仓库一览

| 仓库 | 语言 | 领域 | PR 分支 | 缺陷编号 | 条数 |
|---|---|---|---|---|---|
| `mall-order-service` | Java（Maven） | 电商订单：状态机、发货、金额 | `feature/promotion-batch-ship` | M1~M10 | 10 |
| `payment-settlement-service` | Java（Maven） | 支付结算：费率、金额精度、回调、退款 | `feature/instant-settlement` | P1~P15 | 15 |
| `tenant-user-center` | Python（FastAPI）+ JavaScript | 多租户用户中心：租户隔离、认证、运营后台 | `feature/ops-console` | T1~T18 | 18 |

编号对应 `docs/演示素材与缺陷对照表.md` 第四节，共 43 条。按类别分：

| 类别 | 条数 | 含义 |
|---|---|---|
| A 类 · 通用缺陷 | 3 | SQL 拼接、URL 参数未编码、原型污染这类形态特征明显的问题，静态扫描也能发现 |
| B 类 · 规则依赖 | 17 | 违反知识文档里写明的规则，不读文档发现不了 |
| C 类 · 事故重犯 | 19 | 重复 `bug-history.md` 里记录过的历史事故 |
| 跨类 | 4 | M5、T5 属 A+C；P13、T13 属 A+B |

`tenant-user-center` 是双语言仓库（`src/app/` 是 Python，`web/` 是 JavaScript），
另外两个是纯 Java，多语言审查能力靠它覆盖。

每个仓库重建后有 3 个提交：`main` 上 2 个（源码与构建描述、知识文档），PR 分支上
1 个（被审查的功能）。`main` 的基线代码基本正确，缺陷集中在 PR 分支的那次提交里，
因此对比审查 `main` 与 PR 分支可以直接体现「增量审查」的差异。

---

## 二、上手三步

### 第 1 步：重建三个仓库

三个仓库的**文件**在主仓库里，克隆能拿到；但它们各自的 `.git` 目录不入库，分支与
提交历史需要在本机重建一次：

```bash
bash scripts/init-demo-repos.sh --verify
```

Windows PowerShell：

```powershell
pwsh -File scripts/init-demo-repos.ps1 -Verify
```

`--verify` / `-Verify` 会把重建出的 6 个 ref（3 个 `main` + 3 个 PR 分支）与
`scripts/demo-repos-expected-sha.txt` 里固定的 SHA 逐条比对，六条全中才退出 0；
不带该参数则只重建、不比对。任何时候都可以单独跑一次完整校验（编译、语法、SHA）：

```bash
bash scripts/verify-demo-repos.sh
```

脚本对已初始化的仓库幂等跳过。**若上一次运行中途失败留下了半成品仓库，脚本不会自愈**
——它只看 `.git` 是否存在，存在就跳过。此时删掉三个 `.git` 后重跑：

```bash
rm -rf demo-repos/*/.git && bash scripts/init-demo-repos.sh --verify
```

```powershell
Remove-Item -Recurse -Force demo-repos\*\.git
pwsh -File scripts/init-demo-repos.ps1 -Verify
```

### 第 2 步：在 RepoSage 里绑定仓库

三个仓库各建一个项目，仓库绑定按下表填：

| 字段 | 值 |
|---|---|
| provider | `LOCAL` |
| 仓库地址 | 绝对路径，例如 `F:\202605New\demo-repos\mall-order-service` |
| 分支 | `main` |

审查任务的目标提交填 PR 分支的 HEAD。取法：

```bash
git -C demo-repos/mall-order-service         rev-parse feature/promotion-batch-ship
git -C demo-repos/payment-settlement-service rev-parse feature/instant-settlement
git -C demo-repos/tenant-user-center         rev-parse feature/ops-console
```

这六个 ref 的 SHA 是固定值，同样列在 `scripts/demo-repos-expected-sha.txt` 里。
走 SCM Webhook 的完整 PR 流程需要 GitHub/GitLab 侧配合；本机演示直接审查分支 HEAD
提交即可，diff 内容是一样的。

### 第 3 步：上传知识文档

每个项目上传 **12 份** `.md`：该仓库 `docs/` 下的 4 份 + `knowledge-shared/` 的
5 份 + `knowledge-noise/` 的 3 份。等全部文档状态变为 `INDEXED` 后再创建审查任务，
否则未索引完的文档不会进入检索范围。

---

## 三、知识库三层

| 层 | 位置 | 份数 | 用途 |
|---|---|---|---|
| 仓库专属 | `<repo>/docs/` | 4 | 该仓库的业务规则、表结构、安全约定与历史事故。B 类与 C 类缺陷的判据全在这一层 |
| 通用规范 | `knowledge-shared/` | 5 | 三个仓库共用的工程规范，只写与业务领域无关的通则 |
| 干扰文档 | `knowledge-noise/` | 3 | 与代码审查无关但有真实感的文档，用来检验检索排序 |

各仓库 `docs/` 下的 4 份：

- `mall-order-service`：`order-flow.md`、`db-schema.md`、`security-policy.md`、`bug-history.md`
- `payment-settlement-service`：`settlement-rules.md`、`db-schema.md`、`security-policy.md`、`bug-history.md`
- `tenant-user-center`：`tenant-isolation.md`、`auth-policy.md`、`api-contract.md`、`bug-history.md`

`knowledge-shared/` 的 5 份：`engineering-standards.md`、`security-baseline.md`、
`api-design-guide.md`、`data-handling-policy.md`、`code-review-checklist.md`

`knowledge-noise/` 的 3 份：`frontend-style-guide.md`、`mobile-release-process.md`、
`oncall-rotation.md`

三层的边界是刻意划出来的：通用规范里**不写**具体费率、金额阈值、字段名与事故编号，
这些只出现在仓库专属文档里。因此「AI 说得出具体数值」这件事只可能来自专属文档的
检索命中，不可能是从通用规范里推导出来的——下一节的判定口径就建立在这条边界上。

---

## 四、三档对照实验

对同一个 PR 分支的 HEAD 审查三次，只改「关联哪些知识文档」，其余参数全部不变。

| 档 | 关联文档 | 预期发现范围 |
|---|---|---|
| 第 1 档 | 不关联 | A 类，以及跨类那 4 条的 A 面（如 T13 前端硬编码 API Key）。少数形态明显的 B/C 类也可能被报出来——例如 T2 整个漏掉 `tenant_id` 过滤——但说不出依据，指不到具体条款或事故编号 |
| 第 2 档 | 仅 `knowledge-shared/` 5 份 | A 类，加上一部分 B 类的「违规」层面：能指出违反了哪条通用规范 |
| 第 3 档 | 全部 12 份 | B/C 类命中数应显著上升，输出里应出现专属文档名、章节号与历史事故编号 |

### 判定口径

第 2 档**允许**出现这样的表述：「这里违反了 `security-baseline.md` 要求的参数化
查询」「导出接口没有行数上限，不符合 `data-handling-policy.md` 对批量导出的约定」。
这是通用规范应有的效果。

但第 2 档**说不出**下面三样东西：

- **具体数值**：结算费率 0.6%、最小结算净额 100 分、bcrypt cost ≥ 12、导出行数上限
- **历史事故编号**：`INC-2024-07`、`BUG-001` 这一类
- **专属条款出处**：例如 `tenant-isolation.md` 第 2 节第 3 条「`tenant_id` 必须
  来自认证上下文，不得来自请求参数」

如果第 2 档就报出了上述任意一项，说明关联范围没控制住（专属文档被误关联），或者
模型在编——两种情况都要复查，不能当作检索生效的证据。

**B 类与 C 类必须命中仓库专属文档才算 RAG 真正起效。** 第 3 档如果 Finding 数量涨了，
但没有一条引到 `bug-history.md` 或专属规则文档，只有「建议增加参数校验」这类放之
四海皆准的话，那就不能算检索起了作用。

第 3 档里 `knowledge-noise/` 的 3 份也在关联范围内，它们的作用是让「检索排序合理」
可被证伪：正常情况下它们不该被召回为 Finding 的依据。若某条 Finding 引用了
`oncall-rotation.md` 之类，那是检索排序的问题，应当如实记录而不是略过。

最适合单独拎出来对比的是 **T1**：`search_users` 与 `export_users` 都接收 `tenant_id`
参数并用它过滤，代码层面完全「正常」——一个函数按租户过滤了，看上去就是对的。只有
读过 `tenant-isolation.md` 第 2 节第 3 条「`tenant_id` 必须来自认证上下文，不得来自
请求参数」，才知道调用方可以自选租户。这类缺陷纯代码审查发现不了，三档之间的差异
在它身上最清楚。一条覆盖两个函数，做对比够用。

演示时注意区分同一处代码上的两条缺陷：这两个函数的 SQL 是 f-string 拼的（T5），
第 1 档就该报出注入；但「`tenant_id` 取自请求参数」（T1）在第 1 档不该出现。同一段
代码、两种结论，这个对照比换个函数更有说服力。

**不要拿 T2 做这个对比。** `user_stats` 是 `select role, count(*) ... group by role`，
`tenant_id` 过滤整个缺失、函数连这个参数都没接——属于「漏了过滤」，静态审查一眼可见，
第 1 档大概率就能报出来。它的价值在别处：对照表把它记在 `bug-history.md` 的
INC-2024-09 名下，看 AI 能不能指出「这与 INC-2024-09 是同一个问题」，那是 C 类的
考法，不是「代码看着正常」的考法。

---

## 五、诚实边界

1. **缺陷是刻意植入的**，不是真实项目中自然产生的。不宣称「在真实项目里发现了 43 个问题」。
2. **AI 不保证全部命中。** 对照表是满分答案，实际命中率取决于模型与检索质量。
3. **可能出现误报。** 这正是系统保留人工审批环节的理由。
4. **`knowledge-noise/` 是刻意放的干扰项**，目的是让「检索排序合理」可被证伪。答辩时应主动说明。
5. **通用规范是为演示编写的**，不是从真实企业规范摘录，不宣称对标某套行业标准。
6. **仓库代码可编译但不可运行**，没有数据库与容器依赖。它们的用途是被审查，不是被部署。
7. **沙箱工具镜像未构建**，补丁会标记为不可审批并附原因，这是设计内的降级而非故障。

---

## 六、patches 目录

`patches/<repo>/feature-*.patch` 是 PR 分支的**唯一载体**。演示仓库的 `.git` 不入库，
PR 分支的那次提交也就无处存放，改由 patch 文件承载：重建脚本建好 `main` 之后切出
feature 分支、`git apply` 对应 patch、再提交。

| patch | 应用到 |
|---|---|
| `patches/mall-order-service/feature-promotion-batch-ship.patch` | `feature/promotion-batch-ship` |
| `patches/payment-settlement-service/feature-instant-settlement.patch` | `feature/instant-settlement` |
| `patches/tenant-user-center/feature-ops-console.patch` | `feature/ops-console` |

**不要手工编辑这些 patch。** patch 内容直接决定 PR 分支提交的 SHA，改动一个字节就会
让 `--verify` 的六条比对失败。这是防篡改设计，不是脚本脆弱：素材一旦被悄悄改过，
对照表就不再对得上，而 SHA 比对能立刻把这件事暴露出来。

确需改动 PR 分支内容时走完整流程：改 patch → 重建 → 把新的实际 SHA 更新进
`scripts/demo-repos-expected-sha.txt` → 同步 `docs/演示素材与缺陷对照表.md` 的对应条目。
