# tenant-user-center

多租户用户中心（演示用）。**Python + JavaScript 双语言**，用于验证多语言审查能力。

| 目录 | 语言 | 职责 |
|---|---|---|
| `src/app/` | Python (FastAPI) | 接口、认证、数据访问 |
| `web/` | JavaScript | 管理后台前端片段 |

## 架构要点

多租户**共享库**：所有租户数据在同一套表里，靠 `tenant_id` 隔离。少一个过滤条件就是一次跨租户泄露。

## 规范

- 租户隔离：`docs/tenant-isolation.md`
- 认证与密码：`docs/auth-policy.md`
- 接口约定：`docs/api-contract.md`
- 历史事故：`docs/bug-history.md`
