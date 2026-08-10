# Implement：评测集扩充 6→30-50 例

0. [ ] `mvn test` 复证裸 ObjectMapper 对 record 未知字段抛异常(design D2 前提;若不炸则简化 schema 方案并回写 design)。
1. [ ] Schema 扩展(独立提交):`EvaluationReport`(fixtureLayout/lineEnd/categoryEquivalents)+ `EvaluationCorpusService.validate` 新规则 + 测试 fixture;容器 `mvn -s .mvn/settings.xml verify` 绿。
2. [ ] 工具链(独立提交):`evaluation/tools/{build-case-repos.sh,run-baseline.sh,score.py,category-aliases.json}` + `evaluation/README.md`(真实校验入口 + D3 口径成文);score.py 自带单测样例(手工小矩阵可复算)。
3. [ ] temperature 对齐(独立提交,r6 归档后):`OpenAiCompatibleReviewClient` 0.2 硬编码 → 读 `app.ai.temperature`(默认 0.0)+ 单测;`mvn verify` 绿。
4. [ ] 用例分批落地(按类别一批一提交,五批):base/head(+knowledge) + manifest 标注;每批后容器 `mvn test`(EvaluationCorpusServiceTest 过真实 manifest)+ `build-case-repos.sh` 干跑校验两态可构建;抽 1 例人工核对行号区间。
5. [ ] 全量校验:42 例 manifest 过校验;`git diff --stat demo-repos/ evaluation/cases/{原6例}` 零改动;五类配比达标复点。
6. [ ] 基线跑分(r6 归档后):隔离栈起 → run-baseline.sh 全量(42 例)→ score.py 出两率 → `baseline-mimo-<date>.{json,md}` 落任务目录(含模型/日期/temperature 实值/调用与 token 实数/分类别分 split 明细)→ 隔离栈 down -v。
7. [ ] 抽查 5 例人工复算命中判定(AC),记录进基线档案附录。
8. [ ] `trellis-check`(Agent)→ 有价值教训 `trellis-update-spec` → 提交推送 → `/trellis:finish-work`。

风险文件:backend evaluation 包(字段扩展)、ai 包(temperature 一行)、evaluation/ 全目录。
回滚点:每步独立提交;隔离栈无残留。
产物:42 用例 + 工具三件套 + README 口径 + 基线档案。
