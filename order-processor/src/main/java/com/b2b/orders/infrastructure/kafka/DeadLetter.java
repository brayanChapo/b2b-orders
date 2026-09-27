package com.b2b.orders.infrastructure.kafka;

import com.b2b.orders.application.ErrorCategory;
import java.time.Instant;
import java.util.Optional;

public record DeadLetter(
        String originalTopic,
        int originalPartition,
        long originalOffset,
        String key,
        String payload,
        Optional<String> orderId,
        Optional<String> eventId,
        ErrorCategory category,
        String code,
        String summary,
        int attempts,
        Instant failedAt) {
}
