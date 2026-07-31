# payment-settlement-service

商户结算服务（演示用）。

- 结算周期、金额精度、费率、最小结算额等业务规则见 `docs/settlement-rules.md`
- 表结构与字段约定见 `docs/db-schema.md`
- 历史事故与已固化规则见 `docs/bug-history.md`
- 安全要求见 `docs/security-policy.md`

## 模块

| 包 | 职责 |
|---|---|
| `model` | 领域对象，金额一律 long（分） |
| `service` | 结算编排、手续费计算 |
| `repository` | 数据访问，查询必须带 tenant_id |
| `controller` | HTTP 入口 |

## 约定

金额单位「分」，禁止浮点。费率从 `merchant_fee_config` 读取。所有资金写入路径必须带幂等键。
