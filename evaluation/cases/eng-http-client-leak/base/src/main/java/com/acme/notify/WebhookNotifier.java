package com.acme.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    public void send(WebhookMessage message) {
        log.info("webhook delivery disabled, dropping event {} for {}", message.eventType(), message.targetUrl());
    }
}
