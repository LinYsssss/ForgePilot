# 数据库表结构

> 适用范围：mall-order-service

## 通用约定

1. 所有金额字段单位为**分**，类型 `bigint`，**禁止浮点类型**。
2. 逻辑删除使用 `deleted_at`，所有查询需排除非空记录。
3. 时间字段统一 `timestamp with time zone`。
4. 禁止在查询中拼接字符串，一律参数绑定。

## orders

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial | 订单 ID |
| `user_id` | bigint | 下单用户，**按 ID 查询订单时必须校验归属** |
| `status` | varchar(24) | `CREATED` / `PAID` / `WAIT_SHIP` / `SHIPPED` / `COMPLETED` / `CANCELLED` |
| `pay_status` | varchar(24) | `UNPAID` / `PAID` / `REFUNDED` |
| `amount` | bigint | 应付金额（分） |
| `discount_amount` | bigint | 优惠抵扣（分） |
| `shipping_fee` | bigint | 运费（分） |
| `paid_amount` | bigint | 实付金额（分） |
| `receiver_phone` | varchar(32) | 收货人手机号，**敏感字段，日志与响应需脱敏** |
| `receiver_address` | text | 收货地址，敏感字段 |
| `shipped_at` | timestamptz | 发货时间 |
| `deleted_at` | timestamptz | 逻辑删除 |

**`status` 与 `pay_status` 是两个独立字段。** 订单可能处于 `WAIT_SHIP` 而 `pay_status` 仍为 `UNPAID`。发货逻辑必须同时校验两者 —— 只查其中之一是 BUG-001 的直接成因。

## order_operation_log

订单操作审计。

| 字段 | 说明 |
|---|---|
| `order_id` | 关联订单 |
| `operator_id` | 操作人 |
| `action` | SHIP / CANCEL / REFUND / FORCE_SHIP |
| `created_at` | |

管理端的强制操作（`FORCE_*`）**必须**写审计日志。

## 索引

- `idx_orders_user (user_id, created_at desc)`
- `idx_orders_status (status, pay_status)`
- 唯一键 `uq_orders_out_trade_no (out_trade_no)` 防重复下单
