package com.b2b.orders.application.outbox;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final MessagePublisher publisher;
    private final Duration lease;
    private final Duration initialRetry;
    private final Duration maxRetry;

    public OutboxRelay(OutboxStore store, MessagePublisher publisher, Duration lease, Duration initialRetry, Duration maxRetry) {
        this.store = store;
        this.publisher = publisher;
        this.lease = lease;
        this.initialRetry = initialRetry;
        this.maxRetry = maxRetry;
    }

    /** Publica hasta maxMessages pendientes. Devuelve cuántos se publicaron. */
    public int relay(int maxMessages) {
        int published = 0;
        for (int i = 0; i < maxMessages; i++) {
            Optional<OutboxMessage> next = store.claimNext(lease);
            if (next.isEmpty()) {
                break;
            }
            OutboxMessage message = next.get();
            try {
                publisher.publish(message);
                store.markSent(message.id());
                published++;
                log.info("outbox_published topic={} key={} id={}", message.topic(), message.key(), message.id());
            } catch (RuntimeException e) {
                Duration retryIn = backoff(message.attempts());
                store.markFailed(message.id(), e.getClass().getSimpleName() + ": " + e.getMessage(), retryIn);
                log.warn("outbox_publish_failed topic={} key={} attempts={} retryInMs={}",
                        message.topic(), message.key(), message.attempts() + 1, retryIn.toMillis());
                break;
            }
        }
        return published;
    }

    Duration backoff(int previousAttempts) {
        long millis = initialRetry.toMillis() << Math.min(previousAttempts, 16);
        return Duration.ofMillis(Math.min(millis, maxRetry.toMillis()));
    }
}
