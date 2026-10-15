# 交付文档补充段与仓库卫生

## Goal

本轮改动（Provider 读取失败错误码、密文失败受控化、embed 未配置错误码、知识删除留痕计数、软删需求活动聚合、知识入库分批、前端项目选择抽取与 changeType 中文化）落地后，按 2026-09-30「源码补充（尚未部署）」惯例为交付文档追加带日期的补充段；并处理三项小卫生：TESTING-GUIDE K 组负向用例、AGENTS.md JDK 措辞、.gitattributes 过期路径引用、空 node_modules/.vite 残留。

## Requirements

- `docs/deliverables/README.md`、`MANUAL.html`、`OPERATIONS.html`、`SECURITY.html` 追加「2026-10-14 源码补充（尚未部署）」段落；只追加，不改写既有快照、日期与数字。
- `TEST-REPORT.html` 的补充段引用本轮最终 CI run 的实际数字，因此在其全绿后单独提交。
- `docs/v2/TESTING-GUIDE.md` K 组新增一条负向用例：Provider 不可用时投递返回 503 `provider_unavailable`（编号顺延 K10，表格与正文同步）。
- `AGENTS.md`：`There is no JDK on this host.` 改为准确表述（本机有 JDK 17、无 JDK 21）。
- `.gitattributes` 注释中不存在的 `.trellis/spec/cli/...` 路径改为真实指引（`.trellis/workflow.md` 的 Workspace System）。
- 删除未跟踪的空缓存残留：根目录 `node_modules/.vite` 与 `frontend/node_modules/.vite`（先确认其中无文件）。

## Acceptance Criteria

- [ ] 四份 HTML/README 各有一节 2026-10-14 补充段，措辞与既有补充段同构；快照日期与数字未动。
- [ ] TESTING-GUIDE K 组表格与正文含 K10，格式与既有条目一致。
- [ ] `grep "spec/cli" .gitattributes` 无结果；AGENTS.md 不再声称本机没有 JDK。
- [ ] 两处 .vite 目录已删除；`git status` 无新增未跟踪残留。
- [ ] TEST-REPORT 补充段在最终 CI 全绿后以该 run 的实际数字提交。

## Notes

- 轻量任务，PRD-only。
- 交付文档是面向读者的成品，冲突时以 `docs/v2/` 为准；此处只追加补充段。
- 语料更正（evaluation）不写进交付 HTML，其记录在 `evaluation/README.md` 与 TEST-REPORT 的验证数字中。
- 本机无浏览器手工验收；本轮补充段不含浏览器结论。
