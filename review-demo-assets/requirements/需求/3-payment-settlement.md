# Payment Settlement Service — 需求清单

> 目标仓库：`LinYsssss/reposage-demo-payment-settlement-service`（私有，Java）
>
> **使用方式**：每条需求的「标题 / 背景 / 描述」直接粘进 ForgePilot 需求表单，「验收条件」逐条录入。
> 分隔线以下的「改动范围」「预期发现」**不要粘贴**。

三条需求都在修 2026-09-01 并入 `main` 的 `feature/instant-settlement` 那批代码。**每一条缺陷在仓库自己的 `docs/bug-history.md` 里都有对应的历史事故**。

> **仓库现状约束**：`RawJdbc.queryList` 抛 `UnsupportedOperationException`，`doPayout` 只是 `System.out.println`，是演示用空实现，不连真实数据库与银行渠道。验收条件只要求代码结构与校验逻辑正确，不要求真实放款。
>
> **`SettlementService` 与 `FeeCalculator` 是已经符合规范的参照实现**——幂等键查重、币种校验、最小净额校验、基点整数算费全都在里面。改动时优先复用，不要另写一套。

---

## 需求一：即时结算改整数分与配置费率

**标题**

```
即时结算金额改整数分并接入费率配置
```

**背景**

```
InstantSettlementService 是为 T+0 即时结算新加的服务，类注释写着「为了尽快上线，先按标准商户费率处理，后续再对接配置表」。它踩中了四条固化规则：

1. 金额全程用 double：入参 grossAmountYuan 是 double 且单位是「元」（全仓约定是「分」），fee / net 是 double，settleBatch 还用 double total 累加。docs/settlement-rules.md 第 2 节是 INC-2024-07（对账差异 3,412 元，排查两天）之后立的红线：任何环节禁止浮点。
2. FEE_RATE = 0.008 硬编码。第 3 节要求费率必须从 merchant_fee_config 读取；更严重的是 0.8% 是 2026-05 已经下调掉的旧费率，现行标准商户费率是 0.6%，硬编码的值本身就是错的。
3. Math.round 四舍五入。第 2 节要求手续费一律向下取整、差额平台承担，INC-2025-06 就是四舍五入导致多收手续费被监管问询，这是合规问题。
4. 没有最小净额 100 分校验（INC-2025-12 小额结算净亏损 4 万余元），没有币种校验（第 6 节要求非 CNY 必须拒绝），没有风控商户 T+7 判断（第 1 节）。

同仓的 SettlementService 与 FeeCalculator 已经把这些全部做对了：FeeCalculator 用基点从配置表读费率、grossAmountFen * feeRateBp / 10_000L 整数运算天然向下取整；SettlementService 依次校验幂等键、币种、最小净额。
```

**描述**

```
让即时结算与常规结算遵循同一套金额、费率与校验规则，复用已有的正确实现，而不是并行维护第二套算法。

即时结算与常规结算的差异应当只在「周期」上，金额精度、费率来源、取整方向、最小净额、币种这五件事必须完全一致。
```

**验收条件**

```
AC-1  即时结算路径上不再出现任何 double、float 或浮点字面量，金额入参与返回全部为 long，单位「分」。
AC-2  方法签名的金额参数单位改为「分」，与全仓约定及 SettlementService 一致。
AC-3  费率从 merchant_fee_config 读取，复用 FeeCalculator；代码中不再有任何费率字面量常量。
AC-4  商户未配置费率时明确失败，不回落到任何默认值。
AC-5  手续费向下取整，不使用 Math.round、Math.ceil 或任何四舍五入。
AC-6  校验结算净额 ≥ 100 分，低于则不发起并明确告知，不静默跳过。
AC-7  校验币种为 CNY，非 CNY 拒绝，不按 1:1 处理。
AC-8  风控标记商户（risk_level >= 2）不走即时结算路径，或走后需人工复核，二者择一并显式表达。
AC-9  settleBatch 的累加与返回值使用整型分，不用浮点累加。
AC-10 金额、费率、笔数的边界值（0、负数、极大值）有明确处理，不溢出不静默截断。
AC-11 SettlementService 与 FeeCalculator 的现有行为不被修改破坏。
```

