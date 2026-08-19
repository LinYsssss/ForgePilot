# biz 批次创作说明（业务规则破坏 8 例）

> 素材：demo-repos 对照表答案键（payment P1/P3/P4/P6/P15、mall M1/M4/M10）复制改编，包名脱敏为 `com.acme.clearing.*` / `com.acme.shop.*`，事故编号一律 EVAL- 重编。候选 9 取 8：**弃 P14**（漏报批次已有 `miss-payhub-refund-cast` 同素材大 diff 版，弃小 diff 版以最大化缺陷形态多样性）。每例 knowledge/ 为裁剪版判据文档（均 <500 字符，远低于 2000 上限），缺陷只有对照文档才成立。

- **biz-fee-rate-hardcoded**（P1，settlement-rules.md 费率条款）：新增 T+0 即时结算把标准费率硬编码 80bp（0.8%），文档规定 0.6% 且费率必须从 fee_rate_config 表读取；多收商户手续费属资金计算错误 → HIGH。标注区间 13-26 覆盖常量声明与使用行。
- **biz-fee-rounding-mode**（P3，settlement-rules.md 精度条款）：手续费"对账口径对齐"改造把整数向下取整改成 Math.round 四舍五入，文档明令截断、禁止四舍五入（合规红线）；资金计算 → HIGH。标注 30-32（浮点中间值 + Math.round）。holdout。
- **biz-min-net-amount-skipped**（P4，settlement-rules.md 最小结算金额条款）：新增运营批量结算路径未校验最小净额 100 分（基仓单笔路径有该校验），文档明确规则适用所有发起路径；属校验遗漏 → MEDIUM。标注 26-32（循环体建单无 min-net 守卫）。
- **biz-currency-unchecked**（P6，settlement-rules.md 币种条款）：结算入口开放 currency 参数（跨境预留）但未校验，非 CNY 被按 1:1 落单，文档要求入口直接拒绝；校验遗漏 → MEDIUM。标注 26-37（submit 方法签名到透传落单）。缺陷记在服务层入口（controller 仅透传，不进标注——预期最小集）。
- **biz-status-machine-bypass**（P15，settlement-rules.md 状态机条款）：新增"极速放款"从 PENDING 直接 markSuccess，绕过 markProcessing 状态守卫（防重复放款唯一拦截点），重复调用即重复出款；状态机 + 资损 → HIGH。标注 20-29（payout 全路径）。equivalents 含 TRANSACTION_RISK。
- **biz-ship-ignores-pay-status**（M1，order-flow.md 发货规则）：新增大促批量发货只判 status=WAIT_SHIP 不判 pay_status=PAID（单笔路径两者都判），文档强调两字段独立必须分别校验（EVAL-101 事故重犯）；未支付订单被发货 → HIGH。标注 45-52（batchShip 循环过滤到 doShip）。
- **biz-order-amount-double**（M4，order-flow.md 金额约定）：新增活动折扣重算走 double 元级中间值再 (long) 强转截断，文档禁止浮点、比例须用基点整数；资金计算 → HIGH。标注 33-37。
- **biz-ship-missing-shipped-at**（M10，order-flow.md 发货落库要求）：发货防并发改为条件更新后只更新 status，丢失 shipped_at 写入（基仓 markShipped 单条 update 三项齐写）；字段遗漏 → MEDIUM，条件更新本身正确（nonFinding）。标注 26-31。holdout。

split：development 6 / holdout 2（biz-fee-rounding-mode、biz-ship-missing-shipped-at），符合派单配额。行号均已按 head/ 实际文件逐条核对（2026-08-11）。
