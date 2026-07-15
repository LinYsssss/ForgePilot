package com.example.codereview.scm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    Optional<WebhookDelivery> findByProviderAndDeliveryId(ScmProviderType provider, String deliveryId);
}
