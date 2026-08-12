# evaluation/ — PR Gatekeeper 评测语料与判分工具

r7 起,评测集从 6 例扩到 32 例(dev 22 / holdout 10),并配套独立判分工具链(不改造后端评测框架)。
规格权威:`.trellis/tasks/08-03-r7-eval-corpus/design.md`(D1-D4)。

## 目录结构

```
evaluation/
├── manifest.json           # 唯一标注载体:runtimeMetadata + fixedRun + cases[](全部预期 findings 在这里)
├── cases/<id>/             # 用例 fixture,两种布局(manifest 每例 fixtureLayout 字段声明):
│   │                       #   single(缺省,老 6 例):单态文件树 = 被审查后状态
│   │                       #   base-head(新用例):base/ 缺陷前树 + head/ 缺陷后树,diff = head - base
│   ├── base/  head/        # base-head 布局的两态
│   ├── knowledge/*.md      # 可选:该例的判据知识文档(跑分时走 API 上传,不进 git 仓库树)
│   └── expected.patch      # 可选:期望修复补丁(判分工件,不进仓库树,防答案泄给模型)
├── results/                # 历史合成小矩阵基线(exporter 自校验产物,非真实模型跑分;白名单不动)
└── tools/                  # r7 判分工具三件套(本 README 下文)
    ├── build-case-repos.sh
    ├── run-baseline.sh
    ├── score.py
    └── category-aliases.json
```

## 语料校验的真实入口

**仓库里不存在 `validate_corpus.py`**(个别历史文档引用失实,以本节为准)。确定性校验的实体是
Java 服务 `backend/.../evaluation/EvaluationCorpusService.validate`,自动执行入口是:

```bash
# backend 目录内(容器化环境用项目内 settings):
mvn -s .mvn/settings.xml test -Dtest=EvaluationCorpusServiceTest
# 或随全量:mvn -s .mvn/settings.xml verify
```

该测试用 `Path.of("..","evaluation","manifest.json")` 校验真实 manifest:temperature 必须 0、
toolImage 必须 digest-pinned、case id 唯一、split 双侧非空、fixture 目录存在且不逃出 evaluation 根、
标注字段非空且 line>0、base-head 布局两目录必须存在、lineEnd>=line 等。加用例后必须跑绿。

## 两率计算口径(judge 规则全文,r8 对比的基准)

### 命中规则(match rule `d3-v1`,实现在 `tools/score.py`)

```
hit(f, e) := samePath(f.filePath, e.path)                       # 最小规范化:\→/,剥前导 ./ 与 /
          ∧ norm(f.category) ∈ {e.category}
                              ∪ 全局别名表[e.category]           # tools/category-aliases.json
                              ∪ e.categoryEquivalents            # manifest 用例级例外
          ∧ [f.lineStart, f.lineEnd] ∩ [e.line, e.lineEnd] ≠ ∅   # f.lineStart 为 null ⇒ 不命中
```

- `e.lineEnd` 缺省 = `e.line`;`f.lineEnd` 为 null 时取 `f.lineStart`。类别比较大小写不敏感。
- **贪心 1:1 匹配**:预期与模型 findings 两侧各按(文件, 行, 类别)排序,按序为每个预期配第一个
  未被占用且命中的模型 finding;每个预期至多配一个模型 finding,反之亦然。排序保证判分确定性。

### 两个指标(独立呈报,禁止合成单一分数)

| 指标 | 定义 | 等价口径 |
| --- | --- | --- |
| **漏报率** | 未命中的预期 findings / 预期 findings 总数 | 1 − recall |
| **误报率** | 无对应预期的模型 findings / 模型 findings 总数 | 1 − precision |

- 两率各出全量、分类别(漏报按**标注类别**、误报按**模型输出类别**)、分 split(development/holdout)三份。
- 分母为 0 时呈报 n/a,不折算成 0 或 100%。
- 未跑成的用例**不进两率分母**,在结果里单列 `notRun`,基线档案必须声明。
- `nonFindings` 是"不许报"红线:这些用例的未匹配模型 findings 天然计入误报率,并在逐例明细表
  单列违规提示,供人工比对确认。
- **与后端 `EvaluationMetrics.falsePositiveRate` 不是一回事**:那是 FP/(FP+TN)(依赖真阴性计数),
  本口径的误报率是 FP/(TP+FP)。引用数字时必须写清用的是哪个定义。
- **mock provider 跑分无意义**:`AI_PROVIDER=mock` 的规则引擎不读知识文档且行号恒为 null,
  按行区间规则会全灭。基线跑分口径绑定 `AI_PROVIDER=openai-compatible` + 真实大模型;
  若结果里两率接近全灭,先检查是不是误用了 mock。

## 工具三件套

### 1. build-case-repos.sh — 确定性构建用例 git 仓库

```bash
bash evaluation/tools/build-case-repos.sh [--only <id>]... [--work-dir <dir>]
```

