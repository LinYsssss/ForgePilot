# 全面检查遗留事项跟踪（2026-10-12 审查）

## 目的

记录 2026-10-12/13 全面检查后仍未处理的问题与决策，作为清单与决策记录。本任务不做实现；每一项被处理时另行立项或在本文件勾销。

## 一、待用户决定（阻塞后续）

1. **A2 Embedding 分批的内存上限**（任务 `10-12-knowledge-ingestion-embedding-batching`，状态 planning）：接受极限 5 MB 文档约 100-120 MB 的内存峰值，还是下调上传上限（改 ARCHITECTURE 7.2 表与论文表述），或改逐批落库（重写 §5 的"一个事务"措辞并补失败清理）。设计已备，等决定后 `task.py start`。
2. **A5 前端 composable 设计审阅**（任务 `10-12-frontend-project-selection-composable`，状态 planning）：`projectPath` 收敛到 `lib/http.ts`；各页面"无 project 参数时是否自动选中首项"按现状逐页保留而非统一。
3. **B 组语料更正**：行号（`java-sql-resource-leak` 真值 L7 指向右花括号，实际缺陷在 L4-5）、6 例类型标签与 AC 矛盾、别名口径（两套机制、UNKNOWN 覆盖面不均）、`java-broken-build` 与 `typescript-ambiguous-null` 两个 fixture。耦合动作：同步重算 `reference-runs/phase1-reference.json` 与 `fixtures/phase1-reference-score.json`、升 `corpusVersion`、改 `evaluation/README.md` 的"冻结"措辞；**冻结文件（`score.py`、`run_development.py`、`category-aliases.json`、`metrics.md`、三份 contract）不动**，别名与评分规则问题只能继续以敏感性分析形式写入论文。
4. **六份交付 HTML 的补充段**：本轮触及错误码、密文失败处理、embed 未配置、删除留痕查询、软删活动聚合；按 `docs/deliverables/README.md` 惯例应追加带日期的补充段而非重写快照。建议等部署后随下一次部署记录一并写。

## 二、待验证

5. **CI 首次真实验证**：三个修复任务（`10-12-review-activity-soft-deleted-requirement`、`10-12-github-fetch-provider-error-mapping`、`10-12-small-fixes-ai-knowledge-scm-requirement`）已实现并提交在 `fix/review-audit-fixes`，本机无 JDK 21 与 Docker，从未编译或执行。**已验证通过**：CI run 38555642951（2026-10-14），backend verify、frontend、evaluation、compose-smoke 四个 job 全绿；逐项证据见三个任务的 `validation.md`。
6. **通过后归档**：三个任务已随本次提交归档（`archive/2026-10/`）；交付文档补充段仍待部署后（见第 4 项）。

## 三、第二轮复核缺口（尚未深读的代码）

7. `ProjectAccessService`、`AuthService`、`DecisionRepository` 的认证与决策细节未逐行审。
8. `WorkspacePage.vue`（607 行）与 `ReviewDetailPage.vue`（1219 行）两个最大的前端文件只做了结构检查。
9. `evaluation/tools/` 全部脚本：`formal_evaluation.py` 的完整 freeze/ledger 逻辑、`sensitivity_deleaked.py`、`postfreeze_*` 未逐行读。

## 四、已记录、可选优化（未立项）

10. GitHub 403 二级限流当前映射 502 `provider_error`；可按 `Retry-After` / `x-ratelimit-remaining` 头映射 503，两家 Provider 同步改。
11. 零批次审查（全二进制或固定上下文超预算）仍以 COMPLETED 收场；是否显式失败需改 ARCHITECTURE 3.4 并定前端呈现。
12. 证据锚定无最小长度，单字符引用可过；加最小长度需改 3.5 并评估对 review-5 既有行为的影响。
13. `changeType` 词表因 Provider 而异（GitHub `removed` vs GitLab `deleted`），只能在展示层映射（指纹含该值，存储层归一化会让历史审查全部过期）。
14. `review-demo-assets` 319 个文件 87 份唯一内容；`备用-毕设仓` 四份文档全库无引用。是否压缩/删除。
15. `.trellis` 占跟踪体积约 80%（2026-08 容量证据 5 份 kernel 日志各 2-3 MB）；是否只留一份加哈希。
16. `TESTING-GUIDE.md` 的 K 组可补一条负向用例："Provider 不可用时投递返回 503 provider_unavailable"。
17. 前端约 30 个多余 `export` 与三份 `projectPath`，随 A5 一并处理；`directory-structure.md` 需随 A5 更新。
18. 小卫生：根目录空 `node_modules/.vite` 残留（未跟踪）；AGENTS.md "本机没有 JDK" 表述与实际（有 JDK 17）不符；`.gitattributes` 注释引用的 `.trellis/spec/cli/...` 路径不存在。
19. 论文前置准备（仓库外）：16 号映射的行号锚定提交 `9af6857`，若重锚到新提交需更新；本仓库改动不影响该锚定。

## 五、已更正、无需行动

20. "分批器按原始长度规划、网关按脱敏后长度检查"的预算错位不是缺陷：脱敏六种模式最短 19 字符、掩码 10 字符，只会变短，原始长度是安全上界。
