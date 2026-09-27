package com.b2b.orders.infrastructure.kafka;

import com.b2b.orders.application.ErrorCategory;
import com.b2b.orders.application.EventMetadata;
import com.b2b.orders.application.ProcessOrderUseCase;
import com.b2b.orders.application.ProcessingOutcome;
import com.b2b.orders.application.StoreOutcome;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.validation.InvalidOrderException;
import com.b2b.orders.domain.validation.OrderValidator;
import com.b2b.orders.infrastructure.mongo.DeliveryGuard;
import com.b2b.orders.infrastructure.mongo.EventLedger;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumidor de orders.created.v1. Ack por registro: el offset se confirma solo si este
 * método termina sin excepción (resultado persistido, evento descartado o enviado a la DLT).
 * Una excepción deja el offset sin confirmar y el DefaultErrorHandler reintenta con backoff.
 */
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    private final OrderEventParser parser;
    private final OrderValidator validator;
    private final ProcessOrderUseCase useCase;
    private final DeliveryGuard guard;
    private final EventLedger ledger;
    private final KafkaDeadLetterPublisher deadLetters;
    private final FailureCounter failures;
    private final ProcessingMetrics metrics;
    private final Clock clock;

    public OrderCreatedListener(OrderEventParser parser, OrderValidator validator, ProcessOrderUseCase useCase,
            DeliveryGuard guard, EventLedger ledger, KafkaDeadLetterPublisher deadLetters, FailureCounter failures,
            ProcessingMetrics metrics, Clock clock) {
        this.parser = parser;
        this.validator = validator;
        this.useCase = useCase;
        this.guard = guard;
        this.ledger = ledger;
        this.deadLetters = deadLetters;
        this.failures = failures;
        this.metrics = metrics;
        this.clock = clock;
    }

    @KafkaListener(topics = "${app.topics.input}")
    public void onMessage(ConsumerRecord<String, String> record) {
        DeliveryKey key = DeliveryKey.of(record);
        Instant receivedAt = clock.instant();
        OrderEventParser.EventIds ids = parser.peekIds(record.value());
        MDC.put("topic", record.topic());
        MDC.put("partition", Integer.toString(record.partition()));
        MDC.put("offset", Long.toString(record.offset()));
        ids.eventId().ifPresent(id -> MDC.put("eventId", id));
        ids.orderId().ifPresent(id -> MDC.put("orderId", id));
        MDC.put("traceId", ids.eventId().orElse(key.id()));
        Timer.Sample sample = metrics.startProcessing();
        String outcome = "error";
        try {
            log.info("event_received");
            DeliveryGuard.Delivery delivery = guard.begin(key, ids.eventId(), ids.orderId(), receivedAt);
            if (delivery.poison()) {
                deadLetter(record, ids, ErrorCategory.REPEATED_DELIVERY_FAILURE, "REPEATED_DELIVERY_FAILURE",
                        "El registro superó " + delivery.crashes() + " entregas sin completarse", delivery.crashes() + 1);
                guard.finishQuietly(key, DeliveryGuard.DEAD_LETTERED, clock.instant());
                outcome = "dead_lettered";
                return;
            }

            OrderRequest order;
            try {
                order = validator.validate(parser.parse(record.value()));
            } catch (MalformedEventException e) {
                rejectInvalid(record, key, ids, e.code(), e.getMessage());
                outcome = "invalid";
                return;
            } catch (InvalidOrderException e) {
                rejectInvalid(record, key, ids, e.primary().code().name(), e.getMessage());
                outcome = "invalid";
                return;
            }
            log.info("event_validated items={}", order.items().size());

            EventMetadata metadata = new EventMetadata(record.topic(), record.partition(), record.offset(),
                    receivedAt, MDC.get("traceId"), record.value());
            ProcessingOutcome result = useCase.process(order, metadata);

            guard.finishQuietly(key, DeliveryGuard.DONE, clock.instant());
            failures.reset(key);
            if (result.status().isPresent()) {
                metrics.processed(result.status().get());
                outcome = result.status().get().name().toLowerCase();
            } else {
                metrics.skipped(result.storeOutcome());
                ledger.record(order.eventId().value(), order.orderId().value(), order.eventVersion(),
                        result.storeOutcome().name(), clock.instant());
                outcome = result.storeOutcome().name().toLowerCase();
                if (result.storeOutcome() == StoreOutcome.CONFLICT) {
                    log.warn("event_conflict: otro evento ya ocupa esta versión del pedido");
                }
            }
        } catch (RuntimeException e) {
            int attempt = failures.increment(key);
            log.error("event_processing_failed attempt={} error={}", attempt, e.toString());
            guard.finishQuietly(key, DeliveryGuard.RETRYING, clock.instant());
            throw e;
        } finally {
            metrics.stopProcessing(sample, outcome);
            MDC.clear();
        }
    }

    private void rejectInvalid(ConsumerRecord<String, String> record, DeliveryKey key,
            OrderEventParser.EventIds ids, String code, String summary) {
        log.warn("event_invalid code={} detail={}", code, summary);
        deadLetter(record, ids, ErrorCategory.VALIDATION_ERROR, code, summary, 1);
        guard.finishQuietly(key, DeliveryGuard.DEAD_LETTERED, clock.instant());
        ids.eventId().ifPresent(eventId -> ledger.record(eventId, ids.orderId().orElse(null), null, "INVALID", clock.instant()));
    }

    private void deadLetter(ConsumerRecord<String, String> record, OrderEventParser.EventIds ids,
            ErrorCategory category, String code, String summary, int attempts) {
        deadLetters.publish(new DeadLetter(record.topic(), record.partition(), record.offset(), record.key(),
                record.value(), ids.orderId(), ids.eventId(), category, code, summary, attempts, clock.instant()));
        metrics.deadLettered(category);
    }
}