---
**改动范围（不要粘贴）**

- `src/main/java/com/example/settlement/service/InstantSettlementService.java`
- 可能复用 `FeeCalculator`、`MerchantFeeConfigRepository`

**预期 ForgePilot 会报出的问题**

把 `double` 换成 `BigDecimal` 而不是 `long` 分，虽然消除了二进制误差，但违反了全仓「long 分」的统一约定，审查应指出这一点。把硬编码费率从 `0.008` 改成 `0.006`（即改成正确的现行费率）**仍然不合格**——问题是硬编码本身，应命中 AC-3。用 `(long)` 强转实现「向下取整」在负数上是向零取整而非向下，边界处应命中 AC-10。

---

## 需求二：资金写入路径补齐幂等与鉴权

**标题**

```
退款与即时结算补齐幂等键和权限校验
```

**背景**

```
两个服务的资金写入路径都缺幂等：

RefundService.refund 接收 tenantId、merchantId、orderNo、amountFen 后直接 doPayout，没有任何幂等键。INC-2024-11 就是商户网络抖动重试导致同一笔退款被执行 4 次，重复放款 8.6 万元，此后 refund_request 上加了 idempotency_key 唯一约束并要求接口强制传入。

InstantSettlementService 的幂等键是服务端自己生成的：

  "instant-" + merchantId + "-" + System.currentTimeMillis()

含时间戳意味着每次重试都会得到不同的键，唯一约束拦不住，等于没有幂等。docs/settlement-rules.md 第 7 节要求幂等键由调用方传入。

RefundService.forceRefund 的注释写着「管理员强制退款，跳过风控与状态校验」，但方法体里没有任何角色校验，与 refund 的实现完全相同。docs/security-policy.md 第 5 节要求管理类接口必须校验 ADMIN 角色，「仅校验已登录不构成授权」。

此外两个服务都注入了 repository 却从不使用 —— 结算与退款都不落库，也就无从按幂等键查重；doPayout 用 System.out.println 输出 tenant、merchant、orderNo、amount 四个字段的资金流水，违反第 10 节的日志规范。
```

**描述**

```
让所有资金写入路径遵循同一套幂等与鉴权约定：幂等键由调用方传入、落库前先查重、管理类操作校验角色并留痕。

参照 SettlementService.submit 的既有做法：校验幂等键非空 → 按键查已有记录 → 命中直接返回原结果 → 否则执行并落库。
```

**验收条件**

```
AC-1  refund 与即时结算的幂等键都由调用方传入，方法签名中有该参数，为空或空白时拒绝执行。
AC-2  服务端不再自行生成幂等键；代码中不再出现用 System.currentTimeMillis、随机数或自增值拼接幂等键。
AC-3  执行前按 (tenantId, idempotencyKey) 查询已有记录，命中则直接返回原结果，不重复放款。
AC-4  同一幂等键连续调用两次，只发生一次资金写入。
AC-5  结算与退款记录落库，注入的 repository 被实际使用。
AC-6  forceRefund 校验调用方具备 ADMIN 角色，不具备返回 403 且不执行任何资金动作。
AC-7  forceRefund 跳过的校验项在代码中显式列出并注释说明理由，不是隐式什么都不查。
AC-8  forceRefund 写审计日志，含操作人、时间、目标订单、金额、动作类型；被拒绝的越权尝试与成功操作在审计中可区分。
AC-9  退款校验结算状态，SUCCESS 终态与非法状态下的退款请求被拒绝。
AC-10 资金流水不再经 System.out.println 输出；日志中不含完整银行账号、卡号、密钥。
AC-11 所有查询与写入带 tenant_id 条件。
```

