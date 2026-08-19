# 清理与 Legacy 说明

2026-08-19 执行仓库切分：

- 当前 ForgePilot 旧系统完整内容已覆盖到 [LinYsssss/reposage](https://github.com/LinYsssss/reposage)，Legacy 基线提交为 `96137dd3b43e14c5e8881c99688663afd979cf4e`。
- ForgePilot 主仓库删除旧后端、旧前端、Agent、Patch、MQ、Sandbox、model-service、旧评测运行结果及历史 Trellis 任务，转为 V2 干净骨架。
- 本地另有 ForgePilot 与原 RepoSage 的完整 Git bundle 和提交快照备份；这些备份不进入公开仓库。

Legacy 代码只有三种合法用途：

1. 按迁移矩阵迁移边界清楚的纯代码及其测试。
2. 参考安全策略、协议处理、评测方法和失败案例。
3. 对照 V2 是否丢失必要业务能力。

禁止把 RepoSage 当作 V2 的依赖、子模块或可整包复制的模板。
