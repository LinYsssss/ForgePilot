package com.example.codereview.scm;

/**
 * 一次收到的 webhook 投递的生命周期:从原始接收,经验签,到处理完成。
 *
 * <p>持久化在 {@link WebhookDelivery} 上,用于幂等与审计。投递在**做出任何信任判断之前**
 * 就先落成 {@link #RECEIVED};签名/令牌与解析出的 installation 密钥对上后升为 {@link #VERIFIED};
 * 最终产出 Agent Run 后置为 {@link #PROCESSED}。重放表现为 {@link #DUPLICATE};
 * 不可信或匹配不到 installation 的表现为 {@link #REJECTED};验签之后才冒出的意外错误为
 * {@link #FAILED}。
 */
public enum WebhookDeliveryStatus {
    /** 验签之前先落档的原始投递。 */
    RECEIVED,
    /** 签名或令牌已与解析出的 installation 密钥校验通过。 */
    VERIFIED,
    /** 同一组 {@code (provider, deliveryId)} 此前已记录过。 */
    DUPLICATE,
    /** 验签失败,或没有匹配到任何 installation;绝不会被处理。 */
    REJECTED,
    /** 已成功转成 Agent Run / outbox 事件。 */
    PROCESSED,
    /** 验签通过之后,处理阶段抛出了预期外的错误。 */
    FAILED
}