---
**改动范围（不要粘贴）**

- `src/main/java/com/example/settlement/service/RefundService.java`
- `src/main/java/com/example/settlement/service/InstantSettlementService.java`
- `src/main/java/com/example/settlement/repository/SettlementRequestRepository.java`

**预期 ForgePilot 会报出的问题**

用「先查存在再插入」实现幂等而不依赖数据库唯一约束，存在检查-执行竞态，并发重试仍会双写，审查应指出这一点。把 `forceRefund` 的角色校验放在调用方而不是方法内，应命中 AC-6（防御要贴着资金动作）。只给 `refund` 加幂等而漏掉即时结算，应命中 AC-2。

---

## 需求三：银行回调补齐验签与幂等

**标题**

```
银行代付回调补齐 HMAC 验签与幂等处理
```

**背景**

```
PayoutCallbackController.onPayoutResult 接收 rawBody 与 headers 两个参数，但 headers 收下之后从未使用 —— 签名就在里面。方法体第一行就是 JsonSupport.parse(rawBody)，直接解析业务参数。

docs/security-policy.md 第 1 节要求：所有来自银行/支付渠道的回调必须在解析业务参数之前完成 HMAC 验签，验签必须基于原始请求字节，失败返回 401 且不得把请求正文写入日志或数据库。

INC-2025-09 的成因与此完全一致：回调接口未验签，只校验了商户号是否存在，测试环境收到伪造的成功回调，导致一笔未实际放款的结算被标记为 SUCCESS。

当前实现另有三个问题：

1. callbackLogs.save(settlementId, merchantNo, rawBody, resultCode) 把原始报文整体落库。第 6 节要求回调正文不得落库，只保留哈希与元数据。
2. 没有幂等。同一 event_id 重复到达会重复调用 markSuccess / markFailed，而结算状态机中 SUCCESS 是终态不可回退。
3. Long.valueOf(settlementId) 没有异常防护，回调里 settlementId 不是数字就直接抛异常；且没有校验该结算单属于哪个租户。
```

**描述**

```
把回调入口改成「先验签、再幂等、后处理」的顺序，并让落库内容符合留存规范。

验签失败的请求不产生任何业务副作用，也不留下正文痕迹。
```

**验收条件**

```
AC-1  验签在解析业务参数之前完成，代码顺序上 HMAC 校验先于 JsonSupport.parse。
AC-2  验签基于原始请求字节，不基于反序列化后重新序列化的结果。
AC-3  签名从 headers 中读取，headers 参数被实际使用。
AC-4  HMAC 密钥从环境变量读取，代码与配置默认值中不出现密钥字面量。
AC-5  验签失败返回 401，不写日志正文、不落库、不改任何结算状态。
AC-6  按 event_id 幂等：同一事件重复到达只处理一次，重复投递返回成功但不重复改状态。
AC-7  已处于 SUCCESS 终态的结算单不被回调改回其他状态。
AC-8  落库只保留原始报文的哈希与元数据，不保留正文。
AC-9  settlementId 非数字或缺失时返回明确的错误，不抛未捕获异常。
AC-10 校验结算单归属租户，跨租户的回调被拒绝。
AC-11 日志中不输出回调正文、签名值与密钥。
```

---
**改动范围（不要粘贴）**

- `src/main/java/com/example/settlement/controller/PayoutCallbackController.java`
- `src/main/java/com/example/settlement/repository/PayoutCallbackLogRepository.java`

**预期 ForgePilot 会报出的问题**

先 `parse` 再验签（顺序反了）是最容易踩的坑，虽然功能上「也验了」，但违反 AC-1 与 INC-2025-09 的固化规则。用 `String.equals` 比较签名而非常量时间比较，会引入时序侧信道，审查通常会指出。把幂等判断放在 `markSuccess` 内部而不是回调入口，仍会重复写回调日志，应命中 AC-6。
