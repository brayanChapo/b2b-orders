package com.b2b.orders.infrastructure.outbox;

import com.b2b.orders.application.outbox.OutboxRelay;
import com.b2b.orders.infrastructure.mongo.MongoOutboxStore;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final MongoOutboxStore store;
    private final ProcessingMetrics metrics;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelayScheduler(OutboxRelay relay, MongoOutboxStore store, ProcessingMetrics metrics,
            Clock clock, int batchSize) {
        this.relay = relay;
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-millis}")
    public void run() {
        try {
            while (relay.relay(batchSize) == batchSize) {
                log.debug("outbox_batch_full");
            }
            metrics.oldestPendingAge(store.oldestPendingCreatedAt()
                    .map(created -> Math.max(0, Duration.between(created, clock.instant()).toSeconds()))
                    .orElse(0L));
        } catch (RuntimeException e) {
            log.warn("outbox_relay_failed error={}", e.toString());
        }
    }
}
