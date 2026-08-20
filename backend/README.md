# Backend

Phase 1 的具体 Trellis 计划经确认并执行 `task.py start` 后，将在这里初始化单个 Spring Boot 模块化单体。

允许的顶层业务包仅为：

```text
common
auth
project
requirement
scm
knowledge
ai
review
```

Phase 1 只建工程底座，不生成业务实体、业务表或空分层目录；登录、项目、需求、知识、SCM 和 Review 从 Phase 2 起按授权纵向实现。
