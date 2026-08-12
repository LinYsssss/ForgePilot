# 商户结算业务规则（节选）

> 适用范围：acme-clearing 放款链路

## 结算状态机

```
PENDING → PROCESSING → SUCCESS
                    ↘ FAILED → PENDING（人工重试）
```

- 只有 `PENDING` 可以被发起放款。
- 发起放款**必须先置为 `PROCESSING`**；`PROCESSING` 状态严禁重复发起，重复放款的幂等拦截依赖该状态守卫。
- `SUCCESS` 为终态，不可回退。
- **任何跳过 `PROCESSING` 直达终态的流转都属违规**：绕过状态守卫后，同一张结算票据被重复提交会造成多次出款（事故 EVAL-618：重复代付资损）。
