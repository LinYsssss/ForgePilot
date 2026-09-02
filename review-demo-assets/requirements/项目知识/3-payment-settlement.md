# Payment Settlement Service — 项目规范

> 适用范围：`LinYsssss/reposage-demo-payment-settlement-service`（私有，Java）
> 用途：ForgePilot 项目知识。审查 Finding 会引用本文条款作为判定依据。
> 来源：整合自仓库内 `docs/settlement-rules.md`、`docs/security-policy.md`、`docs/db-schema.md`、`docs/bug-history.md`，并补充当前代码状态。

## 0. 模块

| 包 | 职责 |
|---|---|
| `model` | 领域对象，**金额一律 `long`（分）** |
| `service` | 结算编排、手续费计算 |
| `repository` | 数据访问，**查询必须带 `tenant_id`** |
| `controller` | HTTP 入口 |

## 1. 结算周期

- 普通商户 **T+1**：交易日 24:00 截单，次日 10:00 发起结算。
- 风控标记商户（`merchant.risk_level >= 2`）为 **T+7**，且**必须人工复核后放款**。
- 节假日顺延，顺延期间不计息。

## 2. 金额与精度

**所有金额一律以「分」为单位，用 64 位整型存储与计算，任何环节禁止使用浮点类型。** 这是 INC-2024-07 之后立的红线。

四舍五入规则：

- 手续费计算结果若有小数分，**一律向下取整（截断）**，差额由平台承担。
- **严禁「四舍五入」或「向上取整」**——会多收商户手续费，属于**合规问题**（INC-2025-06 被监管问询）。

推荐做法：费率以**基点**（万分之一）表示，整数运算天然向下取整，不引入浮点。参照 `FeeCalculator.calculate`。

## 3. 费率

| 商户类型 | 费率 | 说明 |
|---|---|---|
| 标准商户 | **0.6%** | 2026-05 由 0.8% 下调 |
| 大额商户（月流水 ≥ 500 万元） | 0.38% | |
| 公益类商户 | 0% | 需资质审核 |

**费率不得硬编码在代码中**，必须从 `merchant_fee_config` 表读取，以便运营调整。代码里出现的任何字面费率常量都是缺陷——**尤其是 0.008，那是已经下调掉的旧费率**。

## 4. 最小结算金额

单笔结算净额（扣手续费后）**必须 ≥ 100 分（1 元）**，低于则不发起、累计到下一周期。银行侧对小额代付收固定成本，低于 1 元净亏损（INC-2025-12）。

## 5. 结算状态机

```
PENDING → PROCESSING → SUCCESS
                    ↘ FAILED → PENDING（人工重试）
```

- 只有 `PENDING` 可以被发起。
- **`PROCESSING` 严禁重复发起，必须依赖幂等键拦截。**
- `SUCCESS` 为终态，不可回退。

## 6. 币种

**当前仅支持 CNY。** 任何非 CNY 的结算请求必须拒绝，不得按 1:1 处理。

## 7. 幂等

**所有资金写入路径必须携带幂等键**（INC-2024-11 退款重复放款 8.6 万元）。

- 幂等键由**调用方传入**，`refund_request` 上有 `idempotency_key` 唯一约束。
- **服务端自行生成的键不构成幂等**——尤其禁止用时间戳、随机数、自增值拼键，重试会得到不同的键，等于没有幂等。
- 落库前先按幂等键查已有记录，命中则直接返回原结果。参照 `SettlementService.submit`。

## 8. 外部回调

- 所有来自银行/支付渠道的回调，**必须在解析业务参数之前完成 HMAC 验签**（INC-2025-09 伪造回调导致未放款的结算被标记 SUCCESS）。
- **验签必须基于原始请求字节**，不能基于反序列化后再重新序列化的结果。
- 验签失败一律返回 401，且**不得把请求正文写入日志或数据库**。
- 回调必须幂等：同一 `event_id` 重复到达只处理一次。

## 9. 出站请求

- 目标主机必须在配置白名单内。
- 禁止把用户可控字符串直接拼进 URL 后发起请求。
- 禁止请求内网地址、回环地址与云元数据地址（`169.254.169.254`）。

## 10. 凭据与日志

- 数据库密码、HMAC 密钥、渠道 API Key 一律从环境变量读取，**禁止出现在代码或配置文件的默认值中**。
- **日志中禁止输出密钥、令牌、Authorization 头、完整卡号与银行账号**；也不得用 `System.out.println` 输出资金流水。
- **回调正文、完整报文不得落库**，只保留哈希与元数据。
- 审计日志保留 180 天，不得含敏感字段明文。

## 11. SQL 与权限

