# 历史 Bug 记录

## BUG-001 未支付订单被发货

原因：发货接口只判断订单状态，没有判断支付状态。

修复：在 `OrderService.shipOrder` 中增加 `pay_status = PAID` 校验。

## BUG-002 管理接口越权访问

原因：新增管理接口没有添加角色校验。

修复：为 `/admin/order/**` 接口添加 `ADMIN` 权限校验。
