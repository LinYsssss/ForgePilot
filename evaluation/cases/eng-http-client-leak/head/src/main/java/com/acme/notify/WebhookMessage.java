package com.acme.notify;

public record WebhookMessage(String targetUrl, String eventType, String payloadJson) {
}
