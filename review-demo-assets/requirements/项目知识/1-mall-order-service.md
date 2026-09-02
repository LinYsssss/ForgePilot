# Mall Order Service — 项目规范

> 适用范围：`LinYsssss/reposage-demo-mall-order-service`
> 用途：ForgePilot 项目知识。审查 Finding 会引用本文条款作为判定依据。
> 来源：本文整合自仓库内 `docs/order-flow.md`、`docs/security-policy.md`、`docs/db-schema.md`、`docs/bug-history.md`，并补充了当前代码状态。

## 1. 订单状态机

```
CREATED → PAID → WAIT_SHIP → SHIPPED → COMPLETED
   ↓        ↓         ↓
CANCELLED  REFUNDING  CANCELLED（仅未发货可取消）
```

- `CREATED`：下单成功未支付，超 30 分钟自动取消。
- `PAID`：支付成功，等待备货。
- `WAIT_SHIP`：备货完成，可发货。
- `SHIPPED`：已发货，不可取消，只能走退货。
- `COMPLETED`：确认收货或超时自动确认。

**状态只能沿箭头方向流转，任何跳跃都属于缺陷。**

## 2. 发货规则

发货前必须**同时**满足四条：

1. 订单存在且 `deleted_at` 为空；
2. `pay_status = PAID`；
3. `status = WAIT_SHIP`；
4. 收货地址已填写且未被风控标记。

发货成功后：`status` 置 `SHIPPED`，写入 `shipped_at`，记录操作人。

> **`status` 与 `pay_status` 是两个独立字段。** 订单可能处于 `WAIT_SHIP` 而 `pay_status` 仍为 `UNPAID`。只校验其中之一是 BUG-001 的直接成因，损失约 4.2 万元。

## 3. 取消规则

- 仅 `CREATED`、`PAID`、`WAIT_SHIP` 可取消；`SHIPPED` 之后禁止取消，必须走退货。
- 已支付订单取消需同步发起退款，**退款失败的订单不得置为 `CANCELLED`**。

## 4. 金额

- `amount`、`discount_amount`、`shipping_fee`、`paid_amount` 单位一律为**分**，`bigint` 整型存储。
- 四个字段相互独立，**不得合并计算后再拆分**。
- **任何金额计算禁止浮点类型。** 需要按比例折算时用 `BigDecimal` 保持精确十进制域，且只在最终写回时舍入一次（`RoundingMode.HALF_UP`）；`(long)` 强转是截断不是四舍五入，会系统性少算。

## 5. 授权边界

- **「已登录」不等于「已授权」。** 任何按资源 ID 操作的接口，必须校验该资源归属于当前用户。
- 管理端接口（`/admin/**`）必须校验调用方具备 `ADMIN` 角色，不能只依赖前端隐藏入口。
- 越权返回 403，资源不存在返回 404。**不得因为"查不到"就返回 200 空结果。**

## 6. SQL 与输入

- **禁止字符串拼接 SQL，一律参数绑定。**
- 排序字段必须走白名单，不得把客户端传入的字段名直接拼进 `ORDER BY`。
- 所有外部输入校验长度与字符集；金额、数量校验范围，禁止负数。
- 查询必须排除 `deleted_at` 非空的逻辑删除记录。

## 7. 敏感数据

- `receiver_phone`、`receiver_address` 属个人信息：**响应中需脱敏，日志中禁止输出完整值**。
- 禁止在异常信息里回显 SQL、内部路径、完整请求体。
- 审计日志中不得包含敏感字段明文。

## 8. 并发与幂等

- 发货、取消、退款等有副作用的操作**必须幂等**，依赖数据库条件更新（如以 `status = WAIT_SHIP` 为更新条件，受影响行数为 0 即视为已处理）或唯一键。
- **不得仅依赖前端防重复点击。**
- 批量操作必须在单个事务内完成，或具备可重入的补偿路径；逐条循环更新且无事务边界的写法属于缺陷。

## 9. 审计

管理端强制操作（`FORCE_SHIP` 等）**必须**写 `order_operation_log`，记录 `order_id`、`operator_id`、`action`、`created_at`。

## 10. 数据表

