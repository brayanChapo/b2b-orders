package com.b2b.orders.infrastructure.config;

import com.b2b.orders.application.ProcessOrderService;
import com.b2b.orders.application.outbox.MessagePublisher;
import com.b2b.orders.application.outbox.OutboxRelay;
import com.b2b.orders.application.outbox.OutboxStore;
import com.b2b.orders.application.port.ClientCatalog;
import com.b2b.orders.application.port.OrderResultStore;
import com.b2b.orders.application.port.ProductCatalog;
import com.b2b.orders.domain.OrderEvaluator;
import com.b2b.orders.domain.eligibility.EligibilityPolicy;
import com.b2b.orders.domain.pricing.DiscountPolicy;
import com.b2b.orders.domain.pricing.OrderCalculator;
import com.b2b.orders.domain.pricing.TaxPolicy;
import com.b2b.orders.domain.validation.OrderValidator;
import com.b2b.orders.infrastructure.kafka.OrderEventParser;
import com.b2b.orders.infrastructure.messaging.OrderProcessedEventMapper;
import com.b2b.orders.infrastructure.messaging.UlidGenerator;
import com.b2b.orders.infrastructure.mongo.MongoOutboxStore;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import com.b2b.orders.infrastructure.outbox.OutboxRelayScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrderEvaluator orderEvaluator() {
        return new OrderEvaluator(new EligibilityPolicy(), new OrderCalculator(new TaxPolicy(), new DiscountPolicy()));
    }

    @Bean
    OrderValidator orderValidator() {
        return new OrderValidator();
    }

    @Bean
    ProcessOrderService processOrderService(ClientCatalog clients, ProductCatalog products, OrderResultStore store,
            OrderEvaluator evaluator, Clock clock) {
        return new ProcessOrderService(clients, products, store, evaluator, clock);
    }

    @Bean
    UlidGenerator ulidGenerator(Clock clock) {
        return new UlidGenerator(clock);
    }

    @Bean
    OrderProcessedEventMapper orderProcessedEventMapper() {
        return new OrderProcessedEventMapper();
    }

    @Bean
    OrderEventParser orderEventParser(AppProperties props) {
        return new OrderEventParser(props.consumer().maxPayloadBytes(), props.consumer().maxItems());
    }

    @Bean
    ProcessingMetrics processingMetrics(MeterRegistry registry) {
        return new ProcessingMetrics(registry);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore store, MessagePublisher publisher, AppProperties props) {
        AppProperties.Outbox outbox = props.outbox();
        return new OutboxRelay(store, publisher, outbox.lease(), outbox.initialRetry(), outbox.maxRetry());
    }

    @Bean
    OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay, MongoOutboxStore store, ProcessingMetrics metrics,
            Clock clock, AppProperties props) {
        return new OutboxRelayScheduler(relay, store, metrics, clock, props.outbox().batchSize());
    }
}
