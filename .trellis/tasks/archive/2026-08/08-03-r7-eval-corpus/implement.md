# Implement：评测集扩充 6→30-50 例

> **进度快照（2026-08-12）**：语料已补齐到 38 例（development 26 / holdout 12），安全类达到 PRD 下限 8 例；manifest 校验、判分器自测和 38/38 确定性建仓均通过。原 32 例 GLM 基线保留为历史记录，当前仅剩 38 例真实模型复跑、`trellis-check`、规范教训沉淀与正式收尾。

0. [x] `mvn test` 复证裸 ObjectMapper 对 record 未知字段抛异常(design D2 前提;若不炸则简化 schema 方案并回写 design)。(实证:炸——UnrecognizedPropertyException 包装为 IllegalArgumentException 抛出,测试 unknownCaseFieldFailsLoudly* 钉死,无需回写;commit 3f53398)
1. [x] Schema 扩展(独立提交):`EvaluationReport`(fixtureLayout/lineEnd/categoryEquivalents)+ `EvaluationCorpusService.validate` 新规则 + 测试 fixture;容器 `mvn -s .mvn/settings.xml verify` 绿。(commit 6972a47;后端全量 606/0)
2. [x] 工具链(独立提交):`evaluation/tools/{build-case-repos.sh,run-baseline.sh,score.py,category-aliases.json}` + `evaluation/README.md`(真实校验入口 + D3 口径成文);score.py 自带单测样例(手工小矩阵可复算)。(commit cf55fb7;--selftest 14 项;隔离栈具体化另行 b894223)
3. [x] temperature 对齐(独立提交,r6 归档后):`OpenAiCompatibleReviewClient` 0.2 硬编码 → 读 `app.ai.temperature`(默认 0.0)+ 单测;`mvn verify` 绿。(commit 3f53398,与 langchain4j 路径同键同默认)
4. [x] 用例分批落地(按类别一批一提交,五批):base/head(+knowledge) + manifest 标注;每批后容器 `mvn test`(EvaluationCorpusServiceTest 过真实 manifest)+ `build-case-repos.sh` 干跑校验两态可构建;抽 1 例人工核对行号区间。(实况:并行创作改为碎片汇交,主会话统一合并 26 例入 manifest 后一次提交 840ee92;mvn 过 32 例校验、建仓 32/32、抽 2 例行号精确命中——批次粒度偏离计划,证据链等效)
5. [x] 全量校验:38 例 manifest 过校验；安全 8、业务 8、工程 8、漏报专项 6、误报专项 4，development 26 / holdout 12；新增安全例为 Java IDOR×2、SQL 注入×2、CSRF×1、路径穿越×1；38/38 确定性建仓通过，`demo-repos/` 与原 6 例零改动。
6a. [x] 历史基线跑分:原 32 例用 `z-ai/glm-5.2` 跑成 32/32，漏报率 36.00%（9/25）、误报率 81.82%（72/88），落档 `baseline-glm-2026-08-12.{json,md}`；该数字仅保留为扩容前历史基线。
6b. [ ] 38 例基线复跑:沿用 `z-ai/glm-5.2`、temperature 0.0 与既定重试参数，全量跑成后重新判分并落新档；不得把 32 例数字作为 38 例结果。（当前本机无 Docker 命令且无 `deploy/.env`，需在已配置真实模型凭据与 Docker 的服务器执行。）
7. [x] 抽查 5 例人工复算命中判定(AC),记录进基线档案附录。(5/5 与判分器一致,附录含逐例推理)
8. [ ] `trellis-check`(Agent)→ 有价值教训 `trellis-update-spec` → 提交推送 → `/trellis:finish-work`。

风险文件:backend evaluation 包(字段扩展)、ai 包(temperature 一行)、evaluation/ 全目录。
回滚点:每步独立提交;隔离栈无残留。
产物:38 用例 + 工具三件套 + README 口径 + 32 例历史基线档案；38 例新基线待生成。
