package com.b2b.orders.domain.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public record OrderRequest(
        EventId eventId,
        int eventVersion,
        Instant occurredAt,
        OrderId orderId,
        Market market,
        Currency currency,
        ClientId clientId,
        String channel,
        List<OrderItem> items) {

    public OrderRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(clientId, "clientId");
        items = List.copyOf(items);
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion debe ser mayor o igual que 1");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("un pedido tiene al menos una línea");
        }
        if (currency != market.currency()) {
            throw new IllegalArgumentException("la moneda no corresponde al mercado");
        }
        var seen = new HashSet<ProductId>();
        for (OrderItem item : items) {
            if (!seen.add(item.productId())) {
                throw new IllegalArgumentException("productId repetido: " + item.productId());
            }
        }
    }

    /** occurredAt es opcional en el contrato de entrada. */
    public Optional<Instant> occurredAtIfPresent() {
        return Optional.ofNullable(occurredAt);
    }
}
