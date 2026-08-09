# Design：沙箱归档断链修复

## 契约冲突的三个候选方案（F-03）

| 方案 | 内容 | 优点 | 缺点 |
| --- | --- | --- | --- |
| A | Runner 识别并剥离 `workspace://` scheme | 改动最小，仅 Runner 一处 | 安全校验逻辑被打洞：`:` 拒绝规则要加白名单例外，校验复杂化；后端仍可随意变格式 |
| B | 后端改产出裸文件名（`agent-run-{id}-{sha}.tar`） | Runner 安全校验保持最严（拒绝一切含 `:`）；改动仅后端一处 | 引用丢失"这是工作区归档"的语义；后端 `InputValidation` 仍与 Runner 规则不同源 |
| **C（推荐）** | 抽共享的引用编解码器：`common` 契约模块（或双侧同构类 + 契约测试锁定）定义 `WorkspaceArchiveReference.encode()/parse()`，后端用 encode，Runner 用 parse，parse 内做全部安全校验 | 单一事实源，两侧不可能再漂移；安全规则集中一处；契约测试天然有落点 | 改动面最大（两模块 + 可能新增共享构件） |

**推荐 C 的理由**：F-03 的根因不是格式选错，而是**契约没有单一事实源**——两侧各写各的校验，测试各测各的假数据。A/B 修得了这一次，防不了下一次。仓库已有 `fix/defense-hardening` 分支"freeze the contracts shared by both hardening tracks"的先例（`aae3446`），说明共享契约模式在本仓库已被接受。

**C 的落地形态**（供服务器上实施时定夺）：若建共享 Maven 模块成本高（两个独立 pom），可退化为 **C'**：两侧各放一份同构的 `WorkspaceArchiveReference` 类 + 一个「双向契约测试」目录（后端产出喂 Runner 解析、Runner 拒绝集喂后端校验），由测试保证同构。C' 保持模块独立性，代价是同构靠测试而非编译期保证——对本仓库规模够用。

## 归档生产者（F-04）

推荐：**backend 在派发沙箱作业前生成归档**（`git archive` 已 clone 的仓库 → tar 写入共享卷），composer 侧：

```yaml
backend:
  volumes:
    - sandbox_archives:/app/archives          # 读写
sandbox-runner:
  volumes:
    - sandbox_archives:/app/archives:ro       # Runner 保持只读
```

- Runner 侧 `:ro` **保留**——审计确认的"Runner 不接收密钥、最小权限"边界不动，只读归档即可取证。
- 归档生命周期：作业完成后由 backend 清理（或按 run 状态定期清理），防卷膨胀；命名含 runId+sha 天然幂等。
- 不选对象存储：单机 Compose 演示环境，引入 MinIO 等属过度设计（YAGNI）；引用格式经 C 方案编解码器隔离，将来若迁对象存储只改编解码器与生产者两点。

## 快速失败（F-05）

`ProdSecretValidator` 现有机制已在生产 profile 校验密钥长度等，把 `SANDBOX_TOOL_IMAGE` 并入同一校验器：非空 + 匹配 `.+@sha256:[0-9a-f]{64}`（与 `EvaluationCorpusService.java:26` 的格式要求一致，考虑直接复用其校验逻辑避免两处正则）。

## 风险与回滚

- 本批改动触及消息契约（SandboxJob 载荷不变，仅引用字符串格式变化）——**部署时后端与 Runner 必须同版本上线**，不能滚动混跑；单机 Compose `up -d --build` 天然满足。
- 回滚：整批 revert 即回到"断链但无害"的现状（沙箱步骤失败不影响其他功能）。
- ADR：修复合入时写一条 ADR（归档引用契约的单一事实源选择），满足"难逆转/无上下文会困惑/真实权衡"三条件。
