# Design：ForgePilot identity-rewritten mirror migration

## 1. Safety boundary

历史重写只发生在系统临时目录中的 bare mirror。当前 `F:\202605New` 和旧 GitHub 仓库均不执行 filter/force-push；旧仓库是原始 SHA 的永久备份。

## 2. Ref collection

1. `git clone --mirror` 旧 GitHub 仓库，取得全部远程 heads/tags。
2. 从当前本地仓库 fetch `refs/heads/*` 与 `refs/tags/*` 到 mirror，补齐 local-only branches。
3. 在重写前保存 ref 清单、commit 数、commit metadata/tree/parents 清单。

## 3. Identity rewrite

使用 `git-filter-repo` commit callback，对每个 commit 同时设置：

```text
commit.author_name = b"LinYsssss"
commit.author_email = b"153968692+LinYsssss@users.noreply.github.com"
commit.committer_name = b"LinYsssss"
commit.committer_email = b"153968692+LinYsssss@users.noreply.github.com"
```

不修改 dates、message、tree 或 parents。保留 filter-repo `commit-map` 作为审计证据。重写会改变所有 SHA，并使原签名无效。

## 4. New repository and README

- 通过 `gh repo create LinYsssss/ForgePilot --public` 创建空仓库。
- 将重写 mirror 推送到新仓库。
- fresh clone 新仓库 main，在重写后的历史上新增迁移提交：
  - README badge/URL/clone 使用 ForgePilot；
  - 新增迁移章节和旧仓库链接；
  - 添加 `.trellis/tasks/08-17-p9-brand-finalization/migration/commit-map.txt` 与报告；
  - 更新当前部署文档 clone/cd 路径，不动 archive/internal identifiers。

## 5. Verification

- `git log --all --format` 全量检查 Author/Committer identity。
- commit-map 非零旧 SHA 行必须全部有新 SHA；行数与重写 commit 集一致。
- 对 old/new commit-map 每一行校验 commit message、author/committer date、tree 等价；parents 通过 commit-map 映射后等价。
- 比较旧 heads/tags + local heads/tags 与新远程 refs 的名称集合。
- GitHub API 检查新仓库 public/default main；旧仓库 public/unarchived/main SHA 不变。

## 6. Local remote policy

当前工作副本因保留旧 SHA，继续使用旧 `origin`；添加 `forgepilot` remote 用于 fetch/核验，但不将当前 main upstream 切到新历史。后续开发从 `ForgePilot` fresh clone，避免 unrelated-history 推送。
