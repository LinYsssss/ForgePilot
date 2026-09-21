# 实施计划

## 1. 实现证据锚定

- [x] 阅读 `ReviewOutputValidator`、`ChangedFileBatcher` 及现有测试 fixture，确认 patch 表示和 warning 约定。
- [x] 在 Validator 内实现按 hunk 隔离的新侧源码索引与逐字引用匹配。
- [x] 将 Finding evidence 和 AC excerpt 接入同一锚定方法，按无命中/唯一命中/多命中规则处理行号与 warning。
- [x] 保持 `finding_key`、`evidence_hash`、Prompt/schema 和公开 DTO 契约不变。

## 2. 修复需求质量超预算路径

- [x] 在 `RequirementQualityService` 中让超预算路径跳过 `AiGateway`，保存 `ai=null` 的确定性结果。
- [x] 更新规则文案与 `QUALITY_VERSION=quality-2`，保持预算内路径不变。

## 3. 最小定向测试

- [x] 调整受严格锚定影响的既有 fixture，使 evidence/excerpt 使用不含 diff `+` 前缀的真实源码文本。
- [x] 增加唯一命中纠行、虚构 Finding/AC 引用拒绝、重复引用无法消歧时行号为空等关键测试。
- [x] 在现有超预算质量测试中断言 `ai=null`、provider 零调用、持久化成功及版本升级。
- [x] 将只想验证 hash 变化的测试直接落到 `FindingKeys`，不再依赖 Validator 接受虚构证据。

## 4. 文档同步

- [x] 更新权威 Markdown：`ARCHITECTURE.md`、`API.md`、`PRD.md`、`TESTING-GUIDE.md`。
- [x] 更新验收与交付索引：`frontend/MANUAL-ACCEPTANCE.md`、`docs/deliverables/README.md`。
- [x] 更新五份直接相关 HTML：`MANUAL`、`SECURITY`、`WALKTHROUGH`、`SRS`、`TEST-REPORT`。
- [x] 保留全部历史快照与正式实验口径，只追加本轮真实变化和检查结果。

## 5. 验证顺序

1. 运行 `ReviewOutputValidatorTest`、`ChangedFileBatcherTest`、`RequirementQualityTest`。
2. 通过仓库文档指定的固定容器路径运行后端完整 `mvn verify`。
3. 运行前端 lint、typecheck、现有测试和 production build，不新增前端测试。
4. 使用 Python 标准库 `HTMLParser` 解析五份修改后的 HTML。
5. 搜索过时描述，确认不存在“超预算仍截断后分析”或“证据只需非空”等冲突表述。
6. 依据本次真实输出更新 `TEST-REPORT.html`，不得预填测试数量。
7. 运行 `git diff --check`，核对工作树只含计划内文件并人工审阅 diff。

### 实际结果（2026-09-20）

- 四个定向测试类共 59 项：0 失败、0 错误、0 跳过。
- 后端完整 `verify` 共 419 项：0 失败、0 错误、0 跳过，打包成功。
- 前端 lint、typecheck、63 项 Vitest（18 个文件）与 production build 全部通过。
- 五份修改后的 HTML 均通过 Python 标准库 `HTMLParser`；冲突表述搜索与 `git diff --check` 通过。
- 工作树仅包含计划内的后端实现/测试、直接受影响文档与本任务记录；正式评测、holdout、原始输出和生产复验证据未修改。

## 6. 风险控制点

- 实现前记录基线工作树；不接触正式评测冻结、holdout 台账或原始输出。
- 定向测试失败时先修复解析/fixture，不放宽逐字锚定规则来迎合旧测试。
- 完整门禁失败若属于既有环境问题，保留日志并区分实现缺陷与环境缺口。
- 提交前按 Trellis 展示提交分组，获得用户确认后再 commit；不自动 push。
