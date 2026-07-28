# Track B 执行计划书（安全边界与交付线）

> 分支：`fix/track-b-boundary`（基线 `fix/defense-hardening` @ `aae3446`）
> 编制：2026-07-28
> 依据：`docs/毕业设计答辩版最终整改与验收方案.md`、`docs/并行实施拆分方案.md`、`docs/TrackA交接说明.md`
> 前置：Track A 已完成并交接（11 提交 / 410 tests / 全部 P0 关闭）

本文只写 Track B。所有状态均**经本机实测核实**，不引用未验证的结论。

---

## 一、基线核实（2026-07-28 实测）

| 项 | 实测结果 |
|---|---|
| 分支位置 | `origin/fix/track-b-boundary` @ `ffa3040`，工作区有 B2 未提交改动 4 文件 |
| 后端测试 | 360 tests / 0 failures（`mvn clean test`，含 B 已提交的 3 个提交） |
| 共享契约可用性 | `ErrorCode`、`PageResponse`、`ProjectAuthorization` **均在 Phase 0**，B 可直接消费 |
| 本机环境 | Docker 29.6 + Compose v5.3.1 + **运行中的 PostgreSQL/pgvector** |

**与交接文档的关键差异：本机具备 Docker 与真实 PostgreSQL。**
交接文档将「迁移链从未被任何数据库执行」「Compose smoke」「镜像扫描」列为共同阻塞项，在本环境**可以实际验证**。这是本计划相对原方案的唯一增量能力，将用于把若干「未验证」转为真实证据（见第五节）。

---

## 二、B 侧问题清单与当前状态

### 2.1 P0（答辩前必须修复）

| 编号 | 问题 | 状态 | 证据 |
|---|---|---|---|
| P0-10 | 生产启动校验不完整且无自动化测试 | ✅ **已完成** | `ffa3040`；新增 SANDBOX_SIGNING_SECRET / Cookie / TTL / Seed 口令策略校验 + 5 个启动失败测试 |
| P0-06 | Backend 健康检查依赖 curl 但镜像未安装 | ⚠️ **实际未复现，但需固化** | 实测容器内 `/usr/bin/curl` 存在（base 镜像自带 8.18.0），healthcheck 现已 healthy。但 `Dockerfile` 只显式装 `git ca-certificates`，**依赖基础镜像的隐式提供，换 base 即失效** |
| P0-07 | Node 版本冲突且 Docker 用 npm install | ❌ **未修复** | `package.json engines >=20 <23`、`Dockerfile FROM node:24-alpine`、`ci.yml node-version 20`、`Dockerfile RUN npm install`（非 `npm ci`） |
| P0-08 | 前端 2 个 high 依赖漏洞 | ❌ **未修复** | 实测 `npm audit`：`vite` GHSA-fx2h-pf6j-xcff、`launch-editor` GHSA-v6wh-96g9-6wx3，共 2 high |
| P0-09 | CI 未运行 Sandbox Runner 测试 | ❌ **未修复** | `ci.yml` 中 `sandbox-runner` 出现 0 次，37 个沙箱测试不在合并门禁内 |

**结论：B 侧尚有 3 个真 P0（P0-07/08/09）+ 1 个需固化项（P0-06），全部落在 B9/B10。**
原计划把 B9/B10 排在最后，与 P0 优先级矛盾，本计划予以纠正（见第三节）。

### 2.2 P1

| 编号 | 问题 | 状态 | 备注 |
|---|---|---|---|
| P1-01 | Git URL SSRF | ✅ 已完成 | `ec67e35`，`OutboundUrlPolicy` + 26 测试 |
| P1-23 | Git 子进程管道阻塞与错误回显 | ✅ 已完成 | `ec67e35`，异步限量 drain + 脱敏 + 死锁回归测试 |
| P1-18 | 登录响应返回 Token | 🔄 代码完成未提交 | 工作区 |
| P1-19 | 登录缺独立防爆破与时序缓解 | 🔄 代码完成未提交 | IP 维度已在 `e10c4f5`；username 维度 + dummy BCrypt 在工作区 |
| P1-20 | 认证输入缺白名单 | 🔄 代码完成未提交 | 工作区 |
| P1-02 | ScmHttpSupport 私网绕过 | ❌ 未做 | 需复用 `OutboundUrlPolicy` |
| P1-03 | CSRF | ❌ 未做 | B3 |
| P1-06/07/21 | Webhook 验签顺序、preview 脱敏、并发幂等 | ❌ 未做 | B5。**唯一约束 `uq_scm_delivery_provider_delivery` 实测已存在，无需 V25** |
| P1-08 | CryptoService 非 v1 明文透传 | ❌ 未做 | B6，需 V26 迁移 |
| P1-09/16 | 限流信任任意 XFF、安全响应头 | ❌ 未做 | B6 + B10 |
| P1-17 | 安全审计事件分散 | ❌ 未做，**原 B 任务清单遗漏** | 本计划新增为 B11 |
| P1-13/14 | 前端硬编码、测试质量 | ❌ 未做 | B9。注意当前 `auth.username` 默认值为 `'ysainlin'`，**是我此前引入的硬编码，需清空** |
| P1-22 | Model Service | ❌ 未做 | B7 |
| P1-24 | 供应链门禁只覆盖 npm | ❌ 未做 | B10 |
| P1-25 | SandboxReplayGuard 无界 Set | ❌ 未做 | B8 |

