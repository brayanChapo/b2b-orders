package com.b2b.orders.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String component,
        Topics topics,
        MongoDb mongodb,
        Endpoint clientsApi,
        Endpoint productsApi,
        Http http,
        Consumer consumer,
        Outbox outbox,
        PersistenceRetry persistenceRetry) {

    public record Topics(String input, String output, String deadLetter) {
    }

    public record MongoDb(String database) {
    }

    public record Endpoint(String baseUrl) {
    }

    public record Http(
            Duration connectTimeout,
            Duration readTimeout,
            int maxAttempts,
            Duration initialBackoff,
            double backoffMultiplier,
            double jitter,
            Duration maxRetryAfter,
            int productParallelism) {
    }

    public record Consumer(int maxPayloadBytes, int maxItems, int maxDeliveryCrashes, Duration publishTimeout) {
    }

    public record Outbox(long pollIntervalMillis, int batchSize, Duration lease, Duration initialRetry, Duration maxRetry) {
    }

    public record PersistenceRetry(Duration initialInterval, double multiplier, Duration maxInterval, Duration maxElapsed) {
    }
}
