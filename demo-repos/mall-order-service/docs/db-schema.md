# 数据库表结构

## orders

字段：

- `id`：订单 ID
- `user_id`：用户 ID
- `status`：订单状态，包含 `CREATED`、`PAID`、`WAIT_SHIP`、`SHIPPED`、`CANCELLED`
- `pay_status`：支付状态，包含 `UNPAID`、`PAID`、`REFUNDED`
- `amount`：订单金额

订单发货逻辑必须检查 `pay_status = PAID`。
