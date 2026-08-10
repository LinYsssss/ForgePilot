# Sandbox Runner Development Guidelines

> `sandbox-runner/`:独立部署的 Spring Boot AMQP worker(无 Web 层),消费 `sandbox.job.queue` 的签名作业,在强约束 Docker 容器里执行仓库只读命令与补丁验证。**它持有 Docker socket,是整个系统的信任边界**——本包的一切取舍都从这一点推导。

---

## Guidelines Index

| Guide | 内容 |
|-------|------|
| [Guidelines](./guidelines.md) | 依赖极简原则、入站信任链、容器加固不变量、路径圈禁、镜像契约与测试写法 |

跨模块契约变更(SandboxJob / WorkspaceArchiveReference)必须走 `.trellis/spec/guides/contract-testing.md` 的双向金标流程。
