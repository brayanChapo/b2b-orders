package com.b2b.orders.infrastructure.observability;

import com.b2b.orders.application.ErrorCategory;
import com.b2b.orders.application.ProcessingStatus;
import com.b2b.orders.application.StoreOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * orders.processed{status}      aprobados, rechazados y fallos técnicos
 * orders.skipped{outcome}       duplicados, obsoletos y conflictos
 * orders.dead_lettered{category}
 * orders.dependency.retries{dependency}
 * orders.processing.duration{outcome}
 * outbox.published{topic} / outbox.publish.failures{topic} / outbox.oldest_pending.age.seconds
 */
public class ProcessingMetrics {

    private final MeterRegistry registry;
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public ProcessingMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("outbox.oldest_pending.age.seconds", oldestPendingAgeSeconds);
    }

    public void processed(ProcessingStatus status) {
        registry.counter("orders.processed", "status", status.name()).increment();
    }

    public void skipped(StoreOutcome outcome) {
        registry.counter("orders.skipped", "outcome", outcome.name()).increment();
    }

    public void deadLettered(ErrorCategory category) {
        registry.counter("orders.dead_lettered", "category", category.name()).increment();
    }

    public void retry(String dependency) {
        registry.counter("orders.dependency.retries", "dependency", dependency).increment();
    }

    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    public void stopProcessing(Timer.Sample sample, String outcome) {
        sample.stop(registry.timer("orders.processing.duration", "outcome", outcome));
    }

    public void outboxPublished(String topic) {
        registry.counter("outbox.published", "topic", topic).increment();
    }

    public void outboxPublishFailed(String topic) {
        registry.counter("outbox.publish.failures", "topic", topic).increment();
    }

    public void oldestPendingAge(long seconds) {
        oldestPendingAgeSeconds.set(seconds);
    }
}