`orders` 关键字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `user_id` | bigint | 下单用户，**按 ID 查订单时必须校验归属** |
| `status` | varchar(24) | 见状态机 |
| `pay_status` | varchar(24) | `UNPAID` / `PAID` / `REFUNDED`，**与 `status` 独立** |
| `amount` / `discount_amount` / `shipping_fee` / `paid_amount` | bigint | 金额，单位分 |
| `receiver_phone` / `receiver_address` | varchar / text | 敏感字段 |
| `shipped_at` | timestamptz | 发货时间 |
| `deleted_at` | timestamptz | 逻辑删除 |

索引：`idx_orders_user (user_id, created_at desc)`、`idx_orders_status (status, pay_status)`、唯一键 `uq_orders_out_trade_no` 防重复下单。

## 11. 历史 Bug 台账

**每条都已固化成上文的一条规则。新代码重复同类问题视为高危。**

| 编号 | 时间 | 问题 | 成因 | 固化规则 |
|---|---|---|---|---|
| BUG-001 | 2025-03 | 37 笔未支付订单被发货，损失约 4.2 万 | `shipOrder` 只判 `status` 未判 `pay_status` | §2 |
| BUG-002 | 2025-06 | 普通用户调用管理端强制发货 | `/admin/order/**` 只加登录校验没加角色校验 | §5 |
| BUG-003 | 2025-08 | 用户遍历订单 ID 看到他人地址手机号 | `getOrderDetail(orderId)` 未校验归属 | §5 |
| BUG-004 | 2025-09 | 订单搜索接口可 SQL 注入 | 搜索关键字直接拼进 SQL | §6 |
| BUG-005 | 2025-11 | 网络重试导致同一订单发货两次 | 发货接口无幂等控制，仅靠前端禁用按钮 | §8 |
| BUG-006 | 2026-01 | 部分订单实付金额少 1 分 | 优惠计算过程用了 `double` | §4 |

## 12. 当前代码状态

本仓是**为代码审查演示准备的仓库，`main` 分支上存在有意保留的缺陷**（`README.md` 已声明）。2026-09-01 又把 `feature/promotion-batch-ship`（PR #1）并入了 main，因此当前 main 上的已知未修复项如下。**改动这些文件时应一并处理，不得视为既有正确实现而照抄：**

### 原有部分

| 位置 | 状态 |
|---|---|
| `OrderService.shipOrder` | 直接 `setStatus("SHIPPED")`，**未校验 `pay_status`、未校验 `status`、未写 `shipped_at`、未记录操作人**（BUG-001 复现） |
| `AdminOrderController.forceShip` | 管理端强制发货，**无 `ADMIN` 角色校验、无审计**（BUG-002 复现） |
| `OrderMapper.searchByKeyword` | 直接拼接 `keyword` 进 SQL（BUG-004 复现） |
| `OrderMapper.selectBySql(String)` | 接收整条 SQL 字符串的入口，**只要它存在，任何调用方都能绕过参数绑定** |
| `OrderService` / `AdminOrderController` | 依赖用 `new` 直接实例化，无法注入替身，不可测 |
| `OrderMapper` | 演示用空实现，不连真实数据库；`findById` 恒返回空 `Order`，调用方对空值无防护 |

### 2026-09-01 并入的大促批量发货部分

| 位置 | 状态 |
|---|---|
| `PromotionShipService.searchActivityOrders` | `activityId`、`keyword`、`sortField` 三个参数**全部拼进 SQL** —— 注入 + `ORDER BY` 无白名单（BUG-004 同类） |
| `PromotionShipService.batchShip` | **不校验操作人权限、不校验 `pay_status`**（BUG-001 同类）；逐条循环 `updateStatus` 无事务边界、无条件更新、无幂等（BUG-005 同类）；`operatorId` 收下了但从未使用 |
| `PromotionController.orderDetail` | **拉出活动全部订单再在内存里 filter** 找单条；不校验归属、不脱敏（BUG-003 同类） |
| `PromotionShipService.recalculatePaidAmount` | ✅ **这一个是正确的**——用 `BigDecimal` 保持精确十进制域、`RoundingMode.HALF_UP` 只在收尾舍入一次，注释里写清了原实现两处只少算不多算的错误。它是本仓金额计算的参照写法 |

> `recalculatePaidAmount` 是 main 上唯一一处**正确的**参照实现，其余均为待修复项。

