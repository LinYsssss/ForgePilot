# 实施与验证

1. 已完成：核对分支领先 main 8 个提交、无既有 PR、工作区干净；主仓库不在本系统 SCM 绑定中，创建 PR 不会触发本系统额外审查。已校验私有证据，已归档 guidance-3 并修复引用。
2. 从校验后的四份输入/输出导出 LF 格式摘要 CSV，记录新数据集 SHA-256。
3. 编写只读标准库复算脚本和论文小节，脚本校验摘要表一致；更新论文入口。
4. 给测试报告添加当前/历史索引，去掉当前入口中随合并即过时的分支状态句子，实际部署仍记 54e2953。
5. 在现有 evaluation CI job 加入论文复算循环；本地运行五个 verifier、链接/编码/diff 和冻结/订正检查。无需重跑本机后端/前端全套。
6. 提交材料与任务记录，推送当前修复分支；按正常工作流创建 PR、等待 CI 并合并 main，不强推、不删除分支、不使用管理员绕过。
7. 如主分支新增业务代码、产生业务冲突或需要人工审批，停止并报告。合并后安全快进本地 main，并确认运行代码未变，不重新部署。

主要文件：docs/thesis/GUIDANCE-ACCEPTANCE.md、guidance-acceptance-20261003.csv、verify-guidance-acceptance.py、README.md；docs/deliverables/TEST-REPORT.html；README.md、docs/v2/DEFENSE-GUIDE.md；.github/workflows/ci.yml。

完成时记录真实校验结果及 PR/CI 入口；不把本轮说成论文定稿、全站浏览器验收或新一轮 review-5 模型评测。
