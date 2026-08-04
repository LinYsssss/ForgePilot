# Implement：功能增强

- [x] F0 读代码定案：AgentFindings/PatchDiffViewer 现状;ReviewTask 幂等键;三个分页端点控制器与测试现状
- [x] F1 A1 PR 直达 Run（useWorkspace + PullRequestsView + 测试）→ fef3d58
- [x] F2 A2 citation 定位增强 + 证据抽屉 → fef3d58
- [x] F3 A4 后端三端点 PageResponse + 测试（容器化 mvn test 全绿,531 例）→ cbfa992
- [x] F4 A4 前端适配 + AI 日志 pager + A3 键盘导航 → fef3d58
- [x] F5 B 幂等语义调整（幂等键按文档集区分）+ 测试 → cbfa992
- [x] F6 B 对比审查：compareReports 纯函数 + 单测 → useReviews.createCompareReview → CompareView 区块 → 3fb5a9f
- [x] F7 端到端演示验证（dev 后端 + demo-repos 本地路径）：E2E OK,4 篇知识文档入库、双任务对比视图完整;mock 口径下两侧一致为预期,已在 research/compare-walkthrough-result.md 记录;途中发现并修复 SPA CSRF 时序缺陷（NullAuthenticatedSessionStrategy + initCsrf 重引导 + SpaCsrfBrowserFlowTest）,截图落 research/shots/
- [x] F8 文档同步（README 核心特性 2 条 + 使用流程步骤 7/8/10 + 常见问题 2 则含 A5 未实施口径）

验证：`cd frontend && npm test && npm run build`;`docker run --rm -v /root/reposage:/ws -v reposage-m2:/root/.m2 -w /ws/backend maven:3.9-eclipse-temurin-17 mvn -q -s .mvn/settings.xml test`
风险：后端幂等语义改动（必须先读懂既有测试的断言意图）;对比视图的匹配算法过拟合(保持简单可解释)。
