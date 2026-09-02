# ForgePilot 演示文档包

> 生成于 2026-09-01，基于 fp-demo 线上环境实测与三个测试仓库的真实代码。

## 三个测试仓库

`LinYsssss` 名下这三个仓是同一批建的（2026-08-05，间隔 15 秒），仓库描述都写着 **"RepoSage demo review repository: ... with deliberate review findings"**——这才是为审查演示准备的测试集。`campus-platform-*` 是毕设的真实代码仓，不是测试仓。

| 仓库 | 语言 | main | 说明 |
|---|---|---|---|
| `reposage-demo-mall-order-service` | Java | `975aaae8` | 订单发货 / 取消 / 详情 / 搜索 |
| `reposage-demo-tenant-user-center` | **Python + JavaScript** | `540cd4ce` | 多租户用户中心，可验证多语言审查 |
| `reposage-demo-payment-settlement-service` | Java | `db3a87af` | 商户结算，资金类规则最密 |

三个仓是同一套设计：**`main` 放正确的参照实现 + 四份规范文档 + 历史事故台账，feature 分支放违反这些规范的新代码**。2026-09-01 已按要求把 feature 分支全部并入 main、只保留 main。正确实现与缺陷代码现在并排躺在 main 上，这对审查是有利的——模型能拿同仓的正确写法做对照，Finding 的证据链更硬。

## 文件

| 文件 | 用途 |
|---|---|
| [00-操作指南.md](./00-操作指南.md) | **先读这个。** 全流程 + 两个硬阻塞 + 排错手册 + 答辩要点 |
| [项目知识/1-mall-order-service.md](./项目知识/1-mall-order-service.md) | 上传到 mall 仓所属的 ForgePilot 项目 |
| [项目知识/2-tenant-user-center.md](./项目知识/2-tenant-user-center.md) | 上传到 tenant 仓所属项目 |
| [项目知识/3-payment-settlement.md](./项目知识/3-payment-settlement.md) | 上传到 payment 仓所属项目 |
| [需求/1-mall-order-service.md](./需求/1-mall-order-service.md) | 3 条需求，含 11–12 条验收条件 |
| [需求/2-tenant-user-center.md](./需求/2-tenant-user-center.md) | 3 条需求 |
| [需求/3-payment-settlement.md](./需求/3-payment-settlement.md) | 3 条需求 |
| [备用-毕设仓/](./备用-毕设仓/) | campus-platform-* 两仓的知识与需求。ForgePilot 项目 1、2 **当前绑的就是这两个仓**，改绑前仍然可用 |

## 编写原则

**九条需求全部锚定仓库里真实存在的缺陷**，而且**每一条在仓库自己的 `docs/bug-history.md` 里都有对应的历史事故**——这不是巧合，那批代码就是照着事故台账反向构造的。

| 仓库 | 需求 | 对应事故 |
|---|---|---|
| mall | 发货前置校验与幂等 | BUG-001（未支付被发货，损失 4.2 万）、BUG-005（重复发货） |
| mall | 强制发货鉴权与审计 | BUG-002（管理接口越权） |
| mall | 搜索消除 SQL 注入 | BUG-004（SQL 注入） |
| tenant | 认证授权与租户来源 | INC-2024-09（跨租户泄露 12 万条）、INC-2025-03（令牌只解码不校验）、INC-2025-07（tenant_id 来自参数） |
| tenant | SQL 注入与 bcrypt | INC-2024-12（MD5 两周被破 63%）、INC-2026-01（导出拖垮数据库） |
| tenant | 前端安全整改 | INC-2025-10（昵称 XSS） |
| payment | 整数分与配置费率 | INC-2024-07（浮点对账差 3,412 元）、INC-2025-06（四舍五入多收被监管问询）、INC-2025-12（小额结算亏 4 万） |
| payment | 幂等与鉴权 | INC-2024-11（退款重复放款 8.6 万） |
| payment | 回调验签 | INC-2025-09（伪造回调标记 SUCCESS） |

几处特别值得看的埋点：

- payment 的 `InstantSettlementService` 硬编码 `FEE_RATE = 0.008`，而规范里写明 **2026-05 已下调到 0.6%**——硬编码的值本身就是过期的，这一条要求模型同时读代码和读规范才能命中。
- payment 的幂等键是 `"instant-" + merchantId + "-" + System.currentTimeMillis()`，**看起来有幂等键，实际每次重试都不同**，等于没有。
- tenant 的 `_who` 用 `jwt.decode(..., options={"verify_signature": False})`，而同仓 `auth.py` 的 `verify_token` 就摆着正确写法——同仓对照最能体现项目知识的价值。

**已排除的假需求**：原先给 campus-backend 写的「课程查询分页与过滤」被删了——`EduCourseController.page` 早就实现了分页、关键字、学期、状态过滤和教师范围收窄，拿它做需求审查会无事可做。

## 与清库前那批数据的区别

| | 清库前 | 现在 |
|---|---|---|
| 项目知识 | 3 份，各 250–300 字的占位文 | 3 份，各 3000+ 字的可判定规范，含事故台账 |
| 验收条件 | 60 条，**全是同一句**「正常场景有可重复的自动化测试」 | 每条需求 8–12 条，逐条对应具体代码事实 |
| 需求 | 15 条，与仓库代码无对应关系 | 9 条，全部锚定真实缺陷 |
| 素材 | 15 个 PR 里 14 个是 10 行桩子，答案写在类注释里 | 三个仓 main 上的真实缺陷代码，共约 460 行 |

占位式 AC 是旧演示产不出有效 Finding 的直接原因：AI 拿「正常场景有可重复的自动化测试」去比对 diff，判定不了任何具体违反。

## 备份与回滚

| 文件 | 内容 |
|---|---|
| `/root/fp-demo-pre-cleanup-20260901T020732Z.sql` | 清库前的完整数据库（116 KB） |
| `/root/github-merge-rollback-20260901T030757Z.txt` | 合并前三个仓的全部分支与 PR 的 SHA |
