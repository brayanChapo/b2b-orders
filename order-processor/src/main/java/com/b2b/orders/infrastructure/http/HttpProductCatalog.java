package com.b2b.orders.infrastructure.http;

import com.b2b.orders.application.port.DependencyException;
import com.b2b.orders.application.port.ProductCatalog;
import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/** Consulta los productos de un pedido en paralelo (virtual threads), con un máximo de llamadas simultáneas. */
public class HttpProductCatalog implements ProductCatalog {

    static final String DEPENDENCY = "products-api";
    static final String PREFIX = "PRODUCTS_API";

    private final RestClient rest;
    private final HttpRetrier retrier;
    private final Semaphore permits;

    public HttpProductCatalog(RestClient rest, HttpRetrier retrier, int parallelism) {
        this.rest = rest;
        this.retrier = retrier;
        this.permits = new Semaphore(parallelism);
    }

    @Override
    public Map<ProductId, Product> findAll(List<ProductId> productIds, Market market) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<Future<Optional<Product>>> futures = new ArrayList<>(productIds.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ProductId id : productIds) {
                futures.add(executor.submit(() -> withContext(mdc, () -> findOne(id, market))));
            }
            return collect(productIds, futures);
        }
    }

    private Optional<Product> findOne(ProductId id, Market market) throws InterruptedException {
        permits.acquire();
        try {
            return retrier.execute(DEPENDENCY, () -> HttpResponses.guard(PREFIX, () -> rest.get()
                    .uri("/products/{productId}?market={market}", id.value(), market.name())
                    .headers(HttpResponses::trace)
                    .exchange((request, response) -> HttpResponses.read(response, ProductResponse.class, PREFIX))
                    .map(CatalogMapper::toProduct)));
        } finally {
            permits.release();
        }
    }

    private static Map<ProductId, Product> collect(List<ProductId> ids, List<Future<Optional<Product>>> futures) {
        Map<ProductId, Product> found = new LinkedHashMap<>();
        DependencyException failure = null;
        for (int i = 0; i < ids.size(); i++) {
            try {
                Optional<Product> product = futures.get(i).get();
                if (product.isPresent()) {
                    found.put(ids.get(i), product.get());
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof DependencyException dependency) {
                    failure = failure == null ? dependency : failure;
                } else if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                } else {
                    throw new IllegalStateException(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DependencyException(DependencyException.Kind.TRANSIENT, DEPENDENCY, "INTERRUPTED",
                        "Consulta de productos interrumpida", 1, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return found;
    }

    private interface Task<T> {
        T run() throws InterruptedException;
    }

    private static <T> T withContext(Map<String, String> mdc, Task<T> task) throws InterruptedException {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            return task.run();
        } finally {
            MDC.clear();
        }
    }
}