---

## 三、执行顺序（按风险与依赖重排）

原 B1→B10 顺序把仅剩的 P0 排在最后。调整为：

| 阶段 | 任务 | 依据 | 预估 |
|---|---|---|---|
| **1** | **B2 收尾**（提交工作区改动 + 补测试） | 代码已完成，不落地即浪费 | 0.3 天 |
| **2** | **B10-P0 部分**：Node 三处统一 + `npm ci` + `npm audit` 清零 + CI 纳入 sandbox-runner + Dockerfile 显式装 curl | **P0-06/07/08/09**，唯一剩余 P0 | 0.7 天 |
| **3** | **B5** Webhook 验签顺序 + ON CONFLICT 幂等 + preview 脱敏 | P1-06/07/21，无需迁移，改动内聚 | 0.5 天 |
| **4** | **B6** CryptoService 非 v1 拒绝 + V26 迁移 + 可信代理 | P1-08/09，含 B 段唯一迁移 | 0.7 天 |
| **5** | **B7/B8** Model Service + ReplayGuard | P1-22/25，100% B 独占、零冲突 | 0.7 天 |
| **6** | **B4 补全** ScmHttpSupport 复用 OutboundUrlPolicy | P1-02，B4 的遗留半边 | 0.2 天 |
| **7** | **B11**（新增）最小安全审计事件 | P1-17，原清单遗漏 | 0.4 天 |
| **8** | **B3** SPA CSRF | P1-03。**放靠后**：开关合入时必须回落 false，过早打开会打断 A 的测试 | 0.7 天 |
| **9** | **B9** 前端改造（含分页信封适配） | P1-13/14 + A 已改 4 个端点为分页，**当前前端这 4 处是坏的** | 1.2 天 |
| **10** | **B10 剩余** Nginx 安全头 + 镜像扫描 + 备份 | P1-16/24 + 运维待办 | 0.6 天 |
| **11** | **迁移链真实验证**（本环境独有） | 把「V19~V21 从未执行」转为真实证据 | 0.3 天 |

---

## 四、逐项验收标准

每项完成必须同时满足：**编译通过 + 该缺陷有反向测试 + 全量 `mvn clean test` 不回归 + 小步单独提交**。

- **B2**：登录响应 JSON 不含 `token`；Set-Cookie 含 HttpOnly/Secure/SameSite；同一 username 连续失败达阈值后 429 且带 Retry-After；用户不存在与口令错误的响应耗时无显著差异（dummy BCrypt 存在性由代码审查 + 单测保证，不做统计学计时断言）。
- **B10-P0**：`engines`/`Dockerfile`/`ci.yml` 三处 Node 版本一致；Docker 构建使用 `npm ci`；`npm audit --audit-level=high` 输出 0；CI 作业包含 sandbox-runner 测试；`Dockerfile` 显式安装 curl。
- **B5**：未通过验签的请求不落库正文、不回显既有 runId；并发相同 delivery 不因唯一键冲突返回 5xx。
- **B6**：非 `v1:` 前缀密文被拒绝；V26 在**真实 PostgreSQL** 上执行成功；伪造 `X-Forwarded-For` 不能绕过限流。
- **B7**：pytest 存在且通过；`/status` 不含绝对路径与原始异常；`/model/reload` 默认不可用。
- **B8**：过期淘汰、容量上限、并发三类测试齐备。
- **B3**：无 CSRF token 的写请求 403；登录/退出后 token 轮换；Webhook 免 CSRF 但错误签名仍拒。**合入前开关回落 false。**
- **B9**：四个分页端点在前端正常显示；无 localStorage/sessionStorage 存 token；`auth.username` 默认值为空。

---

## 五、必须如实标注的边界

沿用交接文档第七节口径，以下不得写成「通过」：

1. **本环境可验证、原计划不可验证的**：V25/V26 迁移可在真实 PostgreSQL 执行；Compose 启停可实测；镜像可扫描。这些将产出真实证据。
2. **本环境仍无法验证的**：多实例/横向扩容下的共享防重放与共享限流（当前实现均为进程内），只能声明为单实例结论。
3. **不做统计学断言的**：dummy BCrypt 的时序均衡只保证「两条分支都执行一次哈希」，不声称「时间完全一致」。

---

## 六、纪律约束

- 文件所有权严格遵守拆分方案第四节；`ErrorCode`/`PageResponse`/`common/api`/`common/exception` **只读**，需要新错误码走 `docs/跨线协商.md`。
- Flyway 只用 V25~V29；已执行迁移不改写。
- `app.security.csrf.enabled` 合入集成分支时必须为 `false`。
- 每次改 YAML 后跑一次解析校验（交接文档 5.2 的教训）。
- `@Transactional` 不做同类自调用（交接文档 5.1 的教训）。
