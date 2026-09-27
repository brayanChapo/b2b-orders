package com.b2b.orders.infrastructure.config;

import com.b2b.orders.application.ProcessOrderUseCase;
import com.b2b.orders.domain.validation.OrderValidator;
import com.b2b.orders.infrastructure.kafka.FailureCounter;
import com.b2b.orders.infrastructure.kafka.KafkaDeadLetterPublisher;
import com.b2b.orders.infrastructure.kafka.KafkaMessagePublisher;
import com.b2b.orders.infrastructure.kafka.OrderCreatedListener;
import com.b2b.orders.infrastructure.kafka.OrderEventParser;
import com.b2b.orders.infrastructure.kafka.PersistenceFailureRecoverer;
import com.b2b.orders.infrastructure.mongo.DeliveryGuard;
import com.b2b.orders.infrastructure.mongo.EventLedger;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfiguration {

    @Bean
    KafkaDeadLetterPublisher deadLetterPublisher(KafkaTemplate<String, String> template, AppProperties props) {
        return new KafkaDeadLetterPublisher(template, props.topics().deadLetter(), props.component(),
                props.consumer().publishTimeout());
    }

    @Bean
    KafkaMessagePublisher messagePublisher(KafkaTemplate<String, String> template, AppProperties props,
            ProcessingMetrics metrics) {
        return new KafkaMessagePublisher(template, props.consumer().publishTimeout(), metrics);
    }

    @Bean
    FailureCounter failureCounter() {
        return new FailureCounter();
    }

    /**
     * Errores técnicos no manejados (MongoDB caído, fallo al publicar en la DLT): se reintenta
     * sin confirmar el offset. La partición queda detenida a propósito (backpressure) y solo
     * al agotar maxElapsed el mensaje va a la DLT como PERSISTENCE_ERROR.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaDeadLetterPublisher deadLetters, OrderEventParser parser,
            FailureCounter failures, ProcessingMetrics metrics, Clock clock, AppProperties props) {
        AppProperties.PersistenceRetry retry = props.persistenceRetry();
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxElapsedTime(retry.maxElapsed().toMillis());
        return new DefaultErrorHandler(
                new PersistenceFailureRecoverer(deadLetters, parser, failures, metrics, clock), backOff);
    }

    @Bean
    OrderCreatedListener orderCreatedListener(OrderEventParser parser, OrderValidator validator,
            ProcessOrderUseCase useCase, DeliveryGuard guard, EventLedger ledger,
            KafkaDeadLetterPublisher deadLetters, FailureCounter failures, ProcessingMetrics metrics, Clock clock) {
        return new OrderCreatedListener(parser, validator, useCase, guard, ledger, deadLetters, failures, metrics, clock);
    }
}
