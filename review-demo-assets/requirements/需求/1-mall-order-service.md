# Mall Order Service Demo — 需求清单

> 目标项目：ForgePilot「Mall Order Service Demo」（项目 3）
> 目标仓库：`LinYsssss/reposage-demo-mall-order-service`（私有）
>
> **使用方式**：每条需求的「标题 / 背景 / 描述」直接粘进 ForgePilot 需求表单，「验收条件」逐条录入。
> 分隔线以下的「改动范围」「预期发现」**不要粘贴**。

三条需求分别修复 `main` 分支上现存的 BUG-001、BUG-002、BUG-004 复现代码。**这个仓库是审查演示仓，`main` 上的缺陷是有意保留的**（`README.md` 已声明），所以这三条需求本质上是「让代码追上仓库自己的规范文档」。

> **2026-09-01 变更**：`feature/promotion-batch-ship`（PR #1）已并入 main，`PromotionController` 与 `PromotionShipService` 现在也在 main 上，它们带着同类缺陷的第二份实例——`searchActivityOrders` 三个参数全拼进 SQL、`batchShip` 不校验权限与支付状态且无事务无幂等、`orderDetail` 拉全表再内存过滤且不校验归属不脱敏。**下面三条需求的验收条件都要求覆盖这两处新文件**，只修老文件不算完成。唯一例外是 `recalculatePaidAmount`，它已经是正确实现（`BigDecimal` + 收尾单次 `HALF_UP`），是本仓金额计算的参照写法，不要改坏。

> **仓库现状约束**：`OrderMapper` 是不连数据库的演示用空实现（`findById` 恒返回空 `Order`），`OrderService` / `AdminOrderController` 用 `new` 直接实例化依赖。因此验收条件只要求**代码结构与校验逻辑正确、依赖可注入可测**，不要求真实落库。`Order` 实体目前只有 `status` / `paidAmount` / `shippedAt` 三个 setter，写入其他字段需同步补 setter。

---

## 需求一：发货前置校验与幂等

**标题**

```
发货接口补齐前置校验并保证幂等
```

**背景**

```
OrderService.shipOrder(Long orderId) 当前的实现是：取出订单，直接 order.setStatus("SHIPPED")。

它同时踩中两条历史事故的固化规则：

BUG-001（2025-03，损失约 4.2 万元）——只判断 status 没判断 pay_status。docs/order-flow.md 第 2 节明确写明这两个是独立字段，订单可能 status = WAIT_SHIP 而 pay_status 仍为 UNPAID。当前实现连 status 也没判断，任何状态的订单都会被置为 SHIPPED，属于更严重的状态机跳跃。

BUG-005（2025-11）——发货无幂等控制，网络重试导致同一订单发货两次，产生两个运单。当前实现没有任何防重。

此外，发货成功后应写入 shipped_at 并记录操作人，当前实现两者都没有；shipOrder 也没有接收操作人参数。findById 返回的对象可能为空，当前实现没有空值防护。
```

**描述**

```
按 docs/order-flow.md 第 2 节把发货的四项前置校验补齐，并用条件更新替代无条件写入，使重复调用不产生第二次发货。

发货成功后写入发货时间并记录操作人，因此接口需要接收操作人标识。
```

**验收条件**

