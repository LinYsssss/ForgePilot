# 执行计划

1. **D3**（最小）：改 `PullRequestSyncService.apply`；在 `ScmIdentityFreezeConcurrencyTest` 中加测试并改写类注释。
   定向：`-Dtest='ScmIdentityFreezeConcurrencyTest,GitHubWebhookIngestionTest,GitLabWebhookIngestionTest,ScmRepositoryApiTest'`
2. **D1**：`RequirementQualityService` 加 `TransactionTemplate`、内容快照、锁内比较，更新类注释；`RequirementQualityTest` 加两条测试。
   定向：`-Dtest='RequirementQualityTest,Requirement*Test'`
3. **D2**：`RequirementDetailPage.vue` 加质量请求序号；`requirement.spec.ts` 加一条测试。
   前端：`npm run lint && npm run typecheck && npm test && npm run build`
4. 后端完整 verify，按 DEFENSE-GUIDE §2 的容器命令执行（本机只有 JDK 17）。
5. 文档：AUDIT.md 中三项状态改为「已修复（未部署 / 已部署）」；如 TEST-REPORT 需要补充段，按惯例追加。
6. 提交分组（只在本地）：`fix(scm)` / `fix(requirement)` / `fix(web)` / `docs(trellis)`。推送前请用户确认；部署单独确认，部署前先 `pg_dump`，并检查磁盘余量。

回滚点：每个提交都可单独 revert；部署回滚使用上一镜像 `fp-demo-backend:latest`（部署前打 tag 保留）。
