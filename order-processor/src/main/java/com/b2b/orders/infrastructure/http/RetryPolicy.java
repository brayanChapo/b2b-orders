package com.b2b.orders.infrastructure.http;

import java.time.Duration;

public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        double multiplier,
        double jitter,
        Duration maxRetryAfter) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser al menos 1");
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("jitter debe estar en [0, 1)");
        }
    }

    Duration backoff(int attempt, double random) {
        double base = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        double factor = 1 + jitter * (2 * random - 1);
        return Duration.ofMillis(Math.round(base * factor));
    }
}
