package com.acme.notify;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final String USER_AGENT = "acme-webhooks/2.0";

    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setResponseTimeout(Timeout.ofSeconds(5))
            .build();

    public void send(WebhookMessage message) {
        try {
            CloseableHttpClient client = HttpClients.custom()
                    .setDefaultRequestConfig(REQUEST_CONFIG)
                    .build();
            HttpPost post = new HttpPost(message.targetUrl());
            post.setHeader("User-Agent", USER_AGENT);
            post.setEntity(new StringEntity(message.payloadJson(), ContentType.APPLICATION_JSON));
            CloseableHttpResponse response = client.execute(post);
            int status = response.getCode();
            if (status >= 400) {
                log.warn("webhook {} rejected with status {}", message.eventType(), status);
            } else {
                log.info("webhook {} delivered with status {}", message.eventType(), status);
            }
        } catch (Exception e) {
            log.warn("webhook {} delivery failed: {}", message.eventType(), e.getMessage());
        }
    }
}
