# Implement：评测集扩充 6→30-50 例

0. [x] `mvn test` 复证裸 ObjectMapper 对 record 未知字段抛异常(design D2 前提;若不炸则简化 schema 方案并回写 design)。(实证:炸——UnrecognizedPropertyException 包装为 IllegalArgumentException 抛出,测试 unknownCaseFieldFailsLoudly* 钉死,无需回写;commit 3f53398)
1. [x] Schema 扩展(独立提交):`EvaluationReport`(fixtureLayout/lineEnd/categoryEquivalents)+ `EvaluationCorpusService.validate` 新规则 + 测试 fixture;容器 `mvn -s .mvn/settings.xml verify` 绿。(commit 6972a47;后端全量 606/0)
2. [x] 工具链(独立提交):`evaluation/tools/{build-case-repos.sh,run-baseline.sh,score.py,category-aliases.json}` + `evaluation/README.md`(真实校验入口 + D3 口径成文);score.py 自带单测样例(手工小矩阵可复算)。(commit cf55fb7;--selftest 14 项;隔离栈具体化另行 b894223)
3. [x] temperature 对齐(独立提交,r6 归档后):`OpenAiCompatibleReviewClient` 0.2 硬编码 → 读 `app.ai.temperature`(默认 0.0)+ 单测;`mvn verify` 绿。(commit 3f53398,与 langchain4j 路径同键同默认)
4. [x] 用例分批落地(按类别一批一提交,五批):base/head(+knowledge) + manifest 标注;每批后容器 `mvn test`(EvaluationCorpusServiceTest 过真实 manifest)+ `build-case-repos.sh` 干跑校验两态可构建;抽 1 例人工核对行号区间。(实况:并行创作改为碎片汇交,主会话统一合并 26 例入 manifest 后一次提交 840ee92;mvn 过 32 例校验、建仓 32/32、抽 2 例行号精确命中——批次粒度偏离计划,证据链等效)
5. [x] 全量校验:~~42 例~~ 32 例 manifest 过校验;`git diff --stat demo-repos/ evaluation/cases/{原6例}` 零改动(已复验);配比复点(如实):业务 8、工程 8、漏报专项 6、误报专项 4,新例 26+老 6=32≥30,总数 AC 达标;**D5 安全类批次(目标 10)未交付**——创作代理按「不硬凑」纪律如实缺交,现语料安全正例仅 miss-template-share-authz(AUTH)与老例 typescript-known-patch(PATH_TRAVERSAL),Java 越权/SQL 注入正例为零;此为 PRD「五类配比达标」的**已声明偏差**,缺口已录入 r8 待回灌候选表(research/r2-checklist-evidence.md §2.3),基线分类别表同步暴露该缺口(AUTH/PATH_TRAVERSAL 分母极小)。
6. [x] 基线跑分(r6 归档后):隔离栈起 → run-baseline.sh 全量(32 例)→ score.py 出两率 → `baseline-glm-2026-08-12.{json,md}` 落任务目录(含模型/日期/temperature 实值/调用与 token 实数/分类别分 split 明细)→ 隔离栈 down -v。(实况:模型为 z-ai/glm-5.2 非 design 预期的 mimo——.env 已切换,档案声明偏差;首轮限流事故 5 例失败,resume×2+熔断窗清零后 32/32;档案含事故记)
7. [x] 抽查 5 例人工复算命中判定(AC),记录进基线档案附录。(5/5 与判分器一致,附录含逐例推理)
8. [ ] `trellis-check`(Agent)→ 有价值教训 `trellis-update-spec` → 提交推送 → `/trellis:finish-work`。

风险文件:backend evaluation 包(字段扩展)、ai 包(temperature 一行)、evaluation/ 全目录。
回滚点:每步独立提交;隔离栈无残留。
产物:42 用例 + 工具三件套 + README 口径 + 基线档案。
