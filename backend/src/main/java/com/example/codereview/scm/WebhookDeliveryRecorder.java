package com.example.codereview.scm;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Webhook 投递记录的落库入口,两个 SCM 控制器共用。
 *
 * <p>只在**验签通过之后**才写入。未验签的流量(未知安装、错误签名)不落库:任何人都能
 * 对公开的 webhook 端点发请求,若照单全收就等于开放了一张可被任意灌入的审计表。
 *
 * <p>幂等靠数据库唯一键 {@code uq_scm_delivery_provider_delivery} 而不是"先查后写"。
 * 先查后写在并发下两个请求都会查不到、都去插入,其中一个必然撞唯一键并冒成 5xx;
 * 这里直接尝试插入,撞键即说明另一路已经处理,回查取其结果即可。
 */
@Component
public class WebhookDeliveryRecorder {

    private final WebhookDeliveryRepository deliveries;
    private final boolean storePayloadPreview;

    public WebhookDeliveryRecorder(WebhookDeliveryRepository deliveries,
                                   @Value("${app.scm.webhook.store-payload-preview:false}") boolean storePayloadPreview) {
        this.deliveries = deliveries;
        this.storePayloadPreview = storePayloadPreview;
    }

    /** 记录结果:要么本次新建(继续处理),要么已存在(重复投递)。 */
    public record Recorded(WebhookDelivery delivery, boolean created) {
    }

    /**
     * 幂等地登记一次已验签的投递。
     *
     * @param preview 原始报文文本;仅当显式开启调试开关时才落库,默认丢弃
     */
    public Recorded recordVerified(ScmProviderType provider, String deliveryId, String eventType,
                                   String payloadHash, Long installationId, String preview) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setProvider(provider);
        delivery.setDeliveryId(deliveryId);
        delivery.setEventType(eventType);
        delivery.setStatus(WebhookDeliveryStatus.VERIFIED);
        delivery.setPayloadHash(payloadHash);
        delivery.setInstallationId(installationId);
        if (storePayloadPreview) {
            // 报文可能带有分支名、提交信息等仓库内容,默认不入库;开关仅供本地排障。
            delivery.setPayloadPreview(preview);
        }
        try {
            return new Recorded(deliveries.saveAndFlush(delivery), true);
        } catch (DataIntegrityViolationException duplicate) {
            // 唯一键冲突 = 同一 delivery 已被另一路请求登记,取回它的结果而不是报错。
            Optional<WebhookDelivery> existing = deliveries.findByProviderAndDeliveryId(provider, deliveryId);
            return new Recorded(existing.orElse(delivery), false);
        }
    }

    public void save(WebhookDelivery delivery) {
        deliveries.save(delivery);
    }
}
