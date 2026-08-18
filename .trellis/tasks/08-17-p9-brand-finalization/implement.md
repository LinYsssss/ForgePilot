# Implement：ForgePilot 全历史身份重写迁移

## 0. Preflight

- [x] 用户批准新 public 仓库、旧仓库保留。
- [x] 用户确认全部 Author/Committer 统一为 `LinYsssss <153968692+LinYsssss@users.noreply.github.com>`。
- [x] 用户接受新仓库全部 commit SHA 改变和签名失效。
- [x] 目标仓库名称可用，gh 已认证，旧仓库 admin/public/unarchived。
- [x] 记录旧 main `ce83abf...`、381 commits、分支集合、无 tags/LFS。

## 1. Tooling and mirror

- [ ] 安装/验证 `git-filter-repo`。
- [ ] 创建系统临时 bare mirror，从旧远程取得全部 refs。
- [ ] 从当前本地仓库补齐 local-only heads/tags。
- [ ] 保存 rewrite 前 refs 与 commit metadata 清单。

## 2. Rewrite and verification

- [ ] 执行全 commit Author/Committer callback rewrite。
- [ ] 保存 commit-map；全量验证 identity、tree/message/date/parent topology。
- [ ] 确认当前工作副本和旧远程未被重写。

## 3. Create and push new repository

- [ ] 创建 `LinYsssss/ForgePilot` public 空仓库。
- [ ] mirror push 所有重写 branches/tags。
- [ ] 验证新远程 refs/default main。

## 4. README/migration record

- [ ] fresh clone 新仓库 main。
- [ ] 更新 README badge、URL、clone 命令和迁移章节。
- [ ] 写入 P9 migration report、commit-map/ref summaries。
- [ ] 更新当前部署文档 clone/cd 路径；不改 archive/internal identifiers。
- [ ] 创建迁移说明 commit 并推送新 main。

## 5. Final verification/local remotes

- [ ] GitHub API/`git ls-remote` 验证新旧仓库状态和 refs。
- [ ] 当前旧工作副本添加 `forgepilot` remote，保留旧 `origin`。
- [ ] 记录新 main SHA、旧 main SHA、commit-map 数、ref 数、README URL。
- [ ] P9 check；P8 保持 in_progress。