- 禁止字符串拼接 SQL，一律参数绑定。
- **所有查询必须带 `tenant_id` 过滤**（INC-2025-02 跨租户泄露，两租户下 `merchant_id` 会重复）。
- 管理类接口（`/admin/**`）必须校验 `ADMIN` 角色。仅校验「已登录」不构成授权，必须校验资源归属。

## 12. 历史事故台账

**每条都已固化成上文规则。新代码重复同类问题视为高危。**

| 编号 | 事故 | 原因 | 固化规则 |
|---|---|---|---|
| INC-2024-07 | 对账差异 3,412 元，排查两天 | `SettlementService` 用 `double` 累加手续费 | §2 |
| INC-2024-11 | 退款重复放款 8.6 万元 | 退款接口无幂等键，仅靠前端防重 | §7 |
| INC-2025-02 | 跨租户数据泄露 | 查询按 `merchant_id` 过滤但漏 `tenant_id` | §11 |
| INC-2025-06 | 手续费多收被监管问询 | 手续费用四舍五入，部分向上进位 | §2 |
| INC-2025-09 | 银行回调被伪造，未放款却标记 SUCCESS | 回调接口未验签，只校验商户号存在 | §8 |
| INC-2025-12 | 小额结算净亏损 4 万余元 | 未校验最小结算金额 | §4 |

## 13. 当前代码状态

**本仓 `main` 上同时存在两类代码，必须分清。** 注意 2026-09-01 之前本仓的默认分支曾是 `feature/instant-settlement`，现已改为 `main`。

### 正确的参照实现（改动时以它们为准）

| 文件 | 状态 |
|---|---|
| `service/SettlementService.java` | ✅ 规范实现。校验幂等键非空 → 校验币种 CNY → 按幂等键查已有记录并直接返回 → 算费 → 校验最小净额 100 分。金额全程 `long` |
| `service/FeeCalculator.java` | ✅ 规范实现。费率以**基点**从 `MerchantFeeConfigRepository` 读取，未配置直接抛错；`grossAmountFen * feeRateBp / 10_000L` 整数运算天然向下取整，零浮点 |

### 2026-09-01 并入的缺陷代码（**待修复，不得视为正确实现照抄**）

`service/InstantSettlementService.java`（即时结算 T+0）：

| 问题 | 违反 |
|---|---|
| `FEE_RATE = 0.008` 硬编码，**且是 2026-05 已下调掉的旧费率** | §3 |
| `double` 贯穿：入参 `grossAmountYuan`、`fee`、`net`、`settleBatch` 的 `total` 累加 | §2（INC-2024-07 原样复现） |
| `Math.round(...)` 四舍五入 | §2（INC-2025-06，合规问题） |
| 幂等键 `"instant-" + merchantId + "-" + System.currentTimeMillis()` **服务端生成且含时间戳**，重试必然产生不同键 | §7（INC-2024-11） |
| 无最小净额 100 分校验 | §4 |
| 无币种校验，`currency` 参数收下就不管了 | §6 |
| 无风控商户 T+7 判断 | §1 |
| 注入了 `repository` 但**从不使用**，`submitInstant` 根本不落库、不查幂等 | §7 |
| 入参单位是「元」，与全仓「分」的约定冲突 | §2 |

`service/RefundService.java`：

| 问题 | 违反 |
|---|---|
| `refund` **无幂等键**，直接 `doPayout` | §7（INC-2024-11 直接复现） |
| `forceRefund` 注释写「管理员强制退款」但**代码中没有任何角色校验** | §11 |
| 无结算状态校验 | §5 |
| `doPayout` 用 `System.out.println` 输出 tenant/merchant/order/amount 资金流水 | §10 |
| 注入了 `repository` 但从不使用，不落库 | §7 |

`controller/PayoutCallbackController.java`：

| 问题 | 违反 |
|---|---|
| **完全没有 HMAC 验签**，拿到 `rawBody` 直接 `JsonSupport.parse` | §8（INC-2025-09 直接复现） |
| `headers` 参数收下了但从未使用——签名就在里面 | §8 |
| `callbackLogs.save(settlementId, merchantNo, rawBody, resultCode)` **把原始报文落库** | §10 |
| **无幂等**，同一 `event_id` 重复到达会重复改状态 | §8 |
| `Long.valueOf(settlementId)` 无异常防护，非数字直接抛 | §11 |
| 无 `tenant_id` 校验 | §11 |

`repository/RawJdbc.java`、`repository/MerchantQueryRepository.java`：直接执行 SQL 字符串的入口，**只要它存在，任何调用方都能绕过参数绑定**。

**这批代码的每一条都能在 §12 的事故台账里找到对应条目。**
