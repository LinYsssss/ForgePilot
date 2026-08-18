# P9 ForgePilot 新仓库与全历史身份重写迁移

> 父任务：`08-16-forgepilot-upgrade`（R12）
> 执行日期：2026-08-18
> 用户最终决策：新建 public 仓库 `LinYsssss/ForgePilot`；旧 `LinYsssss/reposage` 完整保留。仅在新仓库副本中，把全部历史 commit 的 Author 和 Committer 统一改为 `LinYsssss <153968692+LinYsssss@users.noreply.github.com>`，接受所有 commit SHA 改变。

## Goal

创建 ForgePilot 新主仓库，在不改变旧 RepoSage 仓库的前提下，迁移所有远程分支、本地分支、tags、文件内容和提交拓扑；在新副本中统一历史提交身份，并用 commit-map、ref 对照和 tree/message/topology 校验完整性。新仓库 README 记录迁移、旧仓库保留和后续贡献入口。

## Confirmed facts

- 旧仓库 `LinYsssss/reposage`：public、未归档、default `main`，main=`ce83abfbc764ae88a4a62e46a02446ea130382ee`。
- 目标 `LinYsssss/ForgePilot` 名称可用；GitHub CLI 已认证且有 repo/workflow 权限。
- 旧仓库当前有 381 个 reachable commits、多个远程/本地分支、无 tags、无 Git LFS 文件。
- 用户明确接受新副本所有 commit SHA 改变；旧仓库保留原始 SHA 作为历史备份。

## Requirements

- 创建空 public 仓库 `LinYsssss/ForgePilot`，不生成独立根提交。
- 临时 bare mirror 从旧远程取得全部 remote heads/tags，再从当前本地仓库补齐 local-only heads/tags。
- 仅在临时 mirror 中重写所有 commit：
  - Author name/email = `LinYsssss <153968692+LinYsssss@users.noreply.github.com>`
  - Committer name/email = 同上
  - author/committer dates、message、parent topology 和 tree content 保持不变
- 保存 `git-filter-repo` commit-map，建立 old SHA → new SHA 的完整映射。
- mirror push 重写后的全部 branches/tags 到新仓库；旧仓库不 force-push、不接收任何重写历史。
- 在新仓库重写后的 main 上增加迁移说明提交：更新 README badge/current URL/clone 命令，增加迁移记录，并可增加任务 migration report/commit-map。
- 当前 `F:\202605New` 工作副本保留原始历史和旧 `origin`，避免把未重写本地历史误推到新仓库；额外添加 `forgepilot` remote 仅用于核验。后续开发应从新仓库 fresh clone。
- P8 保持 `in_progress`；新仓库迁移不代表真实模型矩阵完成。

## Out of scope

- 删除、归档、重命名或 force-push `LinYsssss/reposage`。
- 在当前旧历史工作副本中执行 destructive history rewrite。
- Java package、Spring application name、数据库/MQ/metrics 内部标识全局改名。
- 保留原 commit 签名：身份重写会使原签名失效，这是已接受影响。

## Acceptance Criteria

- [ ] 新 public 仓库创建，default branch 为 main。
- [ ] 全部旧远程 heads/tags 和本地 heads/tags 均迁移到新仓库。
- [ ] 新仓库所有历史 commit 的 Author/Committer 均为确认身份，无旧身份残留。
- [ ] commit-map 行数覆盖所有重写 commit；抽样/全量验证 tree、message、dates、parent 拓扑保持。
- [ ] 新 README 和 migration report 明确旧仓库保留、SHA 已重写、新贡献入口。
- [ ] 旧仓库仍 public/unarchived，main 仍为 `ce83abf`。
- [ ] 当前工作副本未重写，旧 origin 未被替换；新增 forgepilot remote 指向新仓库。
- [ ] P8 状态仍 in_progress，无完成误报。
