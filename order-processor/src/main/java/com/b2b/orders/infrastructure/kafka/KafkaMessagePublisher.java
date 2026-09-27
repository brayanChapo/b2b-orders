package com.b2b.orders.infrastructure.kafka;

import com.b2b.orders.application.outbox.MessagePublisher;
import com.b2b.orders.application.outbox.OutboxMessage;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import java.time.Duration;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaMessagePublisher implements MessagePublisher {

    private final KafkaTemplate<String, String> template;
    private final Duration timeout;
    private final ProcessingMetrics metrics;

    public KafkaMessagePublisher(KafkaTemplate<String, String> template, Duration timeout, ProcessingMetrics metrics) {
        this.template = template;
        this.timeout = timeout;
        this.metrics = metrics;
    }

    @Override
    public void publish(OutboxMessage message) {
        try {
            KafkaSender.sendAndWait(template, message.topic(), message.key(), message.payload(), message.headers(), timeout);
            metrics.outboxPublished(message.topic());
        } catch (RuntimeException e) {
            metrics.outboxPublishFailed(message.topic());
            throw e;
        }
    }
}
