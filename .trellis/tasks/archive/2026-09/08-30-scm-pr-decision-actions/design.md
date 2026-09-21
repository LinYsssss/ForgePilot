# Technical design

在 `scm.github.GitHubClient` 增加基于已存仓库凭据的 merge、close、delete-branch 方法；使用 PR 详情中的 head ref 和仓库默认分支。`ReviewDecisionService.decide` 在本地决策事务成功后调用一个 SCM facade，GitHub 执行远程动作，GitLab no-op。远程失败向调用方返回错误，不改变已落库的 Review 决策。

不新增表或迁移；不引入通用 provider 抽象之外的新模块。默认分支保护只在删除分支前比较 head ref 与 PR base/default branch。
