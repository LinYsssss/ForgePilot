# 快速语料三处硬伤更正与产物重算

## Goal

快速语料（12 例 development quick corpus）有三处明确的标注/构造错误，逐例核对了 fixture 源码后确认：

1. `java-sql-resource-leak` 的真值与 AC 提示指向 `src/App.java` 第 7 行，而该行是方法的右花括号；两条未关闭的资源获取在第 4-5 行。按行区间相交的匹配口径，正确报告 4-5 行的模型会被记为漏报加误报。
2. `java-broken-build` 的 fixture 是 `class Broken { void compile( { }`，本身就是语法错误的代码，却被用作"零缺陷"阴性用例，并把语法错误定义为"环境或构建结果"。这个定义不成立：源码级语法错误报出来反而被扣分。
3. `typescript-ambiguous-null` 把评测元信息写进了 fixture 注释（"verifier evidence is required before blocking"）和 AC 文本（"must not block without verifier evidence"），且该注释在 prompt 里引导模型不要报缺陷，与真值（AT_RISK + 一条 finding）方向相反。

## Requirements

- 只改语料：`manifest.quick.json`、`case-sets/phase1-quick.json`、`cases/`、`reference-runs/phase1-reference.json`、`fixtures/phase1-reference-score.json`、`evaluation/README.md`。
- **冻结文件一字不动**：`score.py`、`run_development.py`、`category-aliases.json`、`metrics.md`、三份 contract。
- 类型标签（6 例 CODE_QUALITY）与别名口径不改：与正式语料同构，属构造属性，论文以效度分析说明。
- `corpusVersion` 升为 `forgepilot-phase1-quick-v2`；`caseSetVersion` 不变（写死在冻结的 `score.py` 里）。
- `java-broken-build` 改名 `java-clean-change`：manifest 校验要求 `fixture == cases/{id}`，目录随之改名；新 fixture 为无缺陷的新增类 `src/Adder.java`。
- 参考运行与评分 fixture 按新真值同步重算，`--compare-report` 必须通过。

## Acceptance Criteria

- [ ] `score.py --validate-corpus`、`--selftest`、`--guard-no-holdout` 全部通过。
- [ ] 重算的 `scores-reference.json` 与更新后的 `fixtures/phase1-reference-score.json` 语义相等（`--compare-report` 输出 REFERENCE MATCH）。
- [ ] `test_run_development.py` 单测通过（其硬编码引用的三个用例 id 不在本次改动范围内）。
- [ ] 归档证据与论文前置准备的历史审计记录不更新（它们是带日期的快照）。

## Notes

- 轻量任务，PRD-only。
- 本机 Python 3.13 可用，评分口径的全部门在本地可执行；后端无关。
