package com.b2b.orders.application.outbox;

import java.util.Map;

public record OutboxMessage(String id, String topic, String key, String payload, Map<String, String> headers, int attempts) {

    public OutboxMessage {
        headers = Map.copyOf(headers);
    }
}
