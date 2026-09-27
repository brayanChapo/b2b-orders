package com.b2b.orders.infrastructure.kafka;

import com.b2b.orders.application.ErrorCategory;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/** Se invoca cuando se agota el backoff de un error técnico no manejado (típicamente MongoDB caído). */
public class PersistenceFailureRecoverer implements ConsumerRecordRecoverer {

    private final KafkaDeadLetterPublisher deadLetters;
    private final OrderEventParser parser;
    private final FailureCounter failures;
    private final ProcessingMetrics metrics;
    private final Clock clock;

    public PersistenceFailureRecoverer(KafkaDeadLetterPublisher deadLetters, OrderEventParser parser,
            FailureCounter failures, ProcessingMetrics metrics, Clock clock) {
        this.deadLetters = deadLetters;
        this.parser = parser;
        this.failures = failures;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        String payload = record.value() == null ? null : record.value().toString();
        OrderEventParser.EventIds ids = parser.peekIds(payload);
        Throwable cause = rootCause(exception);
        deadLetters.publish(new DeadLetter(record.topic(), record.partition(), record.offset(),
                record.key() == null ? null : record.key().toString(), payload,
                ids.orderId(), ids.eventId(), ErrorCategory.PERSISTENCE_ERROR,
                cause.getClass().getSimpleName(), String.valueOf(cause.getMessage()),
                failures.remove(DeliveryKey.of(record)), clock.instant()));
        metrics.deadLettered(ErrorCategory.PERSISTENCE_ERROR);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
