package com.b2b.orders.application;

import com.b2b.orders.application.port.ClientCatalog;
import com.b2b.orders.application.port.DependencyException;
import com.b2b.orders.application.port.OrderResultStore;
import com.b2b.orders.application.port.ProductCatalog;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientId;
import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.OrderId;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class Fakes {

    private Fakes() {
    }

    static final class Clients implements ClientCatalog {
        final Map<ClientId, Client> data = new HashMap<>();
        DependencyException failure;
        int calls;

        @Override
        public Optional<Client> findById(ClientId clientId) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(data.get(clientId));
        }
    }

    static final class Products implements ProductCatalog {
        final Map<ProductId, Product> data = new HashMap<>();
        DependencyException failure;
        int calls;

        @Override
        public Map<ProductId, Product> findAll(List<ProductId> productIds, Market market) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            Map<ProductId, Product> found = new HashMap<>();
            productIds.forEach(id -> {
                if (data.containsKey(id)) {
                    found.put(id, data.get(id));
                }
            });
            return found;
        }
    }

    static final class Store implements OrderResultStore {
        final Map<OrderId, StoredOrderState> states = new HashMap<>();
        final List<ProcessedOrder> saved = new ArrayList<>();
        StoreOutcome nextOutcome = StoreOutcome.SAVED;

        @Override
        public Optional<StoredOrderState> findState(OrderId orderId) {
            return Optional.ofNullable(states.get(orderId));
        }

        @Override
        public StoreOutcome save(ProcessedOrder result) {
            if (nextOutcome == StoreOutcome.SAVED) {
                saved.add(result);
                states.put(result.order().orderId(),
                        new StoredOrderState(result.order().eventId(), result.order().eventVersion(), result.status()));
            }
            return nextOutcome;
        }
    }
}
