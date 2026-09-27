package com.b2b.orders.application;

import java.time.Instant;

public record EventMetadata(
        String topic,
        int partition,
        long offset,
        Instant receivedAt,
        String traceId,
        String rawPayload) {
}
