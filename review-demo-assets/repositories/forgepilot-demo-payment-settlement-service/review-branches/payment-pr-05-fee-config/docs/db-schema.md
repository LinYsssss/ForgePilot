# 数据库设计说明

> 适用范围：payment-settlement-service

## 命名与类型约定

1. **所有金额字段一律 `bigint`，单位为「分」。** 禁止 `decimal`、`float`、`double`。
2. 所有表必须包含 `tenant_id`，且**所有查询必须带 `tenant_id` 过滤条件**。
3. 时间字段统一 `timestamp with time zone`。
4. 逻辑删除使用 `deleted_at`，查询需排除非空记录。

## settlement_request

商户结算请求。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial | 主键 |
| `tenant_id` | bigint | 租户 ID，**查询必须过滤** |
| `merchant_id` | bigint | 商户 ID |
| `idempotency_key` | varchar(64) | **唯一键**，防重复提交 |
| `gross_amount` | bigint | 结算总额（分） |
| `fee_amount` | bigint | 手续费（分） |
| `net_amount` | bigint | 净额（分）= gross - fee |
| `currency` | varchar(3) | 币种，当前仅 CNY |
| `status` | varchar(24) | PENDING / PROCESSING / SUCCESS / FAILED |
| `created_at` | timestamptz | |

唯一约束：`uq_settlement_idempotency (tenant_id, idempotency_key)`

**任何创建结算请求的写入路径都必须提供 `idempotency_key`**，否则重复提交会导致重复放款。

## refund_request

退款请求，结构与约束同上，同样要求 `idempotency_key` 唯一。

## merchant_fee_config

商户费率配置。费率从此表读取，**不得在代码中硬编码**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `merchant_id` | bigint | |
| `fee_rate_bp` | int | 费率，单位「基点」（万分之一）。0.6% = 60 |
| `effective_from` | timestamptz | 生效时间，取最新一条生效的 |

## payout_callback_log

银行回调日志。

- **禁止存储回调正文原文**，只保留 `payload_hash`、`event_type`、`status`、`received_at`。
- 回调正文可能包含银行账号等敏感信息，落库属于合规事故。
