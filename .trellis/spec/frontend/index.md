# Frontend Development Guidelines

> RepoSage 前端(`frontend/`,Vue 3 + Vite 6 + vue-router hash 模式,纯 JS 无 TS/Pinia,node 内置 test runner)的开发规范。

---

## Guidelines Index

| Guide | 内容 |
|-------|------|
| [Directory Structure](./directory-structure.md) | src 布局、views/composables/api/utils 职责边界、nav 接缝 |
| [State Management](./state-management.md) | 模块级单例 composable 模式、领域划分、401/SSE 硬规则 |
| [Component Guidelines](./component-guidelines.md) | SFC 写法、props/emits、Observatory 设计系统、逻辑抽离测试 |
| [Quality Guidelines](./quality-guidelines.md) | lockfile 源纪律、API 边界容错、node --test 测试写法 |

后端契约(分页信封、ErrorCode、REST 字段)见 `.trellis/spec/backend/frozen-contracts.md`;
| [Streaming API](./streaming-api.md) | 带 JSON body 的 fetch SSE、增量解析、generation guard 与内存会话生命周期 |
跨包纪律见 `.trellis/spec/guides/`。