```
AC-1  发货前依次校验：订单存在且未逻辑删除、pay_status = PAID、status = WAIT_SHIP、收货地址已填写。任意一项不满足即拒绝发货。
AC-2  pay_status 与 status 分别独立校验，不得用其中之一推断另一个。
AC-3  校验失败按原因给出可区分的结果，调用方能分辨「订单不存在」「未支付」「状态不允许」「地址缺失」，不得统一返回一个笼统失败。
AC-4  状态更新以 status = WAIT_SHIP 作为更新条件，受影响行数为 0 时视为已被处理，按成功返回而不是再次发货，也不抛异常。
AC-5  同一订单连续调用两次发货，只产生一次状态变更、一次发货时间写入。
AC-6  发货成功后写入 shipped_at。
AC-7  shipOrder 接收操作人标识并记录，操作人为空时拒绝发货。
AC-8  订单查询结果为空时安全返回失败，不抛 NullPointerException。
AC-9  OrderService 的 OrderMapper 依赖改为构造器注入，可传入替身以便测试。
AC-10 不引入浮点类型参与任何金额或数量计算。
AC-11 PromotionShipService.batchShip 同样补齐 pay_status 与 status 校验，并使用条件更新；批量过程在单个事务内完成，或具备可重入的补偿路径，不得逐条循环无边界更新。
AC-12 batchShip 的 operatorId 被实际使用并记录，不再是收下即丢弃的参数。
```

---
**改动范围（不要粘贴）**

- `src/main/java/com/example/mall/service/OrderService.java`
- `src/main/java/com/example/mall/mapper/OrderMapper.java`（`updateStatus` 需要能表达条件更新并返回影响行数）
- `src/main/java/com/example/mall/entity/Order.java`（补 setter）

**预期 ForgePilot 会报出的问题**

只补 `status` 校验而漏掉 `pay_status`，应直接命中项目知识 §2 与 BUG-001。用「先查后判再更新」而不用条件更新实现幂等（存在检查-执行竞态）应命中 AC-4。把四项校验写成一个返回布尔值的方法，导致调用方无法分辨失败原因，应命中 AC-3。

---

## 需求二：管理端强制发货补角色校验与审计

**标题**

```
管理端强制发货补齐角色校验与操作审计
```

**背景**

```
AdminOrderController.forceShip(Long orderId) 直接调用 orderService.shipOrder(orderId)，没有任何角色校验，也不写审计日志。路由 /admin/orders/{id}/force-ship 由 route() 方法声明，属于 /admin/** 管理端接口。

BUG-002（2025-06）的成因与此完全一致：新增的 /admin/order/** 接口只加了登录校验没加角色校验，普通用户构造请求即可调用管理端强制发货。docs/security-policy.md 第 1 节的固化规则是「已登录不等于已授权，管理端接口必须校验 ADMIN 角色」。

docs/db-schema.md 同时要求：管理端的强制操作（FORCE_*）必须写 order_operation_log，记录 order_id、operator_id、action、created_at。当前没有任何审计。

强制发货作为管理端操作，其语义是绕过部分常规限制，因此更需要留痕 —— 但它绕过的边界必须是明确的、写下来的，而不是「什么都不检查」。
```

**描述**

```
给管理端强制发货补齐调用方角色校验与操作审计，并明确它相对常规发货放宽了哪些约束、仍然坚持哪些约束。

强制发货可以放宽订单状态的限制，但不得放宽支付状态 —— 未支付订单在任何路径下都不允许发货，这是 BUG-001 的底线。
```

**验收条件**

```
AC-1  forceShip 校验调用方具备 ADMIN 角色，不具备则返回 403，且不执行任何状态变更。
AC-2  仅校验「已登录」不满足本需求，必须是角色校验。
AC-3  强制发货仍然校验 pay_status = PAID，未支付订单在管理端路径下同样被拒绝。
AC-4  强制发货放宽的约束在代码中显式表达并有注释说明，不是隐式跳过。
AC-5  每次强制发货写一条审计记录，含 order_id、operator_id、action = FORCE_SHIP、created_at 四个字段。
AC-6  校验失败（403）不写成功审计；被拒绝的尝试与成功的操作在审计中可区分。
AC-7  审计记录不含收货人手机号、地址等敏感字段明文。
AC-8  forceShip 接收操作人标识，操作人为空时拒绝执行。
AC-9  AdminOrderController 的 OrderService 依赖改为构造器注入，不再用 new 直接实例化。
AC-10 常规发货路径（需求一）的行为不受影响。
AC-11 PromotionController.batchShip 这条运营入口同样校验操作人权限，未授权返回 403 且不发出任何一单；批量发货写审计，记录操作人、活动、成功笔数。
```

