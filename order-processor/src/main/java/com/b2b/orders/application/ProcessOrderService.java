package com.b2b.orders.application;

import com.b2b.orders.application.port.ClientCatalog;
import com.b2b.orders.application.port.DependencyException;
import com.b2b.orders.application.port.OrderResultStore;
import com.b2b.orders.application.port.ProductCatalog;
import com.b2b.orders.domain.OrderDecision;
import com.b2b.orders.domain.OrderEvaluator;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.OrderItem;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessOrderService implements ProcessOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessOrderService.class);

    private final ClientCatalog clients;
    private final ProductCatalog products;
    private final OrderResultStore store;
    private final OrderEvaluator evaluator;
    private final Clock clock;

    public ProcessOrderService(ClientCatalog clients, ProductCatalog products, OrderResultStore store,
            OrderEvaluator evaluator, Clock clock) {
        this.clients = clients;
        this.products = products;
        this.store = store;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    @Override
    public ProcessingOutcome process(OrderRequest order, EventMetadata metadata) {
        Optional<StoreOutcome> skip = store.findState(order.orderId())
                .flatMap(current -> VersionPolicy.classify(current, order.eventId(), order.eventVersion()));
        if (skip.isPresent()) {
            log.info("event_skipped outcome={}", skip.get());
            return ProcessingOutcome.skipped(skip.get());
        }

        ProcessedOrder result = evaluate(order, metadata);
        StoreOutcome stored = store.save(result);
        if (stored == StoreOutcome.SAVED) {
            log.info("order_persisted status={}", result.status());
            return ProcessingOutcome.stored(result.status(), stored);
        }
        log.info("event_skipped outcome={} stage=write", stored);
        return ProcessingOutcome.skipped(stored);
    }

    private ProcessedOrder evaluate(OrderRequest order, EventMetadata metadata) {
        Client client = null;
        try {
            Optional<Client> found = clients.findById(order.clientId());
            client = found.orElse(null);
            log.info("client_fetched found={}", found.isPresent());

            Optional<OrderDecision.Rejected> byClient = evaluator.screenClient(order, found);
            if (byClient.isPresent()) {
                return toResult(order, client, byClient.get(), metadata);
            }

            List<ProductId> ids = order.items().stream().map(OrderItem::productId).toList();
            Map<ProductId, Product> catalog = products.findAll(ids, order.market());
            log.info("products_fetched requested={} found={}", ids.size(), catalog.size());

            return toResult(order, client, evaluator.decide(order, found.orElseThrow(), catalog), metadata);
        } catch (DependencyException e) {
            TechnicalError error = new TechnicalError(
                    e.isTransient() ? ErrorCategory.TRANSIENT_DEPENDENCY_EXHAUSTED : ErrorCategory.DEFINITIVE_DEPENDENCY_ERROR,
                    e.code(), e.getMessage(), e.attempts());
            log.warn("dependency_failed dependency={} code={} attempts={}", e.dependency(), e.code(), e.attempts());
            return ProcessedOrder.technicalFailure(order, client, error, metadata, clock.instant());
        }
    }

    private ProcessedOrder toResult(OrderRequest order, Client client, OrderDecision decision, EventMetadata metadata) {
        ProcessedOrder result = switch (decision) {
            case OrderDecision.Approved approved ->
                    ProcessedOrder.approved(order, client, approved.pricing(), metadata, clock.instant());
            case OrderDecision.Rejected rejected ->
                    ProcessedOrder.rejected(order, client, rejected.reasons(), metadata, clock.instant());
        };
        log.info("order_decided status={}{}", result.status(),
                result.primaryReason().map(r -> " reason=" + r.code()).orElse(""));
        return result;
    }
}