把 manifest 每个用例构建成 main 分支两提交的 git 仓库(固定作者/时间戳,SHA 跨机器稳定):
base-head 布局 = commit1(base 树)+ commit2(head 树);single 布局 = commit1(README 占位)
+ commit2(fixture 整树新增)。`knowledge/` 与 `expected.patch` 不进仓库树(前者走 API 上传,
后者是判分工件)。输出 `$EVAL_WORK_DIR/manifest-shas.txt`(每行 `<id> <baseSha> <headSha>`)。
幂等:先删后建;`--only` 只更新对应行。

### 2. run-baseline.sh — 隔离栈跑分驱动器

```bash
EVAL_BASE_URL=http://127.0.0.1:<隔离栈端口> EVAL_USERNAME=... EVAL_PASSWORD=... \
  bash evaluation/tools/run-baseline.sh [--resume] [--only <id>]...
```

逐例执行:CSRF 引导 → 登录 → 创建项目 → 绑定 LOCAL 仓库 →(有 knowledge/)上传文档并等
INDEXED → 建审查任务(commitId=headSha, baseCommitId=baseSha)→ 轮询终态 → 导出报告+issues
原始响应到 `.trellis/tasks/08-03-r7-eval-corpus/baseline-runs/<date>/<id>.json`。
失败例写 `<id>.error.json` 并继续;`--resume` 跳过已成功的例、重试失败的例。

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EVAL_BASE_URL` | 是 | (无,故意) | 隔离栈地址;无默认值是为了防止误打演示栈 |
| `EVAL_USERNAME` / `EVAL_PASSWORD` | 是 | (无) | 栈内账号(种子管理员);只经环境注入,脚本不打印 |
| `EVAL_REPOS_MOUNT` | 否 | `/eval-repos` | 用例仓库在 backend **容器内**的挂载前缀 |
| `EVAL_WORK_DIR` | 否 | `/tmp/reposage-eval-repos` | 宿主机工作目录(读 manifest-shas.txt) |
| `EVAL_RUN_DATE` | 否 | 今天(YYYY-MM-DD) | 输出子目录名 |
| `EVAL_OUT_ROOT` | 否 | `<repo>/.trellis/tasks/08-03-r7-eval-corpus/baseline-runs` | 输出根 |
| `EVAL_TASK_TIMEOUT` | 否 | 900 | 单任务轮询上限秒(对齐 fixedRun.timeoutSeconds) |
| `EVAL_INDEX_TIMEOUT` | 否 | 120 | 知识文档 INDEXED 等待上限秒 |
| `EVAL_POLL_INTERVAL` | 否 | 5 | 轮询间隔秒 |

### 3. score.py — 判分器(python3 标准库 only)

```bash
python3 evaluation/tools/score.py --runs .trellis/tasks/08-03-r7-eval-corpus/baseline-runs/<date>
python3 evaluation/tools/score.py --selftest   # 内置小矩阵自测,必须打出 SELFTEST OK
```

按上文口径产出 `scores-<date>.json` + `scores-<date>.md`(默认写到 runs 目录的父目录):
全量/分类别/分 split 两率 + 逐例明细 + nonFindings 违规提示 + notRun 清单。
`category-aliases.json` 是全局类别别名表(扩展方式见文件内 `_comment`)。

## 基线跑分操作顺序(隔离栈,演示栈零接触)

1. **前置**:r6 归档完成;宿主机有 git/curl/python3。
2. **构建用例仓库**:`bash evaluation/tools/build-case-repos.sh`,核对 manifest-shas.txt 条数 = 用例数。
3. **起隔离栈**:`docker compose -p reposage-eval`(独立 project name + 独立卷 + 错开端口,同镜像);
   环境要点:`GIT_ALLOW_LOCAL_PATH=true`、`AI_PROVIDER=openai-compatible`、MiMo 凭据经环境注入
   (沿用 deploy/.env 变量名,不落盘不打印)、把 `$EVAL_WORK_DIR` 挂载到 backend 容器的
   `$EVAL_REPOS_MOUNT`(只读即可;注意挂载目录对容器用户可读,若 clone 报 dubious ownership,
   在栈内容器加 `git config --global --add safe.directory '*'`)。
   **具体化实现**:以上要点已固化为 `tools/eval-stack.override.yml`(compose 覆盖层:18080 错开、
   非入口端口 `!reset` 收回、/eval-repos 只读挂载)+ `tools/eval-stack.sh`(up/run/calls/down
   四子命令;`run` 在子壳内把 `SEED_ADMIN_*` 映射为 `EVAL_*`,凭据不打印不落盘;`calls` 在
   down 前导出 ai_call_log 实数)。步骤 3/4/7 可分别用 `eval-stack.sh up / run / down` 执行。
4. **跑分**:设 `EVAL_BASE_URL`/`EVAL_USERNAME`/`EVAL_PASSWORD` 后执行 `run-baseline.sh`;
   失败例用 `--resume` 补跑至清零(或在档案里逐条声明)。
5. **判分**:`score.py --runs baseline-runs/<date>`,得两率与逐例明细。
6. **落档**:主会话把结果整理成 `.trellis/tasks/08-03-r7-eval-corpus/baseline-mimo-<date>.{json,md}`
   (模型名/日期/temperature 实值/调用与 token 实数以 ai_call_log 佐证/QualityGate 不适用声明)。
7. **收栈**:`docker compose -p reposage-eval down -v`,即弃无残留;演示栈全程零接触。
