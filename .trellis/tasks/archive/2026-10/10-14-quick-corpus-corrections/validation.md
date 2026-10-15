# 快速语料三处硬伤更正 验证记录（2026-10-14）

## 状态

已实现并推送（`9de50a7` 三处更正、`72c441e` case id 保留回退，分支 `fix/review-audit-fixes`），CI run 38670011837 四项 job 全绿（Backend verify、Evaluation contract、Frontend gates、Empty-stack Compose smoke），任务归档。本任务不涉及后端；评分口径全部门在本地以 CI 相同命令执行。

## 本地门（Python 3.13）

- `--validate-corpus`：CORPUS VALID。
- `--selftest`：SELFTEST OK（30 项）。
- 重算 `scores-reference.json` 与 `fixtures/phase1-reference-score.json`：REFERENCE MATCH；重算差异逐行核对，仅预期更正（行号 7→4-5、AC-0002 文本、case id 保持原名）。
- `--guard-no-holdout --root .`：OK。
- `test_run_development.py`：4 项通过；`run_development.py --all-arms --dry-run` 正常。

## 关键决定：case id 不改名

初次实现把 `java-broken-build` 改名为 `java-clean-change`，CI run 38668708941 的 holdout guard 失败：2026-08 归档开发评测证据（run.json/score.json，带日期快照）引用旧 id，而冻结的 guard 以 `--root .` 扫描全仓并要求 run/corpus 输出只引用当前 quick 集。score.py 冻结、归档证据不可改写，因此 quick 语料 id 是事实上的稳定键；`72c441e` 回退改名，保留全部内容级更正（干净 fixture、AC 文本、显示标题），`evaluation/README.md` 与任务 PRD 记录理由。教训：本地守卫必须与 CI 同口径（`--root .`），首次本地只扫了 `evaluation/` 才漏过。

## 边界

- 冻结文件（score.py、run_development.py、category-aliases.json、三份 contract、metrics.md）一字未动；类型标签与别名口径刻意未改，由论文效度分析说明。
- 归档证据与论文前置准备历史记录不更新（带日期快照）。
- 未部署，未触发真实模型调用。
