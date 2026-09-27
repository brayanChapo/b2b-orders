package com.b2b.orders.application;

import com.b2b.orders.domain.eligibility.RejectionReason;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.pricing.PricedOrder;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProcessedOrder(
        OrderRequest order,
        ProcessingStatus status,
        Client client,
        PricedOrder pricing,
        List<RejectionReason> reasons,
        TechnicalError error,
        EventMetadata metadata,
        Instant processedAt) {

    public ProcessedOrder {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(processedAt, "processedAt");
        reasons = List.copyOf(reasons);
        switch (status) {
            case APPROVED -> require(pricing != null && reasons.isEmpty() && error == null, "APPROVED requiere pricing");
            case REJECTED -> require(pricing == null && !reasons.isEmpty() && error == null, "REJECTED requiere razones");
            case TECHNICAL_FAILURE -> require(pricing == null && reasons.isEmpty() && error != null, "TECHNICAL_FAILURE requiere error");
        }
    }

    public static ProcessedOrder approved(OrderRequest order, Client client, PricedOrder pricing,
            EventMetadata metadata, Instant at) {
        return new ProcessedOrder(order, ProcessingStatus.APPROVED, client, pricing, List.of(), null, metadata, at);
    }

    public static ProcessedOrder rejected(OrderRequest order, Client client, List<RejectionReason> reasons,
            EventMetadata metadata, Instant at) {
        return new ProcessedOrder(order, ProcessingStatus.REJECTED, client, null, reasons, null, metadata, at);
    }

    public static ProcessedOrder technicalFailure(OrderRequest order, Client client, TechnicalError error,
            EventMetadata metadata, Instant at) {
        return new ProcessedOrder(order, ProcessingStatus.TECHNICAL_FAILURE, client, null, List.of(), error, metadata, at);
    }

    public Optional<Client> clientIfKnown() {
        return Optional.ofNullable(client);
    }

    public Optional<RejectionReason> primaryReason() {
        return reasons.stream().findFirst();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