---
**改动范围（不要粘贴）**

- `src/main/java/com/example/mall/controller/AdminOrderController.java`
- `src/main/java/com/example/mall/service/OrderService.java`
- 需要新增审计写入的落点（新建 service 或 mapper 方法）

**预期 ForgePilot 会报出的问题**

强制发货连 `pay_status` 一起跳过，应命中 AC-3 与 BUG-001。审计只在成功路径写、被拒绝的越权尝试无痕，应命中 AC-6。把角色校验写在 `route()` 返回的字符串里而不是实际执行路径上，是这个演示仓特有的坑。

---

## 需求三：订单搜索改参数绑定与排序白名单

**标题**

```
订单搜索消除 SQL 注入
```

**背景**

```
OrderMapper.searchByKeyword(String keyword) 直接把关键字拼进 SQL：

  "select * from orders where username like '%" + keyword + "%'"

BUG-004（2025-09）的成因完全相同，安全测试当时就是从订单搜索接口打进去的。docs/security-policy.md 第 2 节的固化规则是「禁止字符串拼接 SQL，一律参数绑定」「排序字段必须走白名单」。

OrderMapper 还有一个 selectBySql(String sql) 方法，它接收整条 SQL 字符串。只要这个入口存在，任何调用方都可以绕过参数绑定 —— 修掉 searchByKeyword 但留着 selectBySql，等于没修。

另外该 SQL 查的是 username 字段，而 docs/db-schema.md 的 orders 表中没有这个字段，收货人相关字段是 receiver_phone 与 receiver_address。
```

**描述**

```
把订单搜索改为参数绑定，排序字段走白名单，并移除可以传入整条 SQL 的后门入口。

搜索结果涉及收货人信息，需按 docs/security-policy.md 第 3 节脱敏。
```

**验收条件**

```
AC-1  搜索关键字通过参数绑定传入，SQL 语句中不出现由外部输入拼接而成的片段。
AC-2  排序字段走显式白名单，白名单外的取值回落到默认排序而不是拼进 ORDER BY，也不报错泄露字段名。
AC-3  排序方向同样受限于 asc / desc 两个取值。
AC-4  接收整条 SQL 字符串的入口（selectBySql）被移除，或收窄到无法被外部输入驱动；仓库中不再存在可以从调用方传入完整 SQL 的路径。
AC-5  查询排除 deleted_at 非空的逻辑删除记录。
AC-6  搜索字段修正为 orders 表中实际存在的字段，与 docs/db-schema.md 一致。
AC-7  返回结果中的 receiver_phone 与 receiver_address 脱敏。
AC-8  日志中不输出完整手机号、完整地址，也不输出拼接后的 SQL。
AC-9  关键字为空或全空白时返回默认结果集，不构造 like '%%' 全表扫描。
AC-10 关键字长度超出上限时被拒绝，不无限制接受任意长度输入。
AC-11 PromotionShipService.searchActivityOrders 同样改为参数绑定，activityId、keyword、sortField 三者都不再拼进 SQL，排序走同一份白名单。
AC-12 PromotionController.orderDetail 不再「拉出活动全部订单再在内存里 filter」，改为按 ID 单条查询，并校验归属、脱敏收货人信息。
```

---
**改动范围（不要粘贴）**

- `src/main/java/com/example/mall/mapper/OrderMapper.java`
- 调用方（若新增 Service 层搜索方法）

**预期 ForgePilot 会报出的问题**

修掉 `searchByKeyword` 却留着 `selectBySql` 是这条需求最可能的漏项，应命中 AC-4。用转义或过滤单引号来「防注入」而不是参数绑定，应命中 AC-1 与项目知识 §6。把排序字段做字符串校验后仍然拼接（白名单校验 + 拼接）是可接受的，但白名单必须是取值枚举而非正则，审查会关注这一点。
